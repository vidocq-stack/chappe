/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.http.HttpResponseWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Socket-write counts of the HTTP/1.1 writer for known-length bodies.
 *
 * <p>A body whose next read would not block ({@code available() > 0}), such as
 * a classpath resource served from a jar by {@code StaticFileHandler}, is
 * coalesced into full write buffers. A body whose producer may block
 * ({@code available() == 0}) is flushed after each read.</p>
 */
class ResponseWriteCoalescingTest {

    private static final int BUFFER_SIZE = 16 * 1024; // HttpConnection write buffer
    private static final int BODY_SIZE = 64 * 1024;

    @TempDir
    Path tmp;

    @Test
    void classpathJarResourceIsCoalescedIntoFullBuffers() throws IOException {
        byte[] data = payload();
        Path jar = tmp.resolve("static.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("static/big.bin"));
            out.write(data);
            out.closeEntry();
        }
        var url = URI.create("jar:" + jar.toUri() + "!/static/big.bin").toURL();
        var connection = url.openConnection();
        connection.setUseCaches(false);

        var channel = new RecordingChannel();
        try (InputStream in = connection.getInputStream()) {
            write(Body.of(in, data.length), channel);
        }

        assertArrayEquals(data, channel.body());
        // headers + 64 KiB through a 16 KiB buffer: 5 full-buffer writes, not one per 8 KiB read
        assertTrue(channel.writes <= 5, "expected coalesced writes, got " + channel.writes);
    }

    @Test
    void streamThatMayBlockIsFlushedPerRead() throws IOException {
        byte[] data = payload();
        InputStream mayBlock = new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override
            public int available() {
                return 0; // a live producer: the next read could block
            }
        };

        var channel = new RecordingChannel();
        write(Body.of(mayBlock, data.length), channel);

        assertArrayEquals(data, channel.body());
        assertTrue(channel.writes >= BODY_SIZE / 8192, "expected one write per read, got " + channel.writes);
    }

    private static byte[] payload() {
        byte[] data = new byte[BODY_SIZE];
        Arrays.fill(data, (byte) 'x');
        return data;
    }

    private static void write(Body body, RecordingChannel channel) throws IOException {
        var response = Response.builder()
                .header("Content-Type", "application/octet-stream")
                .body(body)
                .build();
        new HttpResponseWriter()
                .write(
                        response,
                        ByteBuffer.allocate(BUFFER_SIZE),
                        channel,
                        true,
                        HttpMethod.GET,
                        Duration.ofSeconds(30));
    }

    /** Counts {@code write} calls and records the bytes. */
    private static final class RecordingChannel implements WritableByteChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes;

        @Override
        public int write(ByteBuffer src) {
            writes++;
            int n = src.remaining();
            byte[] chunk = new byte[n];
            src.get(chunk);
            bytes.writeBytes(chunk);
            return n;
        }

        byte[] body() {
            byte[] all = bytes.toByteArray();
            int start = indexOfHeaderEnd(all) + 4;
            return Arrays.copyOfRange(all, start, all.length);
        }

        private static int indexOfHeaderEnd(byte[] all) {
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') return i;
            }
            throw new AssertionError("no header terminator");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release: the channel only records what is written to it
        }
    }
}
