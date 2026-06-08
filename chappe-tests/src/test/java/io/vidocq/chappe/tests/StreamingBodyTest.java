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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Body.streaming(InputStream).
 * Verifies HTTP/1.1 chunked transfer and HTTP/2 streaming via PipedInputStream.
 */
class StreamingBodyTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void streamingContentLengthIsMinusOne() throws IOException {
        var pis = new PipedInputStream();
        new PipedOutputStream(pis); // avoids BrokenPipeException if contentLength is called
        assertEquals(-1, Body.streaming(pis).contentLength());
    }

    /**
     * HTTP/1.1 keep-alive → Transfer-Encoding: chunked
     * Verifies the header and the decoded content.
     */
    @Test
    void streamingBodySentAsChunkedHttp11() throws IOException {
        var events = List.of("data: event1\n\n", "data: event2\n\n", "data: event3\n\n");

        server = Server.builder()
                .port(0)
                .handler(_ -> {
                    try {
                        var pis = new PipedInputStream(8192);
                        var pos = new PipedOutputStream(pis);
                        Thread.startVirtualThread(() -> {
                            try (pos) {
                                for (var event : events) {
                                    pos.write(event.getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (IOException _) {
                            }
                        });
                        return Response.builder()
                                .status(StatusCode.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Body.streaming(pis))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        server.start();

        // HTTP/1.1 keep-alive by default -> chunked transfer encoding
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String headers = readHeaders(in);
            assertTrue(
                    headers.contains("Transfer-Encoding: chunked"),
                    "Must use Transfer-Encoding: chunked, received headers:\n" + headers);
            assertTrue(headers.contains("text/event-stream"), "Must have Content-Type: text/event-stream");

            String body = readChunkedBody(in);
            assertEquals(String.join("", events), body, "Decoded body must contain all SSE events");
        }
    }

    /**
     * HTTP/2: the streamed body arrives in successive DATA frames.
     */
    @Test
    void streamingBodyHttp2() throws IOException, InterruptedException {
        var events = List.of("data: e1\n\n", "data: e2\n\n");

        server = Server.builder()
                .port(0)
                .handler(_ -> {
                    try {
                        var pis = new PipedInputStream(8192);
                        var pos = new PipedOutputStream(pis);
                        Thread.startVirtualThread(() -> {
                            try (pos) {
                                for (var event : events) {
                                    pos.write(event.getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (IOException _) {
                            }
                        });
                        return Response.builder()
                                .status(StatusCode.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Body.streaming(pis))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        server.start();

        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/"))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(String.join("", events), response.body());
    }

    // --- Raw HTTP parsing helpers ---

    /** Reads HTTP/1.1 headers until the double CRLF. */
    private String readHeaders(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int len = sb.length();
            if (len >= 4
                    && sb.charAt(len - 4) == '\r'
                    && sb.charAt(len - 3) == '\n'
                    && sb.charAt(len - 2) == '\r'
                    && sb.charAt(len - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    /** Reads and decodes a chunked-transfer body from an InputStream. */
    private String readChunkedBody(InputStream in) throws IOException {
        var result = new StringBuilder();
        while (true) {
            // Read chunk size line (hex)
            var sizeLine = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                sizeLine.append((char) b);
            }
            String sizeStr = sizeLine.toString().trim();
            if (sizeStr.isEmpty()) continue;
            // Ignore chunk extensions (";ext=val")
            int semicolon = sizeStr.indexOf(';');
            if (semicolon >= 0) sizeStr = sizeStr.substring(0, semicolon);
            int chunkSize = Integer.parseInt(sizeStr.trim(), 16);
            if (chunkSize == 0) break;
            // Read exactly chunkSize bytes
            byte[] data = in.readNBytes(chunkSize);
            result.append(new String(data, StandardCharsets.UTF_8));
            // Consume CRLF after data
            in.read(); // \r
            in.read(); // \n
        }
        return result.toString();
    }
}
