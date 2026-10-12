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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HPACK decoder (RFC 7541) — decodes a compressed header block
 * and emits name/value pairs into a {@link HeaderSink}.
 */
public final class HpackDecoder {

    /** Sink for decoded name/value pairs — may reject via a protocol exception. */
    @FunctionalInterface
    public interface HeaderSink {
        void accept(String name, String value) throws Http2ConnectionException;
    }

    /**
     * The most continuation octets an integer may take after its prefix: 5 groups of 7 bits cover
     * any value up to {@link Integer#MAX_VALUE} (RFC 7541 §5.1).
     */
    private static final int MAX_INTEGER_CONTINUATION_OCTETS = 5;

    private final HpackDynamicTable dynamicTable;
    private final int maxTableSize;
    private final int softLimit;
    private final int hardLimit;

    /**
     * A decoder with a single limit: a header list past {@code maxHeaderListSize} ends the
     * connection, as if both limits of {@link #HpackDecoder(int, int, int)} were equal.
     *
     * @param dynamicTableMaxSize the SETTINGS_HEADER_TABLE_SIZE we advertise: the dynamic table
     *     starts at it, and a size update past it is a COMPRESSION_ERROR
     */
    public HpackDecoder(int dynamicTableMaxSize, int maxHeaderListSize) {
        this(dynamicTableMaxSize, maxHeaderListSize, maxHeaderListSize);
    }

    /**
     * A decoder with a soft and a hard header-list limit, both measured as RFC 9113 §6.5.2 does:
     * the octets of each name and value, plus 32 per field.
     *
     * @param dynamicTableMaxSize the SETTINGS_HEADER_TABLE_SIZE we advertise: the dynamic table
     *     starts at it, and a size update past it is a COMPRESSION_ERROR (RFC 7541 §4.2). The peer's
     *     own SETTINGS_HEADER_TABLE_SIZE limits our encoder, never this table.
     * @param softLimit past it, {@link #decode} stops forwarding fields and returns {@code false},
     *     but still decodes the whole block
     * @param hardLimit past it, {@link #decode} throws ENHANCE_YOUR_CALM; not below {@code softLimit}
     */
    public HpackDecoder(int dynamicTableMaxSize, int softLimit, int hardLimit) {
        if (softLimit < 0 || hardLimit < softLimit) {
            throw new IllegalArgumentException(
                    "Expected 0 <= softLimit <= hardLimit, got " + softLimit + " and " + hardLimit);
        }
        this.dynamicTable = new HpackDynamicTable(dynamicTableMaxSize);
        this.maxTableSize = dynamicTableMaxSize;
        this.softLimit = softLimit;
        this.hardLimit = hardLimit;
    }

