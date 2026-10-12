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

import java.io.EOFException;
import java.io.IOException;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HPACK hardening over a live connection (CHAPPE-033). The peer's SETTINGS_HEADER_TABLE_SIZE
 * limits the server's encoder, never its decoder, whose dynamic table stays bounded by the 4,096
 * octets the server advertises (RFC 7541 §4.2, RFC 9113 §6.5.2). Malformed HPACK, including a size
 * update out of bounds or out of place and a length the block cannot hold, ends the connection
 * with GOAWAY(COMPRESSION_ERROR) (RFC 9113 §4.3), never with an escaped runtime error.
 */
class Http2HpackHardeningTest {

    /** SETTINGS_HEADER_TABLE_SIZE (RFC 9113 §6.5.2). */
    private static final int HEADER_TABLE_SIZE = 0x1;

    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .handler(Http2HpackHardeningTest::handle)
                .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** {@code /marker} echoes {@code x-marker}; other paths answer pong. */
    private static Response handle(Request request) {
        return switch (request.path()) {
            case "/marker" -> Response.ok(String.valueOf(request.headers().firstOrNull("x-marker")));
            default -> Response.ok("pong");
        };
    }

    /** Reads up to the GOAWAY; an end of stream before it means the server died without one. */
    private static Frame awaitGoaway(H2TestConnection h2) throws IOException {
        while (true) {
            Frame f;
            try {
                f = h2.read();
            } catch (EOFException e) {
                throw new AssertionError("the connection closed without a GOAWAY", e);
            }
            if (f.type() == TYPE_GOAWAY) return f;
        }
    }

    /**
     * Inserts {@code x-marker: m1} into the server's decoder table on stream 1, then refers to it by
     * index on stream 3: the table must hold the entry whatever SETTINGS_HEADER_TABLE_SIZE the client sent.
     */
    private static void insertThenReference(H2TestConnection h2) throws IOException {
        h2.headers(1, true, concat(requestHeaders("GET", "/marker"), literalWithIndexing("x-marker", "m1")));
        h2.flush();
        assertEquals(new Reply("200", "m1"), h2.reply(1));

        h2.headers(3, true, concat(requestHeaders("GET", "/marker"), indexed(62)));
        h2.flush();
        assertEquals(new Reply("200", "m1"), h2.reply(3));
    }

    // --- SETTINGS_HEADER_TABLE_SIZE limits the encoder only ------------------------------------

    @Test
    void aClientAdvertisingAZeroHeaderTableIsServed() throws Exception {
        try (var h2 = H2TestConnection.open(server.port(), setting(HEADER_TABLE_SIZE, 0))) {
            h2.headers(1, true, concat(requestHeaders("GET", "/marker"), literalWithIndexing("x-marker", "m1")));
            h2.flush();
            Frame first = h2.await(TYPE_HEADERS, 1);
            // The encoder honours the client's limit: its first block opens with a size update to 0.
            assertEquals(0x20, first.payload()[0] & 0xFF, "the first response block starts with a size update to 0");
            assertEquals("200", first.headers().get(":status"));
            assertEquals(new Reply(null, "m1"), h2.reply(1));

            h2.headers(3, true, concat(requestHeaders("GET", "/marker"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "m1"), h2.reply(3));

            h2.headers(5, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(5));
        }
    }

    @Test
    void aClientAdvertisingAHeaderTableOf2Pow31IsServed() throws Exception {
        // 2^31 does not fit a signed int: the old code read it as a negative table size.
        try (var h2 = H2TestConnection.open(server.port(), setting(HEADER_TABLE_SIZE, Integer.MIN_VALUE))) {
            insertThenReference(h2);
        }
    }

    @Test
    void aClientAdvertisingTheMaximumHeaderTableIsServed() throws Exception {
        try (var h2 = H2TestConnection.open(server.port(), setting(HEADER_TABLE_SIZE, -1))) { // 2^32 - 1
            insertThenReference(h2);
        }
    }

    // --- Size updates (RFC 7541 §4.2, §6.3) -----------------------------------------------------

    @Test
    void aSizeUpdateWithinTheAdvertisedSizeIsAccepted() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, concat(sizeUpdate(4096), requestHeaders("GET", "/")));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(1));
        }
    }

    @Test
    void aSizeUpdateAboveTheAdvertisedSizeEndsTheConnectionWithCompressionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, concat(sizeUpdate(4097), requestHeaders("GET", "/")));
            h2.flush();
            assertEquals(COMPRESSION_ERROR, awaitGoaway(h2).goawayErrorCode());
        }
    }

    @Test
    void aSizeUpdateInTheMiddleOfABlockEndsTheConnectionWithCompressionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, concat(requestHeaders("GET", "/"), sizeUpdate(0)));
            h2.flush();
            assertEquals(COMPRESSION_ERROR, awaitGoaway(h2).goawayErrorCode());
        }
    }

    // --- Untrusted lengths (RFC 7541 §5.1, §5.2) ------------------------------------------------

    @Test
    void aShortBlockDeclaringATwoGibStringEndsTheConnectionWithCompressionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            // A literal without indexing whose name declares 2^31 - 1 octets: 8 bytes on the wire.
            byte[] block = {0x00, 0x7F, (byte) 0x80, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 'a'};
            h2.headers(1, true, block);
            h2.flush();
            assertEquals(COMPRESSION_ERROR, awaitGoaway(h2).goawayErrorCode());
        }
    }

    @Test
    void aTruncatedHuffmanStringEndsTheConnectionWithCompressionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            // A literal name 'n', then a Huffman value of one octet whose code stops inside a symbol.
            byte[] block = {0x00, 0x01, 'n', (byte) 0x81, 0x00};
            h2.headers(1, true, concat(requestHeaders("GET", "/"), block));
            h2.flush();
            assertEquals(COMPRESSION_ERROR, awaitGoaway(h2).goawayErrorCode());
        }
    }
}
