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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.RequestBodyTooLargeException;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-020: a chunked request body is capped at {@code maxRequestSize}, as a Content-Length one
 * is. The chunk that would take the body past the cap fails the read before its data is read, and
 * the connection is closed after the response: no other request is read from it.
 */
class ChunkedBodyLimitTest {

    private static final long LIMIT = 1024;

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .maxRequestSize(LIMIT)
                .handler(this::handle)
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** {@code /read} reads the whole body and reports how the read ended; anything else answers {@code pong}. */
    private Response handle(Request request) {
        if (!"/read".equals(request.path())) return Response.ok("pong");
        try {
            byte[] body = request.body().asInputStream().readAllBytes();
            outcome.complete("read:" + body.length);
            return Response.ok("read:" + body.length);
        } catch (RequestBodyTooLargeException e) {
            outcome.complete("too large:" + e.limit());
            return Response.ok("too large:" + e.limit());
        } catch (IOException e) {
            outcome.complete("failed:" + e.getClass().getSimpleName());
            return Response.ok("failed");
        }
    }

    @Test
    void aChunkedBodyOverTheLimitFailsTheReadAndClosesTheConnection() throws Exception {
        String request = "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + chunk(400) + chunk(400) + chunk(400) + "0\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

        String response = sendThenRead(request);

        assertEquals("too large:1024", outcome.get(5, TimeUnit.SECONDS));
        assertTrue(response.startsWith("HTTP/1.1 200"), head(response));
        assertTrue(response.endsWith("too large:1024"), "one response, then the close: " + head(response));
        assertFalse(response.contains("pong"), "no request may be read after an over-limit body");
    }

    @Test
    void aChunkSizeOverTheLimitFailsBeforeItsDataArrives() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n100000\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // No chunk data is ever sent: the read must fail on the size line alone.
            assertEquals("too large:1024", outcome.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void aChunkedBodyAtTheLimitIsReadWholeAndKeepsTheConnection() throws Exception {
        String request = "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + chunk(1000) + chunk(24) + "0\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

        String response = sendThenRead(request);

        assertEquals("read:1024", outcome.get(5, TimeUnit.SECONDS));
        assertTrue(response.contains("read:1024"), head(response));
        assertTrue(response.endsWith("pong"), "the keep-alive request must be answered: " + head(response));
    }

    /** RFC 9110 §15.5.14: a Content-Length above the cap is answered 413, without running the handler. */
    @Test
    void aContentLengthOverTheLimitIsAnswered413BeforeTheHandlerRuns() throws Exception {
        String response = sendThenRead(
                "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1025\r\n\r\n" + "x".repeat(1025));

        assertTrue(response.startsWith("HTTP/1.1 413"), head(response));
        assertFalse(outcome.isDone(), "the handler must not run");
    }

    /**
     * RFC 9110 §10.1.1: a server that refuses the body answers the final status instead of
     * {@code 100 Continue}, so the client does not send a body that is refused anyway.
     */
    @Test
    void anExpectContinueWithAContentLengthOverTheLimitGets413Without100Continue() throws Exception {
        String response = sendThenRead(
                "POST /read HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 1025\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 413"), head(response));
        assertFalse(outcome.isDone(), "the handler must not run");
    }

    /** One chunk of {@code size} bytes, framed (RFC 9112 §7.1). */
    static String chunk(int size) {
        return Integer.toHexString(size) + "\r\n" + "x".repeat(size) + "\r\n";
    }

    private String sendThenRead(String request) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readToEof(socket.getInputStream());
        }
    }

    /**
     * Reads to the end of the connection; a reset ends it too, the caller judges what arrived.
     * Swallowing the reset is intentional here: these tests assert on the bytes received. The
     * CHAPPE-014 tests ({@code LingeringCloseTest}, {@code TlsLingeringCloseTest}) keep their own
     * helper, which fails on a reset because a reset is precisely what they guard against.
     */
    static String readToEof(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        } catch (SocketException reset) {
            // a reset ends the connection too
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    static String head(String response) {
        return response.length() <= 200 ? response : response.substring(0, 200) + "…";
    }
}
