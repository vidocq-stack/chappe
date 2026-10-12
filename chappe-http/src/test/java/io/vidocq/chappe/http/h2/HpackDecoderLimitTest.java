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
package io.vidocq.chappe.http.h2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The soft and hard header-list limits of {@link HpackDecoder} (CHAPPE-032): over the soft limit
 * the block is still decoded whole, so the dynamic table stays in sync (RFC 7541 §2.3.2), and
 * only the hard limit ends the connection with ENHANCE_YOUR_CALM. Sizes count octets plus 32 per
 * field (RFC 9113 §6.5.2, RFC 7541 §4.1).
 */
class HpackDecoderLimitTest {

    /** HPACK index of the most recent dynamic-table entry (61 static entries, RFC 7541 §2.3.3). */
    private static final int NEWEST_DYNAMIC_ENTRY = 62;

    @Test
    void aBlockWithinTheSoftLimitReachesTheSinkWhole() throws Exception {
        var decoder = new HpackDecoder(4096, 1000, 2000);
        var block = new Block().literal("a", "b").literal("c", "d");
        var fields = new ArrayList<String>();

        boolean within = decoder.decode(block.buffer(), (n, v) -> fields.add(n + ":" + v));

        assertTrue(within);
        assertEquals(List.of("a:b", "c:d"), fields);
    }

    @Test
    void aBlockOverTheSoftLimitIsDecodedWholeAndKeepsTheDynamicTableInSync() throws Exception {
        var decoder = new HpackDecoder(4096, 100, 10_000);
        String forty = "v".repeat(40);
        // x: 1 + 40 + 32 = 73 (within 100); y: 146 (over); late: 187 (over, but inserted).
        var first = new Block().literal("x", forty).literal("y", forty).literalIndexed("late", "entry");
        var fields = new ArrayList<String>();

        boolean within = decoder.decode(first.buffer(), (n, v) -> fields.add(n + ":" + v));

        assertFalse(within);
        assertEquals(List.of("x:" + forty), fields);

        var second = new Block().indexed(NEWEST_DYNAMIC_ENTRY);
        var secondFields = new ArrayList<String>();
        assertTrue(decoder.decode(second.buffer(), (n, v) -> secondFields.add(n + ":" + v)));
        assertEquals(List.of("late:entry"), secondFields);
    }