    /**
     * Decodes a full header block and emits the pairs into the sink.
     *
     * <p>The whole block is always decoded, so the dynamic table stays in sync with the peer's
     * encoder (RFC 7541 §2.3.2) even when the list is too large to serve. Fields reach the sink
     * only while the running size stays within the soft limit. Names and values are decoded as
     * ISO-8859-1, one char per octet, so {@link String#length()} is the octet count.
     *
     * @return {@code true} if the whole list is within the soft limit
     * @throws Http2ConnectionException ENHANCE_YOUR_CALM as soon as the running size exceeds the
     *     hard limit (the connection is then closed, so the rest of the block does not matter);
     *     COMPRESSION_ERROR for malformed HPACK: a truncated or oversized integer or string, a
     *     string longer than the rest of the block, an invalid Huffman code, an index out of the
     *     tables, or a dynamic table size update past our advertised size or after the first field
     *     of the block (RFC 7541 §4.2, §5.1, §5.2, §6.3); or the error the sink raises
     */
    public boolean decode(ByteBuffer headerBlock, HeaderSink sink) throws Http2ConnectionException {
        long totalSize = 0;
        boolean within = true;
        boolean fieldSeen = false;

        while (headerBlock.hasRemaining()) {
            int b = headerBlock.get(headerBlock.position()) & 0xFF;

            String name, value;

            if ((b & 0x80) != 0) {
                // Indexed Header Field (RFC 7541 §6.1) — 1xxxxxxx
                int index = decodeInteger(headerBlock, 7);
                name = lookupName(index);
                value = lookupValue(index);

            } else if ((b & 0xC0) == 0x40) {
                // Literal with Incremental Indexing (RFC 7541 §6.2.1) — 01xxxxxx
                int index = decodeInteger(headerBlock, 6);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);
                dynamicTable.add(name, value);

            } else if ((b & 0xF0) == 0x00) {
                // Literal without Indexing (RFC 7541 §6.2.2) — 0000xxxx
                int index = decodeInteger(headerBlock, 4);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);

            } else if ((b & 0xF0) == 0x10) {
                // Literal Never Indexed (RFC 7541 §6.2.3) — 0001xxxx
                int index = decodeInteger(headerBlock, 4);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);

            } else {
                // Dynamic Table Size Update (RFC 7541 §6.3) — 001xxxxx
                int newSize = decodeInteger(headerBlock, 5);
                if (fieldSeen) {
                    // RFC 7541 §4.2: a size update must come at the beginning of the block.
                    throw new Http2ConnectionException(
                            Http2ErrorCode.COMPRESSION_ERROR, "HPACK dynamic table size update after a field");
                }
                if (newSize > maxTableSize) {
                    // RFC 7541 §6.3: the new size must not exceed the SETTINGS_HEADER_TABLE_SIZE we advertise.
                    throw new Http2ConnectionException(
                            Http2ErrorCode.COMPRESSION_ERROR,
                            "HPACK dynamic table size update to " + newSize + " exceeds " + maxTableSize);
                }
                dynamicTable.setMaxSize(newSize);
                continue;
            }
            fieldSeen = true;

            // Octets, since both decodeString and HpackHuffman decode ISO-8859-1 (RFC 9113 §6.5.2).
            totalSize += (long) name.length() + value.length() + 32;
            if (totalSize > hardLimit) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.ENHANCE_YOUR_CALM, "Header list exceeds max size " + hardLimit);
            }

            if (totalSize <= softLimit) {
                sink.accept(name, value);
            } else {
                within = false;
            }
        }
        return within;
    }

    // --- Helpers ---

    /**
     * Decodes an HPACK integer (RFC 7541 §5.1).
     *
     * @throws Http2ConnectionException COMPRESSION_ERROR if the integer is cut short, takes more
     *     continuation octets than an int needs, or exceeds {@link Integer#MAX_VALUE}
     */
    static int decodeInteger(ByteBuffer buf, int prefix) throws Http2ConnectionException {
        if (!buf.hasRemaining()) throw truncated();
        int mask = (1 << prefix) - 1;
        int first = buf.get() & mask;

        if (first < mask) return first;

        long value = first;
        int shift = 0;
        for (int octets = 0; ; octets++) {
            if (octets == MAX_INTEGER_CONTINUATION_OCTETS) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.COMPRESSION_ERROR, "HPACK integer has too many continuation octets");
            }
            if (!buf.hasRemaining()) throw truncated();
            int b = buf.get() & 0xFF;
            value += (long) (b & 0x7F) << shift;
            if (value > Integer.MAX_VALUE) {
                throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "HPACK integer overflow");
            }
            if ((b & 0x80) == 0) return (int) value;
            shift += 7;
        }
    }

    /**
     * Decodes an HPACK string (RFC 7541 §5.2). The declared length is checked against the rest of
     * the block before anything is allocated, so a short block cannot request a large buffer.
     */
    private static String decodeString(ByteBuffer buf) throws Http2ConnectionException {
        if (!buf.hasRemaining()) throw truncated();
        boolean huffman = (buf.get(buf.position()) & 0x80) != 0;
        int length = decodeInteger(buf, 7);
        if (length > buf.remaining()) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.COMPRESSION_ERROR,
                    "HPACK string length " + length + " exceeds the " + buf.remaining() + " octets left in the block");
        }

        if (huffman) {
            try {
                return HpackHuffman.decode(buf, length);
            } catch (IllegalArgumentException e) {
                throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, e.getMessage());
            }
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static Http2ConnectionException truncated() {
        return new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Truncated HPACK header block");
    }

    private String lookupName(int index) throws Http2ConnectionException {
        checkIndex(index);
        if (index <= HpackStaticTable.size()) {
            return HpackStaticTable.name(index);
        }
        int dynIndex = index - HpackStaticTable.size();
        if (dynIndex > dynamicTable.count()) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
        return dynamicTable.name(dynIndex);
    }

    private String lookupValue(int index) throws Http2ConnectionException {
        checkIndex(index);
        if (index <= HpackStaticTable.size()) {
            return HpackStaticTable.value(index);
        }
        int dynIndex = index - HpackStaticTable.size();
        if (dynIndex > dynamicTable.count()) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
        return dynamicTable.value(dynIndex);
    }

    /** RFC 7541 §6.1: index 0 is not a valid table index. */
    private static void checkIndex(int index) throws Http2ConnectionException {
        if (index <= 0) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
    }
}
