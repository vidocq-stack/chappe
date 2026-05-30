package io.vidocq.chappe.bench;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares metadata resolution for a classpath resource:
 *   - Old path: {@code loader.getResource()} + {@code URLConnection.openConnection()}
 *     to obtain {@code size} and {@code lastModified}.
 *   - New path: O(1) lookup in the {@link Map} index preloaded from
 *     {@code META-INF/chappe-static-index.properties}.
 *
 * <p>Exactly simulates the difference introduced by {@code chappe-static-index-maven-plugin}
 * at the {@code StaticFileHandler.ClasspathSource.resolve()} level.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class ClasspathLookupBench {

    record IndexedEntry(long size, long mtime, String mime, String etag) {}

    private ClassLoader loader;
    private String resourcePath;
    private Map<String, IndexedEntry> index;

    @Setup
    public void setup() {
        loader = Thread.currentThread().getContextClassLoader();
        resourcePath = "bench-static/test.html";
        index = new HashMap<>();
        index.put(resourcePath, new IndexedEntry(45L, 0L, "text/html", "abc1234567890abc"));
    }

    @Benchmark
    public void old_urlConnection(Blackhole bh) throws IOException {
        URL url = loader.getResource(resourcePath);
        if (url == null) {
            bh.consume(0);
            return;
        }
        URLConnection conn = url.openConnection();
        conn.setUseCaches(false);
        bh.consume(conn.getContentLengthLong());
        bh.consume(conn.getLastModified());
    }

    @Benchmark
    public void current_indexedLookup(Blackhole bh) {
        IndexedEntry entry = index.get(resourcePath);
        if (entry == null) {
            bh.consume(0);
            return;
        }
        bh.consume(entry.size());
        bh.consume(entry.mtime());
    }
}
