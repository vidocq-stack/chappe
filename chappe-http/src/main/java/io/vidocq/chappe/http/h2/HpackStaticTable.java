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

import java.util.HashMap;
import java.util.Map;

/**
 * HPACK Static Table — RFC 7541 Appendix A.
 *
 * <p>All 61 entries exactly as specified. Indexes are 1-based per the RFC.
 */
public final class HpackStaticTable {

    // Entry format: { name, value }. Index 0 is unused; entries start at [1].
    private static final String[][] ENTRIES = {
        null, // [0] unused — table is 1-based
        {":authority", ""}, // [1]
        {":method", "GET"}, // [2]
        {":method", "POST"}, // [3]
        {":path", "/"}, // [4]
        {":path", "/index.html"}, // [5]
        {":scheme", "http"}, // [6]
        {":scheme", "https"}, // [7]
        {":status", "200"}, // [8]
        {":status", "204"}, // [9]
        {":status", "206"}, // [10]
        {":status", "304"}, // [11]
        {":status", "400"}, // [12]
        {":status", "404"}, // [13]
        {":status", "500"}, // [14]
        {"accept-charset", ""}, // [15]
        {"accept-encoding", "gzip, deflate"}, // [16]
        {"accept-language", ""}, // [17]
        {"accept-ranges", ""}, // [18]
        {"accept", ""}, // [19]
        {"access-control-allow-origin", ""}, // [20]
        {"age", ""}, // [21]
        {"allow", ""}, // [22]
        {"authorization", ""}, // [23]
        {"cache-control", ""}, // [24]
        {"content-disposition", ""}, // [25]
        {"content-encoding", ""}, // [26]
        {"content-language", ""}, // [27]
        {"content-length", ""}, // [28]
        {"content-location", ""}, // [29]
        {"content-range", ""}, // [30]
        {"content-type", ""}, // [31]
        {"cookie", ""}, // [32]
        {"date", ""}, // [33]
        {"etag", ""}, // [34]
        {"expect", ""}, // [35]
        {"expires", ""}, // [36]
        {"from", ""}, // [37]
        {"host", ""}, // [38]
        {"if-match", ""}, // [39]
        {"if-modified-since", ""}, // [40]
        {"if-none-match", ""}, // [41]
        {"if-range", ""}, // [42]
        {"if-unmodified-since", ""}, // [43]
        {"last-modified", ""}, // [44]
        {"link", ""}, // [45]
        {"location", ""}, // [46]
        {"max-forwards", ""}, // [47]
        {"proxy-authenticate", ""}, // [48]
        {"proxy-authorization", ""}, // [49]
        {"range", ""}, // [50]
        {"referer", ""}, // [51]
        {"refresh", ""}, // [52]
        {"retry-after", ""}, // [53]
        {"server", ""}, // [54]
        {"set-cookie", ""}, // [55]
        {"strict-transport-security", ""}, // [56]
        {"transfer-encoding", ""}, // [57]
        {"user-agent", ""}, // [58]
        {"vary", ""}, // [59]
        {"via", ""}, // [60]
        {"www-authenticate", ""}, // [61]
    };

    private HpackStaticTable() {}

    /** Returns 61, the number of entries in the static table. */
    public static int size() {
        return 61;
    }

    /**
     * Returns the header name at the given 1-based index.
     *
     * @param index 1–61
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static String name(int index) {
        checkIndex(index);
        return ENTRIES[index][0];
    }

    /**
     * Returns the header value at the given 1-based index.
     *
     * @param index 1–61
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static String value(int index) {
        checkIndex(index);
        return ENTRIES[index][1];
    }

    private static final Map<String, Integer> NAME_FIRST_INDEX;
    private static final Map<Key, Integer> EXACT_INDEX;

    static {
        Map<String, Integer> byName = new HashMap<>(128);
        Map<Key, Integer> byExact = new HashMap<>(128);
        for (int i = 1; i <= 61; i++) {
            String n = ENTRIES[i][0];
            String v = ENTRIES[i][1];
            byName.putIfAbsent(n, i);
            byExact.putIfAbsent(new Key(n, v), i);
        }
        NAME_FIRST_INDEX = Map.copyOf(byName);
        EXACT_INDEX = Map.copyOf(byExact);
    }

    private record Key(String name, String value) {}

    // Cached references for ultra-hot names to enable identity comparisons.
    // Compare with `==` before `equals()`: zero extra cost when caller passes interned
    // String references (as HpackEncoder does using constants from this class).
    private static final String N_METHOD = ENTRIES[2][0]; // ":method"
    private static final String N_PATH = ENTRIES[4][0]; // ":path"
    private static final String N_SCHEME = ENTRIES[6][0]; // ":scheme"
    private static final String N_STATUS = ENTRIES[8][0]; // ":status"

    /**
     * Finds the 1-based index of the entry whose name and value both match exactly.
     *
     * @return 1-based index, or 0 if not found
     */
    @SuppressWarnings("ReferenceEquality") // Intentional `==` comparisons: HPACK fast path
    public static int findExact(String name, String value) {
        // Inline fast path for pseudo-headers (>90% of HTTP/2 traffic).
        // Avoids Key-record allocation + HashMap traversal on the hot path.
        if (name == N_METHOD || ":method".equals(name)) {
            if ("GET".equals(value)) return 2;
            if ("POST".equals(value)) return 3;
        } else if (name == N_PATH || ":path".equals(name)) {
            if ("/".equals(value)) return 4;
            if ("/index.html".equals(value)) return 5;
        } else if (name == N_SCHEME || ":scheme".equals(name)) {
            if ("http".equals(value)) return 6;
            if ("https".equals(value)) return 7;
        } else if (name == N_STATUS || ":status".equals(name)) {
            return switch (value) {
                case "200" -> 8;
                case "204" -> 9;
                case "206" -> 10;
                case "304" -> 11;
                case "400" -> 12;
                case "404" -> 13;
                case "500" -> 14;
                default -> 0;
            };
        }
        Integer idx = EXACT_INDEX.get(new Key(name, value));
        return idx == null ? 0 : idx;
    }

    /**
     * Finds the first 1-based index whose name matches (value is ignored).
     *
     * @return 1-based index, or 0 if not found
     */
    @SuppressWarnings("ReferenceEquality") // Intentional `==` comparisons: HPACK fast path
    public static int findByName(String name) {
        // Inline fast path for pseudo-headers (>90% of HTTP/2 traffic).
        if (name == N_METHOD || ":method".equals(name)) return 2;
        if (name == N_PATH || ":path".equals(name)) return 4;
        if (name == N_SCHEME || ":scheme".equals(name)) return 6;
        if (name == N_STATUS || ":status".equals(name)) return 8;
        Integer idx = NAME_FIRST_INDEX.get(name);
        return idx == null ? 0 : idx;
    }

    private static void checkIndex(int index) {
        if (index < 1 || index > 61) {
            throw new IndexOutOfBoundsException("HPACK static table index out of range: " + index + " (valid: 1–61)");
        }
    }
}
