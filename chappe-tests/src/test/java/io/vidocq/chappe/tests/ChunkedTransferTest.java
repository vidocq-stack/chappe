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
        try (var socket = new Socket("127.0.0.1", port)) {
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
        try (var socket = new Socket("127.0.0.1", port)) {
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

    private void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
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
                var clIdx = headers.toLowerCase().indexOf("content-length: ");
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
