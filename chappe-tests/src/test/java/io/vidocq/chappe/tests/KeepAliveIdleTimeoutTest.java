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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Keep-alive idle timeout ({@code ServerConfig.idleTimeout}).
 *
 * <p>The accept loop used to call {@code socket().setSoTimeout(...)}, which is
 * a silent no-op for blocking {@code SocketChannel} reads — idle keep-alive
 * connections therefore stayed open forever. Real-world impact: abandoned
 * connections / slowloris clients pile up, and the Servlet TCK
 * {@code HttpServletRequest40Tests.TrailerTest} (6.1.0) hangs — its client
 * reads the response until EOF on a keep-alive connection and relies on the
 * container's keep-alive timeout to close it (fixed upstream by sending
 * {@code Connection: close}, jakartaee/servlet commit {@code 079ceb29cb}).</p>
 */
class KeepAliveIdleTimeoutTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private int startWithIdleTimeout(Duration idle) {
        server = Server.builder()
                .port(0)
                .idleTimeout(idle)
                .handler(req -> Response.ok("pong"))
                .build();
        server.start();
        return server.port();
    }

    /** After a served request, an idle keep-alive connection must be closed by the server. */
    @Test
    void idleKeepAliveConnectionIsClosed() throws IOException {
        int port = startWithIdleTimeout(Duration.ofMillis(500));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "GET /ping HTTP/1.1\r\nHost: h\r\n\r\n");
            out.flush();
            String first = readOneResponse(in);
            assertTrue(first.contains("200"), "First response should be 200: " + first);

            // Send nothing more: the server must close within the idle timeout.
            long start = System.nanoTime();
            int eof = in.read(); // would block forever without the timeout
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(-1, eof, "Server should close the idle connection");
            assertTrue(elapsedMs < 4500, "Closed by idle timeout, not by client SO_TIMEOUT (" + elapsedMs + " ms)");
        }
    }

    /** A connection that never sends a request (slowloris connect-only) is closed too. */
    @Test
    void neverSendingClientIsClosed() throws IOException {
        int port = startWithIdleTimeout(Duration.ofMillis(500));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            int eof = socket.getInputStream().read();
            assertEquals(-1, eof, "Server should close a connection that never sends a request");
        }
    }

    /** Activity within the timeout keeps the connection alive across requests. */
    @Test
    void activeKeepAliveSurvives() throws IOException, InterruptedException {
        int port = startWithIdleTimeout(Duration.ofMillis(800));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();
            for (int i = 0; i < 3; i++) {
                Thread.sleep(300); // below the idle timeout
                write(out, "GET /ping HTTP/1.1\r\nHost: h\r\n\r\n");
                out.flush();
                assertTrue(readOneResponse(in).contains("200"), "request " + i + " should succeed");
            }
        }
    }

    private static void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    private static String readOneResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int headerEnd = sb.indexOf("\r\n\r\n");
            if (headerEnd >= 0) {
                String headers = sb.substring(0, headerEnd + 4);
                var clIdx = headers.toLowerCase().indexOf("content-length: ");
                int contentLength = 0;
                if (clIdx >= 0) {
                    var clEnd = headers.indexOf("\r\n", clIdx);
                    contentLength = Integer.parseInt(
                            headers.substring(clIdx + 16, clEnd).trim());
                }
                if (sb.length() - (headerEnd + 4) >= contentLength) return sb.toString();
            }
        }
        return sb.toString();
    }
}
