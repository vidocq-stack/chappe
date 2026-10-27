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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The idle timeout of a keep-alive connection and the write timeout of a response are bounded by
 * one watchdog per connection, not by a virtual thread started and interrupted per request: the
 * wait for a next request and every flush of a response each started a watchdog thread that
 * slept and was interrupted, two thread starts and two InterruptedExceptions per request, which
 * JFR counted at 40 000 requests per second as 80 000 threads and exceptions per second.
 */
class KeepAliveWatchdogThreadsTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void aKeepAliveConnectionStartsNoThreadPerRequest() throws Exception {
        server = Server.builder()
                .port(0)
                .idleTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .handler(_ -> Response.ok("pong"))
                .build();
        server.start();

        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();
            // The first exchange may start what the connection keeps for its whole life.
            exchange(out, in);

            Path dump = Files.createTempFile("chappe-watchdogs", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("jdk.VirtualThreadStart").withoutThreshold();
                recording.enable("jdk.ThreadStart").withoutThreshold();
                recording.start();
                // Sequential exchanges: the next request is never there before the response is
                // read, so every wait takes the slow path.
                for (int i = 0; i < 50; i++) exchange(out, in);
                recording.stop();
                recording.dump(dump);
            }
            List<String> started = threadsStarted(dump);
            Files.deleteIfExists(dump);
            assertTrue(
                    started.size() <= 2,
                    "threads started for 50 keep-alive requests on one connection: " + started.size() + " " + started);
        }
    }

    private static List<String> threadsStarted(Path dump) throws IOException {
        List<String> names = new ArrayList<>();
        try (RecordingFile file = new RecordingFile(dump)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                if (!type.equals("jdk.VirtualThreadStart") && !type.equals("jdk.ThreadStart")) continue;
                var thread = event.getThread("eventThread");
                if (thread == null) thread = event.getThread("thread");
                String name = thread == null ? "?" : thread.getJavaName();
                // Only the server's threads: the JFR recorder and the test runner start their own.
                if (name != null && name.startsWith("chappe-")) names.add(name);
            }
        }
        return names;
    }

    private static void exchange(OutputStream out, InputStream in) throws IOException {
        out.write("GET /ping HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        readOneResponse(in);
    }

    /** Reads one response with a Content-Length body, as the server writes it. */
    private static void readOneResponse(InputStream in) throws IOException {
        var head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        if (b == -1) throw new IOException("Connection closed before the response head");
        String headers = head.toString().toLowerCase(java.util.Locale.ROOT);
        int idx = headers.indexOf("content-length:");
        int length = idx < 0
                ? 0
                : Integer.parseInt(headers.substring(idx + 15, headers.indexOf("\r\n", idx))
                        .trim());
        in.readNBytes(length);
    }
}
