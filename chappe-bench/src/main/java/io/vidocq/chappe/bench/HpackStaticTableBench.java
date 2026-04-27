package io.vidocq.chappe.bench;

import io.vidocq.chappe.http.h2.HpackStaticTable;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Compare le lookup actuel (maps pré-construites, O(1)) à l'ancien scan linéaire O(61)
 * sur les deux méthodes chaudes du hot path HPACK.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class HpackStaticTableBench {

    // 61 entrées — copiées pour reproduire l'ancienne implémentation scan linéaire.
    private static final String[][] OLD_ENTRIES = buildOld();

    // Inputs représentatifs : header fréquent (hit tôt), header rare (hit tardif), miss.
    @Param({":method", "vary", "x-custom-header"})
    public String name;

    @Param({"GET", ""})
    public String value;

    // ── findByName ──

    @Benchmark
    public int findByName_current() {
        return HpackStaticTable.findByName(name);
    }

    @Benchmark
    public int findByName_oldLinear() {
        for (int i = 1; i <= 61; i++) {
            if (OLD_ENTRIES[i][0].equals(name)) return i;
        }
        return 0;
    }

    // ── findExact ──

    @Benchmark
    public int findExact_current() {
        return HpackStaticTable.findExact(name, value);
    }

    @Benchmark
    public int findExact_oldLinear() {
        for (int i = 1; i <= 61; i++) {
            if (OLD_ENTRIES[i][0].equals(name) && OLD_ENTRIES[i][1].equals(value)) return i;
        }
        return 0;
    }

    private static String[][] buildOld() {
        return new String[][] {
            null,
            { ":authority", "" }, { ":method", "GET" }, { ":method", "POST" },
            { ":path", "/" }, { ":path", "/index.html" },
            { ":scheme", "http" }, { ":scheme", "https" },
            { ":status", "200" }, { ":status", "204" }, { ":status", "206" },
            { ":status", "304" }, { ":status", "400" }, { ":status", "404" }, { ":status", "500" },
            { "accept-charset", "" }, { "accept-encoding", "gzip, deflate" },
            { "accept-language", "" }, { "accept-ranges", "" }, { "accept", "" },
            { "access-control-allow-origin", "" }, { "age", "" }, { "allow", "" },
            { "authorization", "" }, { "cache-control", "" }, { "content-disposition", "" },
            { "content-encoding", "" }, { "content-language", "" }, { "content-length", "" },
            { "content-location", "" }, { "content-range", "" }, { "content-type", "" },
            { "cookie", "" }, { "date", "" }, { "etag", "" }, { "expect", "" },
            { "expires", "" }, { "from", "" }, { "host", "" }, { "if-match", "" },
            { "if-modified-since", "" }, { "if-none-match", "" }, { "if-range", "" },
            { "if-unmodified-since", "" }, { "last-modified", "" }, { "link", "" },
            { "location", "" }, { "max-forwards", "" }, { "proxy-authenticate", "" },
            { "proxy-authorization", "" }, { "range", "" }, { "referer", "" },
            { "refresh", "" }, { "retry-after", "" }, { "server", "" },
            { "set-cookie", "" }, { "strict-transport-security", "" },
            { "transfer-encoding", "" }, { "user-agent", "" }, { "vary", "" },
            { "via", "" }, { "www-authenticate", "" },
        };
    }
}
