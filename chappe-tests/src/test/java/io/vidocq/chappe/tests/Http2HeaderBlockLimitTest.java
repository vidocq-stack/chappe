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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/2 header-list limits (CHAPPE-024, CHAPPE-032). chappe advertises
 * SETTINGS_MAX_HEADER_LIST_SIZE = maxHeaderSize + 32 * 100 (11,392 by default). A header list past
 * it, but still decodable, gets 431 on its own stream, and the connection lives on with its HPACK
 * context intact. Past the hard caps (64 KiB of encoded block, 256 KiB of decoded list by default)
 * the peer is abusive: a CONTINUATION flood (the CVE-2024-27316 class) or an HPACK amplification
 * gets GOAWAY(ENHANCE_YOUR_CALM), on a served stream as on a stream the server already reset.
 */
class Http2HeaderBlockLimitTest {

    /** The advertised SETTINGS_MAX_HEADER_LIST_SIZE with the default maxHeaderSize: 8,192 + 32 * 100. */
    private static final int MAX_HEADER_LIST_SIZE = 11_392;

    /** The encoded header block cap with the default maxHeaderSize: max(4 * 11,392, 64 KiB). */
    private static final int HEADER_BLOCK_CAP = 65_536;

    private static final int FRAGMENT = 1_024;

    /** The frame size chappe accepts, its default SETTINGS_MAX_FRAME_SIZE. */
    private static final int MAX_FRAME_SIZE = 16_384;

    /** A header value of 12 KiB: the list is past {@link #MAX_HEADER_LIST_SIZE}, its block under the cap. */
    private static final String BIG_VALUE = "a".repeat(12 * 1_024);

    private final AtomicInteger calls = new AtomicInteger();

    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * {@code /upload} answers without reading its body (the stream is then reset); {@code /read}
     * reads its body first; {@code /marker} echoes {@code x-marker}; others answer pong.
     */
    private Response handle(Request request) throws IOException {
        calls.incrementAndGet();
        return switch (request.path()) {
            case "/upload" -> Response.ok("early");
            case "/read" -> {
                request.body().asInputStream().readAllBytes();
                yield Response.ok("read");
            }
            case "/marker" -> Response.ok(String.valueOf(request.headers().firstOrNull("x-marker")));
            default -> Response.ok("pong");
        };
    }

    /**
     * Sends {@code first} as a HEADERS frame without END_HEADERS, then 1 KiB CONTINUATION frames that
     * never end the block, up to the one that takes it past the cap and no further, then reads up to
     * the GOAWAY. The server has then read every byte sent, so its close cannot turn into a TCP reset
     * that would swallow the GOAWAY. Without a bound the server would wait for END_HEADERS while
     * buffering every fragment.
     */
    private static Frame flood(H2TestConnection h2, int streamId, byte[] first) throws IOException {
        h2.frame(TYPE_HEADERS, FLAG_END_STREAM, streamId, first); // no END_HEADERS
        int frames = (HEADER_BLOCK_CAP - first.length) / FRAGMENT + 1; // the last one passes the cap
        for (int i = 0; i < frames; i++) h2.frame(TYPE_CONTINUATION, 0, streamId, new byte[FRAGMENT]);
        h2.flush();
        return awaitGoaway(h2);
    }

    private static Frame awaitGoaway(H2TestConnection h2) throws IOException {
        while (true) {
            Frame f = h2.read(); // a SocketTimeoutException here means the server kept going
            if (f.type() == TYPE_GOAWAY) return f;
        }
    }

