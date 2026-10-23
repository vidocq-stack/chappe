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
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.internal.HeaderFields;

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

    /** Guards the dynamic table and the pending size update: responses are encoded on many threads. */
    private final ReentrantLock lock = new ReentrantLock();

    /** The table size in force: at most the peer's SETTINGS_HEADER_TABLE_SIZE (RFC 7541 §4.2). */
    private int tableSize = DEFAULT_MAX_TABLE_SIZE;

    /** Whether the next block must open with a dynamic table size update. */
    private boolean sizeUpdatePending;

    /** The smallest table size since the last signalled update (RFC 7541 §4.2). */
    private int smallestPendingSize;

    public HpackEncoder() {
        this.dynamicTable = new HpackDynamicTable(DEFAULT_MAX_TABLE_SIZE);
    }

    /**
     * Applies the peer's SETTINGS_HEADER_TABLE_SIZE, which bounds this encoder's dynamic table
     * (RFC 9113 §6.5.2). The encoder keeps its own 4,096-octet table when the peer allows that much
     * and shrinks it otherwise; a change is signalled by a dynamic table size update at the start
     * of the next block, preceded by the smallest size reached in between (RFC 7541 §4.2, §6.3).
     *
     * @param peerHeaderTableSize the setting as read from the wire: an unsigned 32-bit value, so a
     *     negative {@code int} stands for 2^31 or more
     */
    public void applyPeerMaxTableSize(int peerHeaderTableSize) {
        int size = peerHeaderTableSize < 0
                ? DEFAULT_MAX_TABLE_SIZE
                : Math.min(peerHeaderTableSize, DEFAULT_MAX_TABLE_SIZE);
        lock.lock();
        try {
            if (size == tableSize) return;
            smallestPendingSize = sizeUpdatePending ? Math.min(smallestPendingSize, size) : size;
            sizeUpdatePending = true;
            tableSize = size;
            dynamicTable.setMaxSize(size);
        } finally {
            lock.unlock();
        }
    }

    /** Writes the pending size updates, if any, at the start of a block. Called under the lock. */
    private void writePendingSizeUpdate(ByteArrayOutputStream out) {
        if (!sizeUpdatePending) return;
        if (smallestPendingSize < tableSize) encodeInteger(out, smallestPendingSize, 5, 0x20);
        encodeInteger(out, tableSize, 5, 0x20);
        sizeUpdatePending = false;
    }

    /**
     * Encodes response headers into an HPACK header block.
     *
     * @param statusCode the HTTP status code
     * @param headers    the response headers
     * @return the encoded header block
     * @throws IllegalArgumentException if a name is not an RFC 9110 token or a value holds CR, LF,
     *         NUL, another control or a char above {@code U+00FF} (HPACK carries octets: such a
     *         char would be mangled, and RFC 9113 §8.2.1 makes CR, LF and NUL malformed); the
     *         dynamic table is then left untouched
     */
    public byte[] encode(int statusCode, Headers headers) {
        // All fields are checked before the first one is encoded: a failure must leave the dynamic
        // table untouched, or it would no longer match the peer's (CHAPPE-008). The fields are read
        // once into a snapshot, so a custom Headers cannot hand back other text after the check.
        var fields = new ArrayList<String[]>(Math.max(0, headers.size()));
        for (var entry : headers) {
            HeaderFields.requireValid("response header", entry.name(), entry.value());
            String name = entry.name().toLowerCase(Locale.ROOT);
            String value = trimWhitespace(entry.value());
            if (!connectionSpecific(name, value)) fields.add(new String[] {name, value});
        }
        var out = new ByteArrayOutputStream(256);
        lock.lock();
        try {
            writePendingSizeUpdate(out);

            // :status pseudo-header
            String statusValue = String.valueOf(statusCode);
            encodeHeader(out, ":status", statusValue);

            // Regular headers
            for (String[] field : fields) {
                encodeHeader(out, field[0], field[1]);
            }
        } finally {
            lock.unlock();
        }
        return out.toByteArray();
    }

    /**
     * Whether a field must not be sent over HTTP/2: a connection-specific field, or {@code te} with
     * another value than {@code trailers} (RFC 9113 §8.2.2). A message carrying one is malformed, and
     * strict clients reset the stream, so it is dropped (CHAPPE-018).
     *
     * @param name the lower-case field name
     */
    static boolean connectionSpecific(String name, String value) {
        return switch (name) {
            case "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade" -> true;
            case "te" -> !value.equalsIgnoreCase("trailers");
            default -> false;
        };
    }

    /**
     * {@code value} without leading or trailing SP and HTAB: RFC 9113 §8.2.1 makes a value with
     * either malformed, and RFC 9110 §5.5 never counts them in the value (CHAPPE-018).
     */
    static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) start++;
        while (end > start && isWhitespace(value.charAt(end - 1))) end--;
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * Encodes HTTP/2 trailers (HEADERS frame sent after the body).
     * <p>
     * Unlike {@link #encode(int, Headers)}, does not emit pseudo-headers:
     * RFC 9113 §8.1 forbids pseudo-headers in trailers.
     * A trailer whose name is not a token or whose value is not a valid field value is dropped,
     * as over HTTP/1.1; so is a connection-specific one, and surrounding whitespace is trimmed (CHAPPE-018).
     */
    public byte[] encodeTrailers(Headers trailers) {
        var out = new ByteArrayOutputStream(64);
        lock.lock();
        try {
            writePendingSizeUpdate(out);
            for (var entry : trailers) {
                encodeTrailer(out, entry.name(), entry.value());
            }
        } finally {
            lock.unlock();
        }
        return out.toByteArray();
    }

    /**
     * Encodes one trailer, or drops it: an invalid one, as over HTTP/1.1 (CHAPPE-008), and a
     * connection-specific one (CHAPPE-018). Called under the lock.
     */
    private void encodeTrailer(ByteArrayOutputStream out, String rawName, String rawValue) {
        if (!HeaderFields.isValid(rawName, rawValue)) {
            return;
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        String value = trimWhitespace(rawValue);
        if (!connectionSpecific(name, value)) {
            encodeHeader(out, name, value);
        }
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
