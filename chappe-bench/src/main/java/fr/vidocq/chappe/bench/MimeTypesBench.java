package fr.vidocq.chappe.bench;

import fr.vidocq.chappe.api.MimeTypes;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Compare la nouvelle implémentation zéro-alloc ({@code regionMatches(true, …)} sur tableau
 * ordonné par fréquence) à l'ancienne ({@code substring + toLowerCase + Map.getOrDefault}).
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class MimeTypesBench {

    private static final Map<String, String> OLD_TYPES = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"),
            Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"), Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"));

    @Param({"index.html", "APP.CSS", "main.js", "Logo.PNG", "unknown.xyz"})
    public String filename;

    @Benchmark
    public String current_detect() {
        return MimeTypes.detect(filename);
    }

    @Benchmark
    public String old_substringLowercase() {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "application/octet-stream";
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        return OLD_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    // Allocation profiler : JMH signalera bytes/op via -prof gc.
    @Benchmark
    public void current_alloc(Blackhole bh) {
        bh.consume(MimeTypes.detect(filename));
    }

    @Benchmark
    public void old_alloc(Blackhole bh) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            bh.consume("application/octet-stream");
            return;
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        bh.consume(OLD_TYPES.getOrDefault(ext, "application/octet-stream"));
    }
}