    @Test
    void aBlockOverTheHardLimitThrowsEnhanceYourCalm() {
        var decoder = new HpackDecoder(4096, 100, 200);
        String forty = "v".repeat(40);
        var block = new Block().literal("x", forty).literal("y", forty).literal("z", forty);

        var e = assertThrows(Http2ConnectionException.class, () -> decoder.decode(block.buffer(), (n, v) -> {}));

        assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, e.errorCode());
    }

    @Test
    void indexedReferencesThatAmplifyPastTheHardLimitThrow() throws Exception {
        var decoder = new HpackDecoder(8192, 8192, 65_536);
        String big = "x".repeat(4000);
        assertTrue(decoder.decode(new Block().literalIndexed("a", big).buffer(), (n, v) -> {}));

        var flood = new Block();
        for (int i = 0; i < 20; i++) flood.indexed(NEWEST_DYNAMIC_ENTRY); // 20 x 4,033 = 80,660 decoded
        ByteBuffer encoded = flood.buffer();
        assertTrue(encoded.remaining() < 32, "the flood stays tiny on the wire");

        var e = assertThrows(Http2ConnectionException.class, () -> decoder.decode(encoded, (n, v) -> {}));
        assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, e.errorCode());
    }

    @Test
    void theSizeCountsOctetsOfObsTextValuesNotUtf8Bytes() throws Exception {
        byte[] obsText = new byte[10];
        Arrays.fill(obsText, (byte) 0xE9);
        // n: 1 + 10 + 32 = 43 octets; 53 if the value were counted as UTF-8.
        var exact = new HpackDecoder(4096, 43, 43);
        var values = new ArrayList<String>();
        assertTrue(exact.decode(new Block().literal("n", obsText).buffer(), (n, v) -> values.add(v)));
        assertEquals(List.of(new String(obsText, StandardCharsets.ISO_8859_1)), values);

        var oneShort = new HpackDecoder(4096, 42, 1000);
        var none = new ArrayList<String>();
        assertFalse(oneShort.decode(new Block().literal("n", obsText).buffer(), (n, v) -> none.add(v)));
        assertTrue(none.isEmpty());
    }

    @Test
    void everyRepresentationPastTheSoftLimitIsDecodedAndDropped() throws Exception {
        var decoder = new HpackDecoder(4096, 100, 10_000);
        String forty = "v".repeat(40);
        // x: 73 (within 100); then, all past it: a static indexed field (:method GET, index 2), a
        // never-indexed literal, and an insert whose entry a later block must still find.
        var block = new Block()
                .literal("x", forty)
                .literal("y", forty)
                .indexed(2)
                .neverIndexed("secret", "s")
                .literalIndexed("late", "entry");
        var fields = new ArrayList<String>();

        assertFalse(decoder.decode(block.buffer(), (n, v) -> fields.add(n + ":" + v)));
        assertEquals(List.of("x:" + forty), fields);

        var next = new ArrayList<String>();
        assertTrue(decoder.decode(new Block().indexed(NEWEST_DYNAMIC_ENTRY).buffer(), (n, v) -> next.add(n + ":" + v)));
        assertEquals(List.of("late:entry"), next);
    }

    @Test
    void theSizeCountsOctetsOfHuffmanCodedObsTextValues() throws Exception {
        byte[] obsText = new byte[10];
        Arrays.fill(obsText, (byte) 0xE9);
        String value = new String(obsText, StandardCharsets.ISO_8859_1);
        // n: 1 + 10 + 32 = 43 octets, as for the raw string.
        var exact = new HpackDecoder(4096, 43, 43);
        var values = new ArrayList<String>();
        assertTrue(exact.decode(new Block().literalHuffman("n", value).buffer(), (n, v) -> values.add(v)));
        assertEquals(List.of(value), values);

        var oneShort = new HpackDecoder(4096, 42, 1000);
        assertFalse(oneShort.decode(new Block().literalHuffman("n", value).buffer(), (n, v) -> {}));
    }

    @Test
    void theTwoArgumentConstructorKeepsASingleHardLimit() {
        var decoder = new HpackDecoder(4096, 100);
        String forty = "v".repeat(40);
        var block = new Block().literal("x", forty).literal("y", forty);

        var e = assertThrows(Http2ConnectionException.class, () -> decoder.decode(block.buffer(), (n, v) -> {}));

        assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, e.errorCode());
    }

    /** A raw HPACK block builder, without Huffman coding. */
    private static final class Block {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /** Literal Header Field without Indexing, new name (RFC 7541 §6.2.2). */
        Block literal(String name, String value) {
            return literal(name, value.getBytes(StandardCharsets.ISO_8859_1));
        }

        Block literal(String name, byte[] value) {
            out.write(0x00);
            string(name.getBytes(StandardCharsets.ISO_8859_1));
            string(value);
            return this;
        }

        /** Literal Header Field with Incremental Indexing, new name (RFC 7541 §6.2.1). */
        Block literalIndexed(String name, String value) {
            out.write(0x40);
            string(name.getBytes(StandardCharsets.ISO_8859_1));
            string(value.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        /** Literal Header Field Never Indexed, new name (RFC 7541 §6.2.3). */
        Block neverIndexed(String name, String value) {
            out.write(0x10);
            string(name.getBytes(StandardCharsets.ISO_8859_1));
            string(value.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        /** Literal Header Field without Indexing, new name, with a Huffman-coded value (RFC 7541 §5.2). */
        Block literalHuffman(String name, String value) {
            out.write(0x00);
            string(name.getBytes(StandardCharsets.ISO_8859_1));
            byte[] coded = HpackHuffman.encode(value);
            integer(0x80, 7, coded.length);
            out.writeBytes(coded);
            return this;
        }

        /** Indexed Header Field (RFC 7541 §6.1). */
        Block indexed(int index) {
            integer(0x80, 7, index);
            return this;
        }

        ByteBuffer buffer() {
            return ByteBuffer.wrap(out.toByteArray());
        }

        private void string(byte[] bytes) {
            integer(0x00, 7, bytes.length);
            out.writeBytes(bytes);
        }

        /** RFC 7541 §5.1 integer with an N-bit prefix. */
        private void integer(int flags, int prefix, int value) {
            int max = (1 << prefix) - 1;
            if (value < max) {
                out.write(flags | value);
                return;
            }
            out.write(flags | max);
            value -= max;
            while (value >= 0x80) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
    }
}
