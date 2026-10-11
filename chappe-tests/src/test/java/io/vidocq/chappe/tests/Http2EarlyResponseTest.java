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
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-023: when the response is complete before the request body ended, the server resets the
 * stream with RST_STREAM(NO_ERROR) (RFC 9113 §8.1), and the frames the client had already sent on a
 * stream the server reset or refused are ignored, not a connection error (§5.1). Their header blocks
 * are still decoded, to keep the HPACK context.
 */
class Http2EarlyResponseTest {

    private static final int TYPE_CONTINUATION = 0x9;

    private final CountDownLatch release = new CountDownLatch(1);
    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop();
    }

    /** {@code /block} waits for the test; {@code /echo} answers its {@code x-late} field; others ignore the body. */
    private Response handle(Request request) throws InterruptedException {
        return switch (request.path()) {
            case "/block" -> {
                release.await(10, TimeUnit.SECONDS);
                yield Response.ok("unblocked");
            }
            case "/echo" -> Response.ok("x-late=" + request.headers().firstOrNull("x-late"));
            default -> Response.ok("early");
        };
    }

    /** Stream 1 carries a body the handler never reads; returns the RST_STREAM that follows its response. */
    private static Frame earlyResponse(H2TestConnection h2) throws IOException {
        h2.headers(1, false, requestHeaders("POST", "/upload"));
        h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
        h2.flush();
        assertEquals(new Reply("200", "early"), h2.reply(1));
        return h2.await(TYPE_RST_STREAM, 1);
    }

    @Test
    void anEarlyResponseResetsTheUnreadRequestBodyWithNoError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            assertEquals(NO_ERROR, earlyResponse(h2).errorCode());
        }
    }

    @Test
    void lateDataOnTheResetStreamIsIgnoredButCountsAgainstTheConnectionWindow() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            earlyResponse(h2);
            h2.data(1, false, new byte[16_384]); // already in flight when the reset arrived
            h2.data(1, false, new byte[16_384]);
            h2.data(1, true, new byte[7_232]);
            h2.flush();
            // 3 + 32,768 discarded bytes take the connection window under its threshold: credited back.
            assertTrue(h2.await(TYPE_WINDOW_UPDATE, 0).windowIncrement() >= 32_768);
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(3));
        }
    }

    @Test
    void aLateTrailerBlockOnTheResetStreamStillUpdatesTheHpackContext() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            earlyResponse(h2);
            h2.headers(1, true, literalWithIndexing("x-late", "v1")); // trailers already in flight
            h2.headers(3, true, concat(requestHeaders("GET", "/echo"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "x-late=v1"), h2.reply(3));
        }
    }

    @Test
    void aLateTrailerBlockSplitAcrossContinuationOnTheResetStreamIsDecodedAndDropped() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            earlyResponse(h2);
            byte[] block = literalWithIndexing("x-late", "v2");
            byte[] head = Arrays.copyOfRange(block, 0, 3);
            byte[] tail = Arrays.copyOfRange(block, 3, block.length);
            h2.frame(TYPE_HEADERS, FLAG_END_STREAM, 1, head); // no END_HEADERS: a CONTINUATION follows
            h2.frame(TYPE_CONTINUATION, FLAG_END_HEADERS, 1, tail);
            h2.headers(3, true, concat(requestHeaders("GET", "/echo"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "x-late=v2"), h2.reply(3));
        }
    }

    @Test
    void aRefusedStreamKeepsTheConnectionAndTheHpackContext() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            for (int id = 1; id <= 199; id += 2) h2.headers(id, true, requestHeaders("GET", "/block"));
            // The 101st concurrent stream is refused (MAX_CONCURRENT_STREAMS = 100); its block inserts x-late.
            h2.headers(201, false, concat(requestHeaders("POST", "/echo"), literalWithIndexing("x-late", "v1")));
            h2.data(201, true, "abc".getBytes(StandardCharsets.US_ASCII)); // in flight when the refusal arrives
            h2.flush();
            assertEquals(REFUSED_STREAM, h2.await(TYPE_RST_STREAM, 201).errorCode());

            release.countDown();
            var ended = new HashSet<Integer>();
            while (ended.size() < 100) {
                Frame f = h2.read();
                assertNotEquals(TYPE_GOAWAY, f.type(), "the connection must survive the refused stream");
                boolean streamFrame = f.type() == TYPE_DATA || f.type() == TYPE_HEADERS;
                if (streamFrame && f.endStream() && f.streamId() > 0 && f.streamId() <= 199) ended.add(f.streamId());
            }
            h2.headers(203, true, concat(requestHeaders("GET", "/echo"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "x-late=v1"), h2.reply(203));
        }
    }

    @Test
    void dataOnAStreamClosedNormallyIsAStreamErrorNotAConnectionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(1));
            // Stream 1 is half-closed (remote) or closed, whether or not the server dropped it yet:
            // DATA on it is a stream error STREAM_CLOSED (RFC 9113 §5.1, §6.1).
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(STREAM_CLOSED, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(3));
        }
    }

    @Test
    void dataAfterEndStreamOnAStreamStillServedIsAStreamClosedError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, requestHeaders("GET", "/block")); // half-closed (remote), handler running
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(STREAM_CLOSED, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(3));
        }
    }

    @Test
    void dataOnAStreamNeverOpenedIsStillAConnectionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.data(5, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(PROTOCOL_ERROR, h2.await(TYPE_GOAWAY, 0).goawayErrorCode());
        }
    }
}
