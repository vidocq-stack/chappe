package io.vidocq.chappe.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Compare la résolution de métadonnées pour une ressource classpath :
 *   - Ancien chemin : {@code loader.getResource()} + {@code URLConnection.openConnection()}
 *     pour obtenir {@code size} et {@code lastModified}.
 *   - Nouveau chemin : lookup O(1) dans l'index {@link Map} pré-chargé depuis
 *     {@code META-INF/chappe-static-index.properties}.
 *
 * <p>Simule exactement la différence apportée par {@code chappe-static-index-maven-plugin}
 * au niveau de {@code StaticFileHandler.ClasspathSource.resolve()}.
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
        if (url == null) { bh.consume(0); return; }
        URLConnection conn = url.openConnection();
        conn.setUseCaches(false);
        bh.consume(conn.getContentLengthLong());
        bh.consume(conn.getLastModified());
    }

    @Benchmark
    public void current_indexedLookup(Blackhole bh) {
        IndexedEntry entry = index.get(resourcePath);
        if (entry == null) { bh.consume(0); return; }
        bh.consume(entry.size());
        bh.consume(entry.mtime());
    }
}
