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

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-024: a header block (HEADERS plus its CONTINUATION frames) is accumulated up to the
 * SETTINGS_MAX_HEADER_LIST_SIZE chappe advertises (8,192 bytes), never further. A peer that keeps
 * sending CONTINUATION frames (the CVE-2024-27316 class) gets GOAWAY(ENHANCE_YOUR_CALM) once the
 * block passes it, on a served stream as on a stream the server already reset.
 */
class Http2HeaderBlockLimitTest {

    /** The max header list size chappe advertises, which bounds a header block. */
    private static final int MAX_HEADER_LIST_SIZE = 8_192;

    private static final int FRAGMENT = 1_024;

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

    /** {@code /upload} answers without reading its body (the stream is then reset); others answer pong. */
    private Response handle(Request request) {
        return Response.ok(request.path().equals("/upload") ? "early" : "pong");
    }

    /**
     * Sends {@code first} as a HEADERS frame without END_HEADERS, then 1 KiB CONTINUATION frames that
     * never end the block, up to the one that takes it past the limit and no further, then reads up to
     * the GOAWAY. The server has then read every byte sent, so its close cannot turn into a TCP reset
     * that would swallow the GOAWAY. Without a bound the server would wait for END_HEADERS while
     * buffering every fragment.
     */
    private static Frame flood(H2TestConnection h2, int streamId, byte[] first) throws IOException {
        h2.frame(TYPE_HEADERS, FLAG_END_STREAM, streamId, first); // no END_HEADERS
        int frames = (MAX_HEADER_LIST_SIZE - first.length) / FRAGMENT + 1; // the last one passes the limit
        for (int i = 0; i < frames; i++) h2.frame(TYPE_CONTINUATION, 0, streamId, new byte[FRAGMENT]);
        h2.flush();
        while (true) {
            Frame f = h2.read(); // a SocketTimeoutException here means the server kept accumulating
            if (f.type() == TYPE_GOAWAY) return f;
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
