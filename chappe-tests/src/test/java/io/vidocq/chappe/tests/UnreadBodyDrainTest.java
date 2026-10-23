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
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import io.vidocq.chappe.api.ConnectionUpgrade;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-021: a body the handler left unread is drained for keep-alive within {@code maxDrainSize};
 * past it the connection is closed gracefully instead (the Foy BUG-20261011-11 scenario). The budget
 * counts the body as it is on the wire, chunk framing and trailers included.
 */
class UnreadBodyDrainTest {

    private static final String NEXT_REQUEST = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** A server whose handler never reads the body and answers {@code pong}. */
    private int start(long maxDrainSize) {
        return start(maxDrainSize, _ -> Response.ok("pong"));
    }

    private int start(long maxDrainSize, Handler handler) {
        server = Server.builder()
                .port(0)
                .maxDrainSize(maxDrainSize)
                .maxRequestSize(Long.MAX_VALUE) // isolate the drain budget from the CHAPPE-020 cap
                .handler(handler)
                .build();
        server.start();
        return server.port();
    }

    @Test
    void aBodyLeftUnreadWithinTheBudgetIsDrainedAndTheConnectionKept() throws Exception {
        int port = start(100);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100) + NEXT_REQUEST);

        assertEquals(2, count(response, "pong"), ChunkedBodyLimitTest.head(response));
    }

    @Test
    void aBodyLeftUnreadOneBytePastTheBudgetClosesTheConnection() throws Exception {
        int port = start(100);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 101\r\n\r\n" + "x".repeat(101) + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void aChunkedBodyLeftUnreadWithinTheBudgetIsDrainedAndTheConnectionKept() throws Exception {
        // On the wire: two 1-byte chunks of 6 bytes each, then the 5-byte last chunk: 17 bytes.
        int port = start(17);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + ChunkedBodyLimitTest.chunk(1) + ChunkedBodyLimitTest.chunk(1) + "0\r\n\r\n"
                        + NEXT_REQUEST);

        assertEquals(2, count(response, "pong"), ChunkedBodyLimitTest.head(response));
    }

    @Test
    void chunkFramingCountsAgainstTheBudgetEvenWhenTheDataStaysUnderIt() throws Exception {
        // 100 data bytes against a 1024-byte budget, but each 1-byte chunk carries a 100-byte
        // extension: about 11 KB on the wire. Counting data bytes only would keep the connection.
        int port = start(1024);
        String framed = ("1;" + "e".repeat(100) + "\r\nx\r\n").repeat(100);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + framed + "0\r\n\r\n"
                        + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void chunkedTrailersCountAgainstTheBudget() throws Exception {
        // One data byte, then a 2 KB trailer section against a 1024-byte budget.
        int port = start(1024);
        String trailers = ("X-Pad: " + "t".repeat(100) + "\r\n").repeat(20);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + ChunkedBodyLimitTest.chunk(1) + "0\r\n" + trailers + "\r\n" + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void theUnreadRestOfAPartlyReadChunkCountsAgainstTheBudget() throws Exception {
        // The handler reads one byte of a 4096-byte chunk: the 4095 bytes it left exceed the budget.
        int port = start(1024, req -> {
            req.body().asInputStream().read();
            return Response.ok("pong");
        });
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + ChunkedBodyLimitTest.chunk(4096) + "0\r\n\r\n" + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void anEndlessChunkedUploadIgnoredByTheHandlerIsCutOff() throws Exception {
        int port = start(64 * 1024);
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread sender = Thread.ofVirtual().start(() -> {
                byte[] chunk = ChunkedBodyLimitTest.chunk(4096).getBytes(StandardCharsets.US_ASCII);
                long end = System.nanoTime() + 15_000_000_000L;
                try {
                    while (System.nanoTime() < end) {
                        out.write(chunk);
                        out.flush();
                    }
                } catch (IOException _) {
                    // the server closed the connection: expected
                }
            });
            long start = System.nanoTime();
            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(response.startsWith("HTTP/1.1 200"), ChunkedBodyLimitTest.head(response));
            assertTrue(response.endsWith("pong"), ChunkedBodyLimitTest.head(response));
            assertTrue(elapsedMs < 8_000, "the drain must stop at its budget, took " + elapsedMs + " ms");
            sender.interrupt();
        }
    }

    @Test
    void aConnectionUpgradeWithABodyPastTheBudgetIsRefused() throws Exception {
        var upgraded = new CountDownLatch(1);
        server = Server.builder()
                .port(0)
                .maxDrainSize(16)
                .handler(_ ->
                        new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, Headers.of("Upgrade", "echo"), conn -> {
                            upgraded.countDown();
                            conn.close();
                        }))
                .build();
        server.start();

        String response = sendThenRead(
                server.port(),
                "POST /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\nContent-Length: 1024\r\n\r\n"
                        + "x".repeat(1024));

        assertEquals("", response, "the connection must close without any head");
        assertEquals(1, upgraded.getCount(), "the connection must not be handed over");
    }

    @Test
    void aWebSocketUpgradeWithABodyPastTheBudgetIsRefused() throws Exception {
        var opened = new CountDownLatch(1);
        server = Server.builder()
                .port(0)
                .maxDrainSize(16)
                .handler(_ -> new WebSocketUpgrade(new WebSocketHandler() {
                    @Override
                    public void onOpen(WebSocket ws, Request handshake) {
                        opened.countDown();
                    }
                }))
                .build();
        server.start();

        String response = sendThenRead(
                server.port(),
                "POST /ws HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                        + "Content-Length: 1024\r\n\r\n" + "x".repeat(1024));

        assertEquals("", response, "the connection must close without any head");
        assertEquals(1, opened.getCount(), "the WebSocket must not be opened");
    }

    @Test
    void aZeroBudgetClosesTheConnectionWhenABodyWasLeftUnread() throws Exception {
        int port = start(0);
        String response =
                sendThenRead(port, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx" + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void aZeroBudgetKeepsTheConnectionWhenTheHandlerReadTheWholeBody() throws Exception {
        int port = start(0, req -> {
            req.body().asInputStream().readAllBytes();
            return Response.ok("pong");
        });
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n" + "x".repeat(10) + NEXT_REQUEST);

        assertEquals(2, count(response, "pong"), ChunkedBodyLimitTest.head(response));
    }

    @Test
    void theUnreadRestOfAPartlyReadContentLengthBodyIsDrainedWithinTheBudget() throws Exception {
        // The handler reads 10 of 200 bytes: the 190 it left fit a 190-byte budget.
        int port = start(190, UnreadBodyDrainTest::readTenBytes);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 200\r\n\r\n" + "x".repeat(200) + NEXT_REQUEST);

        assertEquals(2, count(response, "pong"), ChunkedBodyLimitTest.head(response));
    }

    @Test
    void theUnreadRestOfAPartlyReadContentLengthBodyPastTheBudgetClosesTheConnection() throws Exception {
        // The handler reads 10 of 200 bytes: the 190 it left exceed a 189-byte budget.
        int port = start(189, UnreadBodyDrainTest::readTenBytes);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 200\r\n\r\n" + "x".repeat(200) + NEXT_REQUEST);

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void theDrainBudgetDefaultsToOneMebibyteAndRefusesANegativeValue() {
        assertEquals(1024L * 1024, ServerConfig.DEFAULT.maxDrainSize());
        var builder = Server.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.maxDrainSize(-1));
    }

    @Test
    void aServerConfigBuiltDirectlyRefusesANegativeDrainBudget() {
        assertThrows(IllegalArgumentException.class, () -> defaultConfigWithDrainBudget(-1L));
    }

    /** A {@code ServerConfig} built directly: the default one, but for its drain budget. */
    private static ServerConfig defaultConfigWithDrainBudget(long maxDrainSize) {
        var d = ServerConfig.DEFAULT;
        return new ServerConfig(
                d.host(),
                d.port(),
                d.backlog(),
                d.readTimeout(),
                d.writeTimeout(),
                d.idleTimeout(),
                d.maxRequestSize(),
                d.maxHeaderSize(),
                maxDrainSize,
                d.sslContext(),
                d.clientAuth(),
                d.alpnProtocols(),
                d.shutdownGracePeriod());
    }

    private static Response readTenBytes(Request request) throws IOException {
        request.body().asInputStream().readNBytes(10);
        return Response.ok("pong");
    }

    private static String sendThenRead(int port, String request) throws Exception {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return ChunkedBodyLimitTest.readToEof(socket.getInputStream());
        }
    }

    private static int count(String text, String word) {
        return text.split(word, -1).length - 1;
    }
}
