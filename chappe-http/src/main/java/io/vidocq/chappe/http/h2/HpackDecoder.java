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

    private final HpackDynamicTable dynamicTable;
    private final int softLimit;
    private final int hardLimit;

    /**
     * A decoder with a single limit: a header list past {@code maxHeaderListSize} ends the
     * connection, as if both limits of {@link #HpackDecoder(int, int, int)} were equal.
     */
    public HpackDecoder(int dynamicTableMaxSize, int maxHeaderListSize) {
        this(dynamicTableMaxSize, maxHeaderListSize, maxHeaderListSize);
    }

    /**
     * A decoder with a soft and a hard header-list limit, both measured as RFC 9113 §6.5.2 does:
     * the octets of each name and value, plus 32 per field.
     *
     * @param dynamicTableMaxSize the maximum size of the HPACK dynamic table
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
     *     hard limit (the connection is then closed, so the rest of the block does not matter), or
     *     the error the sink or the HPACK decoding raises
     */
    public boolean decode(ByteBuffer headerBlock, HeaderSink sink) throws Http2ConnectionException {
        long totalSize = 0;
        boolean within = true;

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
                dynamicTable.setMaxSize(newSize);
                continue;
            }

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

    /** Updates the maximum dynamic table size (from SETTINGS). */
    public void updateMaxTableSize(int newMax) {
        dynamicTable.setMaxSize(newMax);
    }

    // --- Helpers ---

    /**
     * Decodes an HPACK integer (RFC 7541 §5.1).
     */
    static int decodeInteger(ByteBuffer buf, int prefix) {
        int mask = (1 << prefix) - 1;
        int value = buf.get() & mask;

        if (value < mask) return value;

        int shift = 0;
        int b;
        do {
            b = buf.get() & 0xFF;
            value += (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return value;
    }

    /**
     * Decodes an HPACK string (RFC 7541 §5.2).
     */
    private String decodeString(ByteBuffer buf) {
        int b = buf.get(buf.position()) & 0xFF;
        boolean huffman = (b & 0x80) != 0;
        int length = decodeInteger(buf, 7);

        if (huffman) {
            return HpackHuffman.decode(buf, length);
        } else {
            byte[] bytes = new byte[length];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private String lookupName(int index) throws Http2ConnectionException {
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
        if (index <= HpackStaticTable.size()) {
            return HpackStaticTable.value(index);
        }
        int dynIndex = index - HpackStaticTable.size();
        if (dynIndex > dynamicTable.count()) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
        return dynamicTable.value(dynIndex);
    }
}