    /** Sends {@code block} as one HEADERS frame and as many CONTINUATION frames as the frame size needs. */
    private static void headerBlock(H2TestConnection h2, int streamId, boolean endStream, byte[] block)
            throws IOException {
        int end = Math.min(block.length, MAX_FRAME_SIZE);
        boolean last = end == block.length;
        int flags = (endStream ? FLAG_END_STREAM : 0) | (last ? FLAG_END_HEADERS : 0);
        h2.frame(TYPE_HEADERS, flags, streamId, Arrays.copyOfRange(block, 0, end));
        while (!last) {
            int start = end;
            end = Math.min(block.length, start + MAX_FRAME_SIZE);
            last = end == block.length;
            h2.frame(TYPE_CONTINUATION, last ? FLAG_END_HEADERS : 0, streamId, Arrays.copyOfRange(block, start, end));
        }
    }

    /** The SETTINGS_MAX_HEADER_LIST_SIZE in the first SETTINGS frame the server sends, or -1. */
    private static int advertisedMaxHeaderListSize(int port) throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        try (var h2 = H2TestConnection.over(socket)) {
            h2.preface();
            h2.frame(TYPE_SETTINGS, 0, 0, new byte[0]);
            h2.flush();
            Frame settings;
            do {
                settings = h2.read();
            } while (settings.type() != TYPE_SETTINGS || (settings.flags() & FLAG_ACK) != 0);
            var payload = ByteBuffer.wrap(settings.payload());
            int advertised = -1;
            while (payload.remaining() >= 6) {
                int id = payload.getShort() & 0xFFFF;
                int value = payload.getInt();
                if (id == 0x6) advertised = value; // SETTINGS_MAX_HEADER_LIST_SIZE
            }
            return advertised;
        }
    }

    // --- Limits ---------------------------------------------------------------------------------

    @Test
    void theAdvertisedLimitDerivesFromMaxHeaderSize() throws Exception {
        assertEquals(MAX_HEADER_LIST_SIZE, advertisedMaxHeaderListSize(server.port()));

        var raised = Server.builder()
                .port(0)
                .maxHeaderSize(32_768)
                .handler(this::handle)
                .build();
        raised.start();
        try {
            assertEquals(32_768 + 3_200, advertisedMaxHeaderListSize(raised.port()));
        } finally {
            raised.stop();
        }
    }

    @Test
    void aHeaderListOverTheLimitGets431OnItsStreamOnly() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, requestHeaders("GET", "/", "x-big", BIG_VALUE));
            h2.flush();
            assertEquals(new Reply("431", ""), h2.reply(1));

            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
            assertEquals(1, calls.get(), "the handler must not see the oversized request");
        }
    }

    @Test
    void theHpackContextSurvivesAnOversizedBlock() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            // The oversized block ends with an entry that enters the dynamic table.
            byte[] block =
                    concat(requestHeaders("GET", "/marker", "x-big", BIG_VALUE), literalWithIndexing("x-marker", "m1"));
            h2.headers(1, true, block);
            h2.flush();
            assertEquals("431", h2.reply(1).status());

            h2.headers(3, true, concat(requestHeaders("GET", "/marker"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "m1"), h2.reply(3));
        }
    }

    @Test
    void aRaisedMaxHeaderSizeLetsALargeHeaderListThrough() throws Exception {
        var raised = Server.builder()
                .port(0)
                .maxHeaderSize(32_768)
                .handler(this::handle)
                .build();
        raised.start();
        try (var h2 = H2TestConnection.open(raised.port())) {
            headerBlock(h2, 1, true, requestHeaders("GET", "/", "x-big", "a".repeat(20 * 1_024)));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(1));
        } finally {
            raised.stop();
        }
    }

    @Test
    void anOversizedRequestWithABodyIsResetAfterThe431() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read", "x-big", BIG_VALUE));
            h2.flush();
            assertEquals(new Reply("431", ""), h2.reply(1));
            assertEquals(NO_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());

            // DATA the client sent before it saw the reset: ignored, the connection lives on.
            h2.data(1, true, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
            assertEquals(1, calls.get(), "the handler must not see the oversized request");
        }
    }

    // --- Abuse ----------------------------------------------------------------------------------

    @Test
    void aContinuationFloodStillEndsTheConnection() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            byte[] first = requestHeaders("GET", "/");
            h2.frame(TYPE_HEADERS, FLAG_END_STREAM, 1, first); // no END_HEADERS
            // Up to the cap exactly: far past the advertised limit, still accumulated.
            int left = HEADER_BLOCK_CAP - first.length;
            while (left > 0) {
                int size = Math.min(FRAGMENT, left);
                h2.frame(TYPE_CONTINUATION, 0, 1, new byte[size]);
                left -= size;
            }
            h2.flush();
            assertNull(h2.poll(300), "a block at the cap is still accumulated");

            h2.frame(TYPE_CONTINUATION, 0, 1, new byte[1]); // one byte past the cap
            h2.flush();
            assertEquals(ENHANCE_YOUR_CALM, awaitGoaway(h2).goawayErrorCode());
        }
    }

    @Test
    void aContinuationFloodOnANewStreamEndsTheConnectionWithEnhanceYourCalm() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            assertEquals(
                    ENHANCE_YOUR_CALM, flood(h2, 1, requestHeaders("GET", "/")).goawayErrorCode());
        }
    }

    @Test
    void aContinuationFloodOnAResetStreamEndsTheConnectionWithEnhanceYourCalm() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/upload"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(1));
            assertEquals(NO_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());
            // Trailers on the reset stream: decoded and dropped, so accumulated all the same.
            assertEquals(
                    ENHANCE_YOUR_CALM,
                    flood(h2, 1, literalWithIndexing("x-late", "v1")).goawayErrorCode());
        }
    }

    @Test
    void hpackAmplificationEndsTheConnection() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            // One entry of 4,037 octets (5 + 4,000 + 32), then 70 references to it: about 287 KiB
            // decoded from a block of about 4 KiB, past the 256 KiB decoded cap.
            byte[] references = new byte[70];
            Arrays.fill(references, indexed(62)[0]);
            byte[] block =
                    concat(requestHeaders("GET", "/"), literalWithIndexing("x-big", "a".repeat(4_000)), references);
            h2.headers(1, true, block);
            h2.flush();
            assertEquals(ENHANCE_YOUR_CALM, awaitGoaway(h2).goawayErrorCode());
            assertEquals(0, calls.get());
        }
    }

    // --- Trailers and not-served streams --------------------------------------------------------

    @Test
    void oversizedTrailersResetOnlyTheirStream() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.headers(1, true, literalWithoutIndexing("x-big", BIG_VALUE)); // trailers
            h2.flush();
            assertEquals(ENHANCE_YOUR_CALM, h2.await(TYPE_RST_STREAM, 1).errorCode());

            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
        }
    }

    @Test
    void lateOversizedHeadersOnAResetStreamAreIgnored() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/upload"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(1));
            assertEquals(NO_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());

            // Trailers past the advertised limit, under the cap, on the reset stream: decoded and dropped.
            h2.headers(1, true, literalWithoutIndexing("x-big", BIG_VALUE));
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            while (true) {
                Frame f = h2.read();
                assertNotEquals(TYPE_GOAWAY, f.type(), "the connection must survive the late block");
                assertFalse(f.streamId() == 1 && f.type() == TYPE_HEADERS, "no response on the reset stream");
                if (f.streamId() == 3 && f.type() == TYPE_HEADERS) {
                    assertEquals("200", f.headers().get(":status"));
                    break;
                }
            }
        }
    }

    @Test
    void aHeaderBlockSplitAcrossContinuationFramesIsStillServed() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            byte[] block = requestHeaders("GET", "/");
            int third = block.length / 3;
            h2.frame(TYPE_HEADERS, FLAG_END_STREAM, 1, Arrays.copyOfRange(block, 0, third));
            h2.frame(TYPE_CONTINUATION, 0, 1, Arrays.copyOfRange(block, third, 2 * third));
            h2.frame(TYPE_CONTINUATION, FLAG_END_HEADERS, 1, Arrays.copyOfRange(block, 2 * third, block.length));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(1));
        }
    }
}
