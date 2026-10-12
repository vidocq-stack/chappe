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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * HPACK decoder hardening (CHAPPE-033). The dynamic table is bounded by the size we advertise, a
 * size update is only valid at the start of a block and within that bound (RFC 7541 §4.2, §6.3),
 * and no length or integer the peer declares is trusted before it is checked against the block
 * (RFC 7541 §5.1, §5.2). Every violation is a COMPRESSION_ERROR (RFC 9113 §4.3), never a runtime
 * exception or a large allocation.
 */
class HpackDecoderHardeningTest {

    /** HPACK index of the most recent dynamic-table entry (61 static entries, RFC 7541 §2.3.3). */
    private static final int NEWEST_DYNAMIC_ENTRY = 62;

    private final HpackDecoder decoder = new HpackDecoder(4096, 8192, 65_536);

    private static void assertCompressionError(HpackDecoder decoder, Block block) {
        var e = assertThrows(Http2ConnectionException.class, () -> decoder.decode(block.buffer(), (n, v) -> {}));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, e.errorCode());
    }

    // --- Dynamic table size updates (RFC 7541 §4.2, §6.3) --------------------------------------

    @Test
    void aSizeUpdateAboveTheAdvertisedMaximumIsACompressionError() {
        assertCompressionError(decoder, new Block().sizeUpdate(4097).literal("a", "b"));
    }

    @Test
    void aSizeUpdateInTheMiddleOfABlockIsACompressionError() {
        assertCompressionError(decoder, new Block().literal("a", "b").sizeUpdate(0));
    }

    @Test
    void sizeUpdatesAtTheStartOfABlockApplyAndEvict() throws Exception {
        assertTrue(decoder.decode(new Block().literalIndexed("x", "1").buffer(), (n, v) -> {}));

        // Two updates in a row at the start are allowed (RFC 7541 §4.2): down to 0, then back up.
        var fields = new ArrayList<String>();
        assertTrue(decoder.decode(
                new Block().sizeUpdate(0).sizeUpdate(4096).literal("a", "b").buffer(),
                (n, v) -> fields.add(n + ":" + v)));
        assertEquals(List.of("a:b"), fields);

        // The update to 0 evicted the entry.
        assertCompressionError(decoder, new Block().indexed(NEWEST_DYNAMIC_ENTRY));
    }

    @Test
    void theDynamicTableStaysBoundedByTheAdvertisedSize() throws Exception {
        // A 4,000-octet entry fits; a second one evicts the first, so the table never holds both.
        String big = "v".repeat(4_000);
        assertTrue(decoder.decode(new Block().literalIndexed("a", big).buffer(), (n, v) -> {}));
        assertTrue(decoder.decode(new Block().literalIndexed("b", big).buffer(), (n, v) -> {}));
        var names = new ArrayList<String>();
        assertTrue(decoder.decode(new Block().indexed(NEWEST_DYNAMIC_ENTRY).buffer(), (n, v) -> names.add(n)));
        assertEquals(List.of("b"), names);
        assertCompressionError(decoder, new Block().indexed(NEWEST_DYNAMIC_ENTRY + 1));
    }

    // --- Untrusted lengths and integers (RFC 7541 §5.1, §5.2) ----------------------------------

    @Test
    void aTenByteBlockDeclaringATwoGibStringIsACompressionErrorWithoutAllocating() {
        var block = new Block().raw(0x00).integer(0x00, 7, Integer.MAX_VALUE).raw('a');
        assertTrue(block.buffer().remaining() <= 10);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertCompressionError(decoder, block));
    }

    @Test
    void aHuffmanStringLongerThanTheBlockIsACompressionErrorWithoutAllocating() {
        // 2^30 Huffman octets: the old decoder allocated length * 2, a negative size.
        var block = new Block().raw(0x00).integer(0x80, 7, 1 << 30).raw(0x1F);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertCompressionError(decoder, block));
    }

    @Test
    void aTruncatedHuffmanStringIsACompressionError() {
        // Declares 10 Huffman octets, carries 3.
        assertCompressionError(
                decoder, new Block().raw(0x00).integer(0x80, 7, 10).raw(0x1F, 0x1F, 0x1F));
    }

    @Test
    void aHuffmanStringCutInsideASymbolIsACompressionError() {
        // 0x00 is the 5-bit code of '0' followed by 3 zero bits: the padding is not an EOS prefix.
        assertCompressionError(
                decoder,
                new Block()
                        .raw(0x00)
                        .integer(0x00, 7, 1)
                        .raw('n')
                        .integer(0x80, 7, 1)
                        .raw(0x00));
    }

    @Test
    void anIntegerPastIntMaxValueIsACompressionError() {
        // A size update of 31 + 0x7F * (1 + 2^7 + ... + 2^28): it overflows an int, and wrapped
        // around it reads as 30, which the old decoder applied.
        assertCompressionError(decoder, new Block().raw(0x3F, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F));
    }

    @Test
    void anIntegerWithTooManyContinuationOctetsIsACompressionError() {
        // A size update of 31 padded with zero-valued continuation octets: the value stays small,
        // but the integer does not end within the 5 octets an int needs.
        assertCompressionError(decoder, new Block().raw(0x3F, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00));
    }

    @Test
    void anIntegerCutBeforeItsLastOctetIsACompressionError() {
        assertCompressionError(decoder, new Block().raw(0xFF, 0x80));
    }

    @Test
    void indexZeroIsACompressionError() {
        assertCompressionError(decoder, new Block().indexed(0));
    }

    @Test
    void aLiteralNameIndexPastTheTablesIsACompressionError() {
        assertCompressionError(
                decoder, new Block().integer(0x00, 4, 200).integer(0x00, 7, 1).raw('v'));
    }

    /** A raw HPACK block builder. */
    private static final class Block {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /** Literal Header Field without Indexing, new name (RFC 7541 §6.2.2). */
        Block literal(String name, String value) {
            out.write(0x00);
            string(name);
            string(value);
            return this;
        }

        /** Literal Header Field with Incremental Indexing, new name (RFC 7541 §6.2.1). */
        Block literalIndexed(String name, String value) {
            out.write(0x40);
            string(name);
            string(value);
            return this;
        }

        /** Indexed Header Field (RFC 7541 §6.1). */
        Block indexed(int index) {
            return integer(0x80, 7, index);
        }

        /** Dynamic Table Size Update (RFC 7541 §6.3). */
        Block sizeUpdate(int size) {
            return integer(0x20, 5, size);
        }

        Block raw(int... octets) {
            for (int o : octets) out.write(o);
            return this;
        }

        ByteBuffer buffer() {
            return ByteBuffer.wrap(out.toByteArray());
        }

        private void string(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            integer(0x00, 7, bytes.length);
            out.writeBytes(bytes);
        }

        /** RFC 7541 §5.1 integer with an N-bit prefix. */
        Block integer(int flags, int prefix, int value) {
            int max = (1 << prefix) - 1;
            if (value < max) {
                out.write(flags | value);
                return this;
            }
            out.write(flags | max);
            value -= max;
            while (value >= 0x80) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
            return this;
        }
    }
}
