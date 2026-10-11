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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** CHAPPE-022: {@code readTimeout} bounds each blocking read of an HTTP/1.1 request body. */
class RequestBodyReadTimeoutTest {

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private int start(Duration readTimeout) {
        server = Server.builder()
                .port(0)
                .readTimeout(readTimeout)
                .handler(this::handle)
                .build();
        server.start();
        return server.port();
    }

    /** {@code /read} reads the body and reports how the read ended; anything else ignores the body. */
    private Response handle(Request request) {
        if (!"/read".equals(request.path())) return Response.ok("pong");
        try {
            byte[] body = request.body().asInputStream().readAllBytes();
            outcome.complete("body:" + new String(body, StandardCharsets.US_ASCII));
            return Response.ok("read");
        } catch (IOException e) {
            outcome.complete(e.getClass().getSimpleName());
            return Response.ok("failed");
        }
    }

    @Test
    void aStalledContentLengthBodyFailsTheReadAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            long sent = System.nanoTime();
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");

            assertEquals("SocketTimeoutException", outcome.get(5, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - sent) / 1_000_000;
            assertTrue(elapsedMs >= 250, "the read gave up before the timeout: " + elapsedMs + " ms");
            ChunkedBodyLimitTest.readToEof(socket.getInputStream()); // the server closed the connection
        }
    }

    @Test
    void aStalledChunkedBodyFailsTheReadAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc");

            assertEquals("SocketTimeoutException", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSlowUploadWhoseGapsStayUnderTheReadTimeoutIsReadWhole() throws Exception {
        int port = start(Duration.ofSeconds(1));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n");
            for (char c : "hello".toCharArray()) {
                Thread.sleep(300); // 1.5 s in total, more than the timeout; each gap is less
                send(socket, String.valueOf(c));
            }

            assertEquals("body:hello", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aStalledBodyTheHandlerIgnoredClosesTheConnectionAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");

            // The response comes first, then the drain times out and the connection closes.
            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 200"), ChunkedBodyLimitTest.head(response));
            assertTrue(response.endsWith("pong"), ChunkedBodyLimitTest.head(response));
        }
    }

    @Test
    void aZeroReadTimeoutDisablesTheBound() throws Exception {
        int port = start(Duration.ZERO);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");
            Thread.sleep(500);
            send(socket, "defghij");

            assertEquals("body:abcdefghij", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    /** A timeout too large for a {@code long} of nanoseconds is saturated, not an error. */
    @Test
    void aReadTimeoutOfForeverIsAccepted() throws Exception {
        int port = start(ChronoUnit.FOREVER.getDuration());
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n");
            Thread.sleep(100); // the read waits: the deadline is armed
            send(socket, "hello");

            assertEquals("body:hello", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    /**
     * Full duplex: a body read must not wait behind a response write that is blocked because the
     * client does not read. The producer of a streamed response reads the request body on its own
     * thread while the connection thread is stuck writing; switching the socket's blocking mode would
     * take the write lock and wait for {@code writeTimeout}.
     */
    @Test
    void aBodyReadDoesNotWaitBehindABlockedResponseWrite() throws Exception {
        server = Server.builder()
                .port(0)
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .handler(req -> Response.builder()
                        .body(Body.ofOutputStream(out -> {
                            Thread.ofVirtual().start(() -> {
                                try {
                                    Thread.sleep(1_000); // by then the response write is blocked
                                    byte[] body = req.body().asInputStream().readAllBytes();
                                    outcome.complete("body:" + new String(body, StandardCharsets.US_ASCII));
                                } catch (Exception e) {
                                    outcome.complete(e.getClass().getSimpleName());
                                }
                            });
                            byte[] block = new byte[64 * 1024];
                            try {
                                for (int i = 0; i < 4096; i++) out.write(block); // 256 MiB, never read
                            } catch (IOException _) {
                                // the client closed the connection: expected
                            }
                        }))
                        .build())
                .build();
        server.start();
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5_000);
            send(socket, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n");
            Thread.sleep(200); // the body arrives apart from the head: it is not in the parse buffer
            send(socket, "abcdefghij");

            assertEquals("body:abcdefghij", outcome.get(8, TimeUnit.SECONDS));
        }
    }

    /**
     * A body read that waited, then completed, must not leave a timer behind: the kept connection
     * idles past {@code readTimeout} (it is bounded by {@code idleTimeout} instead) and serves the
     * next request.
     */
    @Test
    void aCompletedBodyReadLeavesNoTimerOnTheKeptConnection() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nab");
            Thread.sleep(100); // the handler now waits for the rest: the read is timed
            send(socket, "cde");
            assertEquals("body:abcde", outcome.get(5, TimeUnit.SECONDS));
            assertTrue(readUntil(socket.getInputStream(), "read").startsWith("HTTP/1.1 200"));

            Thread.sleep(900); // three read timeouts, well under the 60 s idle timeout
            send(socket, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            assertTrue(response.endsWith("pong"), "the kept connection was closed: " + response);
        }
    }

    /**
     * The drain budget still counts chunk framing when the body is read through the read timeout
     * (CHAPPE-021 with CHAPPE-022): 100 data bytes against a 1024-byte budget, about 11 KB on the
     * wire, close the connection instead of being drained.
     */
    @Test
    void chunkFramingStillCountsAgainstTheDrainBudgetWithAReadTimeout() throws Exception {
        server = Server.builder()
                .port(0)
                .readTimeout(Duration.ofSeconds(5))
                .maxDrainSize(1024)
                .maxRequestSize(Long.MAX_VALUE)
                .handler(_ -> Response.ok("pong"))
                .build();
        server.start();
        String framed = ("1;" + "e".repeat(100) + "\r\nx\r\n").repeat(100);
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5_000);
            send(
                    socket,
                    "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + framed + "0\r\n\r\n"
                            + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            assertEquals(1, response.split("pong", -1).length - 1, "the pipelined request must not be read");
        }
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads until the text read so far ends with {@code end}: one response on a kept connection. */
    private static String readUntil(InputStream in, String end) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (!bytes.toString(StandardCharsets.US_ASCII).endsWith(end)) {
            int b = in.read();
            if (b == -1) break;
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}
