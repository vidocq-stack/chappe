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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lingering close (RFC 9112 §9.6): when the server ends an HTTP/1.1 connection
 * while request bytes it never read are still waiting in its receive buffer, a
 * plain {@code close()} makes the TCP stack answer with a reset, and the reset
 * can destroy the response the client has not read yet. The server must close
 * its write side first, drain the input for a bounded time, then close.
 */
class LingeringCloseTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .maxHeaderSize(8192)
                .handler(LingeringCloseTest::handle)
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** {@code /read} reads the request body and answers even when that read fails; else {@code pong}. */
    static Response handle(Request request) {
        if ("/read".equals(request.path())) {
            try {
                request.body().asInputStream().readAllBytes();
                return Response.ok("read ok");
            } catch (IOException e) {
                return Response.ok("read failed");
            }
        }
        return Response.ok("pong");
    }

    /** A request whose chunked body fails on its first byte, followed by 64 KiB the server never reads. */
    static String failingChunkedUpload(String path) {
        return "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + "Z0\r\n\r\n"
                + "Z".repeat(64 * 1024);
    }

    /**
     * CHAPPE-014: a body read that fails closes the connection (no next request, CHAPPE-013), but
     * gracefully: the response written after the failure must not be destroyed by a reset.
     */
    @Test
    void responseSurvivesAFailedBodyReadWithUnreadUploadBytes() throws Exception {
        String response = sendThenRead(failingChunkedUpload("/read"));

        assertTrue(response.startsWith("HTTP/1.1 200"), "expected the 200 response, got: " + head(response));
        assertTrue(response.endsWith("read failed"), "expected the whole body, got: " + head(response));
    }

    /** Same when the handler ignores the body and the connection's own drain of it fails. */
    @Test
    void responseSurvivesAFailedBodyDrainWithUnreadUploadBytes() throws Exception {
        String response = sendThenRead(failingChunkedUpload("/"));

        assertTrue(response.startsWith("HTTP/1.1 200"), "expected the 200 response, got: " + head(response));
        assertTrue(response.endsWith("pong"), "expected the whole body, got: " + head(response));
    }

    /**
     * An uploader that keeps sending after its body read failed: the server answers, half-closes,
     * drains what keeps coming, and the client reads the whole response then a clean end of stream.
     * Never a second request parsed from the upload (it holds one).
     */
    @Test
    void anUploaderStillSendingAfterAFailedBodyReadGetsTheResponseAndAnOrderlyClose() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\nZ0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var sender = Thread.ofVirtual().start(() -> {
                byte[] chunk = ("GET /smuggled HTTP/1.1\r\nHost: localhost\r\n\r\n" + "Z".repeat(4096))
                        .getBytes(StandardCharsets.US_ASCII);
                long end = System.nanoTime() + 500_000_000L;
                try {
                    while (System.nanoTime() < end) {
                        out.write(chunk);
                        out.flush();
                        Thread.sleep(5);
                    }
                    socket.shutdownOutput();
                } catch (IOException | InterruptedException _) {
                    // the main thread reports what the client saw
                }
            });
            String response = readToEof(socket.getInputStream());
            sender.join();

            assertTrue(response.startsWith("HTTP/1.1 200"), "expected the 200 response, got: " + head(response));
            assertTrue(response.endsWith("read failed"), "expected one whole response, got: " + head(response));
        }
    }

    /** The 431 must reach a client that sent more than the server reads before rejecting. */
    @Test
    void headerTooLargeResponseSurvivesUnreadRequestBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "X-Oversized: " + "X".repeat(16_384) + "\r\n"
                + "X-More: " + "Y".repeat(64 * 1024) + "\r\n"
                + "\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 431"), "expected the 431 response, got: " + head(response));
    }

    /** Same for a 400 sent after the headers were parsed, with a pipelined request left unread. */
    @Test
    void badRequestResponseSurvivesPipelinedBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\n\r\n" // no Host: 400
                + "GET / HTTP/1.1\r\nHost: localhost\r\nX-Pad: " + "Z".repeat(64 * 1024) + "\r\n\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 400"), "expected the 400 response, got: " + head(response));
    }

    /** A Connection: close response must reach a client that pipelined another request behind it. */
    @Test
    void connectionCloseResponseSurvivesPipelinedBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\nX-Pad: " + "Z".repeat(64 * 1024) + "\r\n\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 200"), "expected the 200 response, got: " + head(response));
        assertTrue(response.endsWith("pong"), "expected the whole body, got: " + head(response));
    }

    /** Lingering is bounded: a client that never stops sending is cut off, not served forever. */
    @Test
    void lingeringIsBoundedForAClientThatKeepsSending() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: localhost\r\nX-Oversized: " + "X".repeat(16_384) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            long start = System.nanoTime();
            byte[] chunk = "X".repeat(1024).getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class,
                    () -> {
                        while (System.nanoTime() - start < 15_000_000_000L) {
                            out.write(chunk);
                            out.flush();
                            Thread.sleep(20);
                        }
                    },
                    "the server must eventually close a connection that keeps sending after an error");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 8_000, "lingering must be bounded, took " + elapsedMs + " ms");
        }
    }

    /**
     * Writes the whole request at once, gives the server time to answer and close,
     * then reads to EOF. A reset while reading is a failure: the response was lost.
     */
    private String sendThenRead(String request) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(300); // the server answers and closes before the client reads
            return readToEof(socket.getInputStream());
        }
    }

    private static String readToEof(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static String head(String response) {
        return response.length() <= 200 ? response : response.substring(0, 200) + "…";
    }
}
