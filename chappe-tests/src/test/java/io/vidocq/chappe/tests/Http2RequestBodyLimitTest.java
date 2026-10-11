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

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.http.grpc.GrpcFrameWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-025: {@code maxRequestSize} caps an HTTP/2 request body. Before the response starts, the read
 * fails with RequestBodyTooLargeException and the server answers 413; once the response is on its way,
 * the stream is reset with CANCEL. A call the router sends to a gRPC handler is not capped, whatever
 * the content-type a client sends to a plain handler.
 */
class Http2RequestBodyLimitTest {

    private static final long LIMIT = 1000;
    private static final String GRPC_PATH = "/svc.Upload/Collect";
    private static final int TYPE_PING = 0x6;

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private final ObservedBody liveBody = new ObservedBody();
    private final Router grpcRouter =
            Router.builder().grpc(GRPC_PATH, this::collect).build();
    private final CountDownLatch routeGrpc = new CountDownLatch(1);
    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .maxRequestSize(LIMIT)
                .handler(request -> GRPC_PATH.equals(request.path()) ? routeGrpc(request) : handle(request))
                .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        liveBody.close();
        routeGrpc.countDown();
        if (server != null) server.stop();
    }

    /** {@code /read} reads the body and reports how it ended; {@code /live} answers a live body; else {@code pong}. */
    private Response handle(Request request) {
        return switch (request.path()) {
            case "/read" -> {
                try {
                    byte[] body = request.body().asInputStream().readAllBytes();
                    outcome.complete("read:" + body.length);
                    yield Response.ok("read:" + body.length);
                } catch (IOException e) {
                    outcome.complete(e.getClass().getSimpleName());
                    yield Response.ok("handler answer");
                }
            }
            case "/live" -> Response.builder().body(Body.of(liveBody, -1)).build();
            default -> Response.ok("pong");
        };
    }

    /** Routes a gRPC call once the test lets it: the body can pass the limit before the route is known. */
    private Response routeGrpc(Request request) throws Exception {
        assertTrue(routeGrpc.await(10, TimeUnit.SECONDS), "the gRPC route was never released");
        return grpcRouter.handle(request);
    }

    /** A client-streaming gRPC call: sums the sizes of the messages it receives up to the half-close. */
    private void collect(GrpcCall call) throws IOException {
        int total = 0;
        byte[] message;
        while ((message = call.receive()) != null) total += message.length;
        call.send(("read:" + total).getBytes(StandardCharsets.US_ASCII));
        call.complete(GrpcStatus.OK, "");
    }

    /** A live response body with nothing to send until it is closed; its close is observable. */
    private static final class ObservedBody extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                if (closed.await(20, TimeUnit.SECONDS)) throw new IOException("body closed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void aBodyOverTheLimitIsAnswered413WhenTheResponseHasNotStarted() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[400]); // the third frame passes the limit
            h2.flush();
            assertEquals("RequestBodyTooLargeException", outcome.get(5, TimeUnit.SECONDS));
            assertEquals("413", h2.reply(1).status());
            assertEquals(NO_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.data(1, true, new byte[400]); // still in flight: ignored
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
        }
    }

    @Test
    void aBodyOverTheLimitResetsAStreamWhoseResponseHasStarted() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/live"));
            h2.flush();
            assertEquals("200", h2.await(TYPE_HEADERS, 1).headers().get(":status"));
            h2.data(1, false, new byte[1_500]);
            h2.flush();
            assertEquals(CANCEL, h2.await(TYPE_RST_STREAM, 1).errorCode());
            assertTrue(
                    liveBody.closed.await(5, TimeUnit.SECONDS),
                    "the response body of the reset stream must be released");
        }
    }

    @Test
    void aBodyAtTheLimitIsReadWhole() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            h2.data(1, false, new byte[600]);
            h2.data(1, true, new byte[400]);
            h2.flush();
            assertEquals(new Reply("200", "read:1000"), h2.reply(1));
        }
    }

    @Test
    void aCallRoutedToAGrpcHandlerIsNotCappedByMaxRequestSize() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(
                    1, false, requestHeaders("POST", GRPC_PATH, "content-type", "application/grpc", "te", "trailers"));
            h2.data(1, false, GrpcFrameWriter.encode(new byte[1_000]));
            h2.data(1, true, GrpcFrameWriter.encode(new byte[500]));
            h2.frame(TYPE_PING, 0, 0, new byte[8]);
            h2.flush();
            // Frames are handled in order: once the PING is acknowledged, the server has counted the
            // DATA past the limit, and only then may the router send the call to its gRPC handler.
            h2.await(TYPE_PING, 0);
            routeGrpc.countDown();
            Reply reply = h2.reply(1);
            assertEquals("200", reply.status());
            assertTrue(reply.body().endsWith("read:1500"), reply.body());
        }
    }

    @Test
    void aGrpcContentTypeDoesNotExemptAPlainHandlerFromTheLimit() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read", "content-type", "application/grpc"));
            h2.data(1, false, new byte[600]);
            h2.data(1, true, new byte[600]);
            h2.flush();
            assertEquals("RequestBodyTooLargeException", outcome.get(5, TimeUnit.SECONDS));
            assertEquals("413", h2.reply(1).status());
        }
    }
}
