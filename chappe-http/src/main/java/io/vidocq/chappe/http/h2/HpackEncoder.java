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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.vidocq.chappe.api.Headers;

/**
 * Optimized HPACK encoder — RFC 7541.
 *
 * <p>Encoding strategy in priority order:
 * <ol>
 *   <li>Indexed Header Field (§6.1, prefix 0x80) — exact name+value match in the
 *       static or dynamic table.</li>
 *   <li>Literal with Incremental Indexing (§6.2.1, prefix 0x40) — indexed name or new name,
 *       the pair is added to the dynamic table.</li>
 * </ol>
 * String values are Huffman-encoded if that produces a shorter result.
 */
public final class HpackEncoder {

    /** Default max dynamic table size per RFC 7541 §6.5.2 (4096 bytes). */
    private static final int DEFAULT_MAX_TABLE_SIZE = 4096;

    private final HpackDynamicTable dynamicTable;

    public HpackEncoder() {
        this.dynamicTable = new HpackDynamicTable(DEFAULT_MAX_TABLE_SIZE);
    }

    /**
     * Encodes response headers into an HPACK header block.
     *
     * @param statusCode the HTTP status code
     * @param headers    the response headers
     * @return the encoded header block
     */
    public byte[] encode(int statusCode, Headers headers) {
        var out = new ByteArrayOutputStream(256);

        // :status pseudo-header
        String statusValue = String.valueOf(statusCode);
        encodeHeader(out, ":status", statusValue);

        // Regular headers
        for (var entry : headers) {
            encodeHeader(out, entry.name().toLowerCase(Locale.ROOT), entry.value());
        }

        return out.toByteArray();
    }

    /**
     * Encodes HTTP/2 trailers (HEADERS frame sent after the body).
     * <p>
     * Unlike {@link #encode(int, Headers)}, does not emit pseudo-headers:
     * RFC 9113 §8.1 forbids pseudo-headers in trailers.
     */
    public byte[] encodeTrailers(Headers trailers) {
        var out = new ByteArrayOutputStream(64);
        for (var entry : trailers) {
            encodeHeader(out, entry.name().toLowerCase(Locale.ROOT), entry.value());
        }
        return out.toByteArray();
    }

    /**
     * Encode a single name/value pair using the optimal representation:
     * indexed > literal with incremental indexing (indexed name) > literal with incremental
     * indexing (new name).
     */
    private void encodeHeader(ByteArrayOutputStream out, String name, String value) {
        // 1. Exact match in static table → Indexed (0x80)
        int staticExact = HpackStaticTable.findExact(name, value);
        if (staticExact > 0) {
            encodeInteger(out, staticExact, 7, 0x80);
            return;
        }

        // 2. Exact match in dynamic table → Indexed (0x80)
        int dynExact = findDynamicExact(name, value);
        if (dynExact > 0) {
            // Dynamic table indexes follow static table: offset by static table size
            encodeInteger(out, HpackStaticTable.size() + dynExact, 7, 0x80);
            return;
        }

        // 3. Name-only match in static table → Literal with incremental indexing (0x40), indexed name
        int staticName = HpackStaticTable.findByName(name);
        if (staticName > 0) {
            encodeInteger(out, staticName, 6, 0x40);
            encodeStringHuffman(out, value);
            dynamicTable.add(name, value);
            return;
        }

        // 4. Name-only match in dynamic table → Literal with incremental indexing (0x40), indexed name
        int dynName = findDynamicByName(name);
        if (dynName > 0) {
            encodeInteger(out, HpackStaticTable.size() + dynName, 6, 0x40);
            encodeStringHuffman(out, value);
            dynamicTable.add(name, value);
            return;
        }

        // 5. New name → Literal with incremental indexing (0x40), new name
        out.write(0x40);
        encodeStringHuffman(out, name);
        encodeStringHuffman(out, value);
        dynamicTable.add(name, value);
    }

    /** Searches the dynamic table for an exact name+value match. Returns 1-based index or 0. */
    private int findDynamicExact(String name, String value) {
        int count = dynamicTable.count();
        for (int i = 1; i <= count; i++) {
            if (dynamicTable.name(i).equals(name) && dynamicTable.value(i).equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /** Searches the dynamic table for a name-only match. Returns 1-based index or 0. */
    private int findDynamicByName(String name) {
        int count = dynamicTable.count();
        for (int i = 1; i <= count; i++) {
            if (dynamicTable.name(i).equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** Encodes an HPACK integer (RFC 7541 §5.1). */
    static void encodeInteger(ByteArrayOutputStream out, int value, int prefix, int pattern) {
        int mask = (1 << prefix) - 1;
        if (value < mask) {
            out.write(pattern | value);
        } else {
            out.write(pattern | mask);
            value -= mask;
            while (value >= 128) {
                out.write((value & 0x7F) | 0x80);
                value >>= 7;
            }
            out.write(value);
        }
    }

    /**
     * Encodes an HPACK string with Huffman if shorter, otherwise as a literal (RFC 7541 §5.2).
     */
    static void encodeStringHuffman(ByteArrayOutputStream out, String s) {
        byte[] huffman = HpackHuffman.encode(s);
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        if (huffman.length < raw.length) {
            encodeInteger(out, huffman.length, 7, 0x80); // H=1
            out.write(huffman, 0, huffman.length);
        } else {
            encodeInteger(out, raw.length, 7, 0x00); // H=0
            out.write(raw, 0, raw.length);
        }
    }

    /** Encodes an HPACK string without Huffman (RFC 7541 §5.2). Kept for compatibility. */
    static void encodeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        encodeInteger(out, bytes.length, 7, 0x00); // H=0 (no Huffman)
        out.write(bytes, 0, bytes.length);
    }
}
