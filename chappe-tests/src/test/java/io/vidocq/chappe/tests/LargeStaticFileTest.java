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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.SplittableRandom;

import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression: a client that drains slowly must not receive a truncated
 * response. The zero-copy path {@code FileChannel.transferTo(SocketChannel)}
 * can return 0 when the kernel {@code SO_SNDBUF} is saturated (see JDK-8264762,
 * sendfile(2) on Linux/macOS) — a silent break on that 0 return truncates
 * the response while {@code Content-Length} advertises the full size, which
 * makes the browser hang indefinitely.
 */
class LargeStaticFileTest {

    private static final int FILE_SIZE = 8 * 1024 * 1024; // 8 MiB > typical SO_SNDBUF
    private static final int CLIENT_RCVBUF = 16 * 1024;
    private static final int CHUNK = 4096;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void largeFileIsServedIntactWhenClientDrainsSlowly(@TempDir Path root) throws Exception {
        byte[] payload = randomBytes(FILE_SIZE);
        Path big = root.resolve("big.bin");
        Files.write(big, payload);

        server = Server.builder().port(0).handler(StaticFileHandler.of(root)).build();
        server.start();

        byte[] received = drainSlowly(server.port(), "/big.bin", payload.length);

        assertEquals(payload.length, received.length, "truncated response — likely silent transferTo==0");
        assertEquals(sha256(payload), sha256(received), "file integrity compromised");
        assertArrayEquals(payload, received);
    }

    private static byte[] drainSlowly(int port, String path, long expectedBodyLen) throws IOException {
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(CLIENT_RCVBUF);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(30_000);

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n" + "Host: localhost\r\n" + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            String headers = readHeaders(in);
            assertTrue(headers.startsWith("HTTP/1.1 200"), "expected 200, got headers:\n" + headers);
            long contentLength = parseContentLength(headers);
            assertEquals(expectedBodyLen, contentLength, "Content-Length diverges from file size");

            byte[] body = new byte[(int) contentLength];
            int total = 0;
            byte[] buf = new byte[CHUNK];
            while (total < body.length) {
                int n;
                try {
                    n = in.read(buf, 0, Math.min(CHUNK, body.length - total));
                } catch (SocketException e) {
                    break;
                }
                if (n < 0) break;
                System.arraycopy(buf, 0, body, total, n);
                total += n;
                // Slow drain — saturates server-side SO_SNDBUF and forces
                // sendfile(2) into EAGAIN-like behavior where transferTo may return 0.
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            byte[] truncated = new byte[total];
            System.arraycopy(body, 0, truncated, 0, total);
            return truncated;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        var sb = new StringBuilder(512);
        int prev = -1;
        int crlfRun = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("connection closed while reading headers");
            sb.append((char) c);
            if (c == '\n' && prev == '\r') {
                crlfRun++;
                if (crlfRun == 2) return sb.toString();
            } else if (c != '\r') {
                crlfRun = 0;
            }
            prev = c;
        }
    }

    private static long parseContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                return Long.parseLong(line.substring(colon + 1).trim());
            }
        }
        throw new AssertionError("Content-Length absent");
    }

    private static byte[] randomBytes(int size) {
        var rng = new SplittableRandom(0xC0FFEEL);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) out[i] = (byte) rng.nextInt(256);
        return out;
    }

    private static String sha256(byte[] data) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
