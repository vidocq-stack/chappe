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

    /** The number of entries in the static table. */
    private static final int SIZE = 61;

    private static final String METHOD = ":method";
    private static final String PATH = ":path";
    private static final String SCHEME = ":scheme";
    private static final String STATUS = ":status";

    // Entry format: { name, value }. Index 0 is unused; entries start at [1].
    private static final String[][] ENTRIES = {
        null, // [0] unused — table is 1-based
        {":authority", ""}, // [1]
        {METHOD, "GET"}, // [2]
        {METHOD, "POST"}, // [3]
        {PATH, "/"}, // [4]
        {PATH, "/index.html"}, // [5]
        {SCHEME, "http"}, // [6]
        {SCHEME, "https"}, // [7]
        {STATUS, "200"}, // [8]
        {STATUS, "204"}, // [9]
        {STATUS, "206"}, // [10]
        {STATUS, "304"}, // [11]
        {STATUS, "400"}, // [12]
        {STATUS, "404"}, // [13]
        {STATUS, "500"}, // [14]
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
        return SIZE;
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
        Map<String, Integer> byName = HashMap.newHashMap(SIZE);
        Map<Key, Integer> byExact = HashMap.newHashMap(SIZE);
        for (int i = 1; i <= SIZE; i++) {
            String n = ENTRIES[i][0];
            String v = ENTRIES[i][1];
            byName.putIfAbsent(n, i);
            byExact.putIfAbsent(new Key(n, v), i);
        }
        NAME_FIRST_INDEX = Map.copyOf(byName);
        EXACT_INDEX = Map.copyOf(byExact);
    }

    private record Key(String name, String value) {}

    /**
     * Finds the 1-based index of the entry whose name and value both match exactly.
     *
     * @return 1-based index, or 0 if not found
     */
    public static int findExact(String name, String value) {
        // Inline fast path for pseudo-headers (>90% of HTTP/2 traffic).
        // Avoids Key-record allocation + HashMap traversal on the hot path. String.equals tests
        // identity first, so the interned names the encoder passes match at the cost of a `==`.
        // The static table holds no other entry for these four names, so their answer is final.
        if (METHOD.equals(name)) return eitherIndex(value, "GET", 2, "POST", 3);
        if (PATH.equals(name)) return eitherIndex(value, "/", 4, "/index.html", 5);
        if (SCHEME.equals(name)) return eitherIndex(value, "http", 6, "https", 7);
        if (STATUS.equals(name)) return statusIndex(value);
        Integer idx = EXACT_INDEX.get(new Key(name, value));
        return idx == null ? 0 : idx;
    }

    /** {@code firstIndex} or {@code secondIndex} for the value that matches, 0 for neither. */
    private static int eitherIndex(String value, String first, int firstIndex, String second, int secondIndex) {
        if (first.equals(value)) return firstIndex;
        return second.equals(value) ? secondIndex : 0;
    }

    /** The index of a {@code :status} entry, 0 for a status the static table does not hold. */
    private static int statusIndex(String value) {
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

    /**
     * Finds the first 1-based index whose name matches (value is ignored).
     *
     * @return 1-based index, or 0 if not found
     */
    public static int findByName(String name) {
        // Inline fast path for pseudo-headers (>90% of HTTP/2 traffic).
        if (METHOD.equals(name)) return 2;
        if (PATH.equals(name)) return 4;
        if (SCHEME.equals(name)) return 6;
        if (STATUS.equals(name)) return 8;
        Integer idx = NAME_FIRST_INDEX.get(name);
        return idx == null ? 0 : idx;
    }

    private static void checkIndex(int index) {
        if (index < 1 || index > SIZE) {
            throw new IndexOutOfBoundsException("HPACK static table index out of range: " + index + " (valid: 1–61)");
        }
    }
}
