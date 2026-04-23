package fr.vidocq.chappe.http.h2;

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
        null,                                                  // [0] unused — table is 1-based
        { ":authority",                "" },                   // [1]
        { ":method",                   "GET" },                // [2]
        { ":method",                   "POST" },               // [3]
        { ":path",                     "/" },                  // [4]
        { ":path",                     "/index.html" },        // [5]
        { ":scheme",                   "http" },               // [6]
        { ":scheme",                   "https" },              // [7]
        { ":status",                   "200" },                // [8]
        { ":status",                   "204" },                // [9]
        { ":status",                   "206" },                // [10]
        { ":status",                   "304" },                // [11]
        { ":status",                   "400" },                // [12]
        { ":status",                   "404" },                // [13]
        { ":status",                   "500" },                // [14]
        { "accept-charset",            "" },                   // [15]
        { "accept-encoding",           "gzip, deflate" },      // [16]
        { "accept-language",           "" },                   // [17]
        { "accept-ranges",             "" },                   // [18]
        { "accept",                    "" },                   // [19]
        { "access-control-allow-origin", "" },                 // [20]
        { "age",                       "" },                   // [21]
        { "allow",                     "" },                   // [22]
        { "authorization",             "" },                   // [23]
        { "cache-control",             "" },                   // [24]
        { "content-disposition",       "" },                   // [25]
        { "content-encoding",          "" },                   // [26]
        { "content-language",          "" },                   // [27]
        { "content-length",            "" },                   // [28]
        { "content-location",          "" },                   // [29]
        { "content-range",             "" },                   // [30]
        { "content-type",              "" },                   // [31]
        { "cookie",                    "" },                   // [32]
        { "date",                      "" },                   // [33]
        { "etag",                      "" },                   // [34]
        { "expect",                    "" },                   // [35]
        { "expires",                   "" },                   // [36]
        { "from",                      "" },                   // [37]
        { "host",                      "" },                   // [38]
        { "if-match",                  "" },                   // [39]
        { "if-modified-since",         "" },                   // [40]
        { "if-none-match",             "" },                   // [41]
        { "if-range",                  "" },                   // [42]
        { "if-unmodified-since",       "" },                   // [43]
        { "last-modified",             "" },                   // [44]
        { "link",                      "" },                   // [45]
        { "location",                  "" },                   // [46]
        { "max-forwards",              "" },                   // [47]
        { "proxy-authenticate",        "" },                   // [48]
        { "proxy-authorization",       "" },                   // [49]
        { "range",                     "" },                   // [50]
        { "referer",                   "" },                   // [51]
        { "refresh",                   "" },                   // [52]
        { "retry-after",               "" },                   // [53]
        { "server",                    "" },                   // [54]
        { "set-cookie",                "" },                   // [55]
        { "strict-transport-security", "" },                   // [56]
        { "transfer-encoding",         "" },                   // [57]
        { "user-agent",                "" },                   // [58]
        { "vary",                      "" },                   // [59]
        { "via",                       "" },                   // [60]
        { "www-authenticate",          "" },                   // [61]
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

    /**
     * Finds the 1-based index of the entry whose name and value both match exactly.
     *
     * @return 1-based index, or 0 if not found
     */
    public static int findExact(String name, String value) {
        Integer idx = EXACT_INDEX.get(new Key(name, value));
        return idx == null ? 0 : idx;
    }

    /**
     * Finds the first 1-based index whose name matches (value is ignored).
     *
     * @return 1-based index, or 0 if not found
     */
    public static int findByName(String name) {
        Integer idx = NAME_FIRST_INDEX.get(name);
        return idx == null ? 0 : idx;
    }

    private static void checkIndex(int index) {
        if (index < 1 || index > 61) {
            throw new IndexOutOfBoundsException(
                    "HPACK static table index out of range: " + index + " (valid: 1–61)");
        }
    }
}
