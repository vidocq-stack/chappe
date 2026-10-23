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
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkedTransferTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok("received:" + body);
                })
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void chunkedRequestBody() throws IOException {
        // Send a request with Transfer-Encoding: chunked over a raw socket
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Request line + headers
            write(out, "POST /echo HTTP/1.1\r\n");
            write(out, "Host: localhost\r\n");
            write(out, "Transfer-Encoding: chunked\r\n");
            write(out, "Connection: close\r\n");
            write(out, "\r\n");

            // Chunk 1: "Hello"
            write(out, "5\r\n");
            write(out, "Hello\r\n");

            // Chunk 2: " World"
            write(out, "6\r\n");
            write(out, " World\r\n");

            // Terminal chunk
            write(out, "0\r\n");
            write(out, "\r\n");
            out.flush();

            // Read response
            var response = readResponse(in);
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(
                    response.contains("received:Hello World"),
                    "Body should contain 'received:Hello World': " + response);
        }
    }

    @Test
    void emptyChunkedBody() throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /echo HTTP/1.1\r\n");
            write(out, "Host: localhost\r\n");
            write(out, "Transfer-Encoding: chunked\r\n");
            write(out, "Connection: close\r\n");
            write(out, "\r\n");

            // Immediate terminal chunk
            write(out, "0\r\n");
            write(out, "\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(response.contains("received:"), "Body should contain 'received:': " + response);
        }
    }

    /**
     * A read returns the chunk that arrived without waiting for the next one: a streaming reader
     * (Foy's non-blocking ReadListener pump) sees each chunk as it comes (CHAPPE-010).
     */
    @Test
    void aReadReturnsTheArrivedChunkWithoutWaitingForTheNext() throws Exception {
        server.stop();
        var firstRead = new java.util.concurrent.CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    var body = req.body().asInputStream();
                    byte[] buf = new byte[1024];
                    int n = body.read(buf, 0, buf.length);
                    firstRead.complete(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    var rest = new String(body.readAllBytes(), StandardCharsets.US_ASCII);
                    return Response.ok("rest:" + rest);
                })
                .build();
        server.start();
        port = server.port();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            write(out, """
                    POST /echo HTTP/1.1\r
                    Host: localhost\r
                    Transfer-Encoding: chunked\r
                    Connection: close\r
                    \r
                    5\r
                    Hello\r
                    """);
            out.flush();
            // The second chunk is sent only once the first read returned.
            assertEquals("Hello", firstRead.get(5, java.util.concurrent.TimeUnit.SECONDS));
            write(out, "5\r\nWorld\r\n0\r\n\r\n");
            out.flush();
            var response = readResponse(socket.getInputStream());
            assertTrue(response.contains("rest:World"), response);
        }
    }

    private void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    /** A connection closed in the middle of a chunk's data is a read failure, not the end of the body (CHAPPE-011). */
    @Test
    void aBodyTruncatedInsideAChunkFailsTheRead() throws Exception {
        server.stop();
        var outcome = new java.util.concurrent.CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    try {
                        outcome.complete("body:"
                                + new String(req.body().asInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                    } catch (IOException _) {
                        outcome.complete("IOException");
                    }
                    return Response.ok("done");
                })
                .build();
        server.start();
        port = server.port();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            var out = socket.getOutputStream();
            write(out, """
                    POST /echo HTTP/1.1\r
                    Host: localhost\r
                    Transfer-Encoding: chunked\r
                    \r
                    5\r
                    Hello\r
                    3\r
                    ab""");
            out.flush();
            socket.shutdownOutput();
            assertEquals("IOException", outcome.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private String readResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
            // If we reached the end of the HTTP body, we can stop
            if (sb.indexOf("\r\n\r\n") > 0) {
                // Check whether the full body has been read (Content-Length based)
                String s = sb.toString();
                int headerEnd = s.indexOf("\r\n\r\n") + 4;
                String headers = s.substring(0, headerEnd);
                // Extract Content-Length
                var clIdx = headers.toLowerCase(Locale.ROOT).indexOf("content-length: ");
                if (clIdx >= 0) {
                    var clEnd = headers.indexOf("\r\n", clIdx);
                    var clValue = headers.substring(clIdx + 16, clEnd).trim();
                    int contentLength = Integer.parseInt(clValue);
                    int bodyReceived = s.length() - headerEnd;
                    if (bodyReceived >= contentLength) break;
                } else {
                    // No Content-Length, read a bit more and stop
                    try {
                        if (in.available() == 0) break;
                    } catch (IOException _) {
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }
}
