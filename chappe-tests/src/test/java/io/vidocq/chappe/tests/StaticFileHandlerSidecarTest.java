package io.vidocq.chappe.tests;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StaticFileHandlerSidecarTest {

    @Test
    void gzipSidecarServedWhenAccepted(@TempDir Path root) throws Exception {
        Path orig = root.resolve("style.css");
        Files.writeString(orig, "body{margin:0}");
        byte[] gz = gzipBytes(Files.readAllBytes(orig));
        Files.write(root.resolve("style.css.gz"), gz);

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .preferPrecompressed(true)
                .build();

        Response r = h.handle(req("/style.css", "gzip"));
        assertEquals(StatusCode.OK, r.status());
        assertEquals("gzip", r.headers().firstOrNull("Content-Encoding"));
        assertEquals("Accept-Encoding", r.headers().firstOrNull("Vary"));
        // Content-Type stays the original (text/css), not application/gzip
        assertEquals("text/css", r.headers().firstOrNull("Content-Type"));
        assertNotNull(readAll(r.body()));
    }

    @Test
    void brSidecarPreferredOverGzip(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("app.js"), "console.log('hi')");
        Files.write(root.resolve("app.js.gz"), gzipBytes("FAKE GZ".getBytes()));
        Files.write(root.resolve("app.js.br"), "FAKE BR".getBytes());

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .preferPrecompressed(true)
                .build();

        Response r = h.handle(req("/app.js", "br, gzip"));
        assertEquals("br", r.headers().firstOrNull("Content-Encoding"));
        assertEquals("text/javascript", r.headers().firstOrNull("Content-Type"));
    }

    @Test
    void fallbackToOriginalWhenClientDoesNotAcceptGzip(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("style.css"), "body{}");
        Files.write(root.resolve("style.css.gz"), gzipBytes("body{}".getBytes()));

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .preferPrecompressed(true)
                .build();

        Response r = h.handle(req("/style.css", "identity"));
        // No Content-Encoding set when serving the original
        assertEquals(null, r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void noSidecarFallsBackToOriginal(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("style.css"), "body{}");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .preferPrecompressed(true)
                .build();

        Response r = h.handle(req("/style.css", "gzip"));
        assertEquals(StatusCode.OK, r.status());
        assertEquals(null, r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void preferPrecompressedDisabledIgnoresSidecars(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("style.css"), "body{}");
        Files.write(root.resolve("style.css.gz"), gzipBytes("body{}".getBytes()));

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .build();

        Response r = h.handle(req("/style.css", "gzip"));
        assertEquals(null, r.headers().firstOrNull("Content-Encoding"));
    }

    private static byte[] gzipBytes(byte[] data) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var gout = new GZIPOutputStream(bos)) {
            gout.write(data);
        }
        return bos.toByteArray();
    }

    private static Request req(String path, String acceptEncoding) {
        Headers h = Headers.builder().add("Accept-Encoding", acceptEncoding).build();
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("http://test" + path); }
            @Override public String path() { return path; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return h; }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
            @Override public String pathInfo() { return path; }
        };
    }

    private static byte[] readAll(Body b) throws Exception {
        try (var in = b.asInputStream()) { return in.readAllBytes(); }
    }
}
