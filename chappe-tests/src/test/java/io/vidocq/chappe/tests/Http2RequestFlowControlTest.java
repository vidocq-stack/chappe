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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.http.grpc.GrpcFrameWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-024: the HTTP/2 stream window reopens as the handler reads the body, so a stream buffers at
 * most its window; a client overrunning it gets a stream FLOW_CONTROL_ERROR; readTimeout bounds the
 * wait for the next DATA frame, except on a call routed to a gRPC handler ({@code Router.grpc}),
 * whatever the content-type a client sends to a plain handler. The padded upload is guarded by {@link
 * Http2PaddedDataFlowControlTest} (CHAPPE-023).
 */
class Http2RequestFlowControlTest {

    private static final String GRPC_PATH = "/svc.Upload/Collect";

    private final CountDownLatch release = new CountDownLatch(1);
    private final Router grpcRouter =
            Router.builder().grpc(GRPC_PATH, this::collect).build();
    private Server server;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop();
    }

    private void start(Duration readTimeout) {
        server = Server.builder()
                .port(0)
                .readTimeout(readTimeout)
                .handler(request -> GRPC_PATH.equals(request.path()) ? grpcRouter.handle(request) : handle(request))
                .build();
        server.start();
    }

    /**
     * {@code /wait-then-read} reads 49,152 bytes once the test releases it; {@code /block} never reads;
     * {@code /read} reads the whole body; a failed read answers {@code failed:<exception>}.
     */
    private Response handle(Request request) throws InterruptedException {
        try {
            return switch (request.path()) {
                case "/wait-then-read" -> {
                    release.await(10, TimeUnit.SECONDS);
                    yield Response.ok("read:" + request.body().asInputStream().readNBytes(49_152).length);
                }
                case "/block" -> {
                    release.await(10, TimeUnit.SECONDS);
                    yield Response.ok("released");
                }
                case "/read" ->
                    Response.ok("read:" + request.body().asInputStream().readAllBytes().length);
                default -> Response.ok("pong");
            };
        } catch (IOException e) {
            return Response.ok("failed:" + e.getClass().getSimpleName());
        }
    }

    /** A client-streaming gRPC call: sums the sizes of the messages it receives up to the half-close. */
    private void collect(GrpcCall call) throws IOException {
        int total = 0;
        byte[] message;
        while ((message = call.receive()) != null) total += message.length;
        call.send(("read:" + total).getBytes(StandardCharsets.US_ASCII));
        call.complete(GrpcStatus.OK, "");
    }

    @Test
    void theStreamWindowReopensOnlyAsTheHandlerReadsTheBody() throws Exception {
        start(Duration.ofSeconds(30));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/wait-then-read"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[16_384]);
            h2.flush();
            // The handler has read nothing yet: no WINDOW_UPDATE may reopen stream 1.
            long end = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < end) {
                Frame f = h2.poll(100);
                if (f != null) {
                    assertFalse(
                            f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 1, "stream 1 credited before any read");
                }
            }
            release.countDown();
            assertTrue(h2.await(TYPE_WINDOW_UPDATE, 1).windowIncrement() >= 32_767);
            assertEquals(new Reply("200", "read:49152"), h2.reply(1));
        }
    }

    @Test
    void aClientOverrunningTheStreamWindowGetsAStreamFlowControlError() throws Exception {
        start(Duration.ofSeconds(30));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/block"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[16_384]);
            h2.data(1, false, new byte[16_383]); // the whole 65,535-byte stream window
            h2.flush();
            // The connection window is credited on receipt: the last byte overruns only the stream window.
            h2.await(TYPE_WINDOW_UPDATE, 0);
            h2.data(1, false, new byte[1]); // one byte past the stream window
            h2.flush();
            assertEquals(FLOW_CONTROL_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
        }
    }

    @Test
    void aStalledHttp2BodyFailsTheReadAfterTheReadTimeout() throws Exception {
        start(Duration.ofMillis(300));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush(); // and nothing more: the body stalls
            assertEquals(new Reply("200", "failed:SocketTimeoutException"), h2.reply(1));
        }
    }

    @Test
    void anIdleCallRoutedToAGrpcHandlerIsExemptFromTheReadTimeout() throws Exception {
        start(Duration.ofMillis(300));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(
                    1, false, requestHeaders("POST", GRPC_PATH, "content-type", "application/grpc", "te", "trailers"));
            h2.data(1, false, GrpcFrameWriter.encode("abc".getBytes(StandardCharsets.US_ASCII)));
            h2.flush();
            // A client-streaming call pauses three times longer than readTimeout: a timed-out read
            // would end the call within this wait.
            assertNull(h2.poll(900), "the idle gRPC call was answered before its half-close");
            h2.data(1, true, GrpcFrameWriter.encode("def".getBytes(StandardCharsets.US_ASCII)));
            h2.flush();
            Reply reply = h2.reply(1);
            assertEquals("200", reply.status());
            assertTrue(reply.body().endsWith("read:6"), reply.body());
        }
    }

    @Test
    void aGrpcContentTypeDoesNotExemptAPlainHandlerFromTheReadTimeout() throws Exception {
        start(Duration.ofMillis(300));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read", "content-type", "application/grpc+proto"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush(); // and nothing more: the body stalls
            assertEquals(new Reply("200", "failed:SocketTimeoutException"), h2.reply(1));
        }
    }
}
