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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFileHandlerFallbackTest {

    @Test
    void notFoundFileServesFallbackWith404Status(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("404.html"), "<h1>not found</h1>");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .notFoundFile("/404.html")
                .build();

        Response r = h.handle(req("/missing"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
        assertTrue(bodyAsString(r).contains("not found"));
    }

    @Test
    void notFoundFileLeavesExistingResourcesUntouched(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("404.html"), "nf");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .notFoundFile("/404.html")
                .build();

        Response r = h.handle(req("/index.html"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("home"));
    }

    @Test
    void spaFallbackServesIndexWith200Status(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "<div id=app></div>");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .spaFallback("/index.html")
                .build();

        Response r = h.handle(req("/users/42"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("id=app"));
    }

    @Test
    void spaFallbackAndNotFoundFileMutuallyExclusive(@TempDir Path root) {
        StaticFileHandler.Builder b = StaticFileHandler.builder()
                .addPath(root)
                .spaFallback("/index.html")
                .notFoundFile("/404.html");
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void missingFallbackFileProducesPlainNotFound(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "home");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .notFoundFile("/does-not-exist.html")
                .build();

        Response r = h.handle(req("/whatever"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
    }

    @Test
    void notFoundFileWithoutLeadingSlashAccepted(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "home");
        Files.writeString(root.resolve("404.html"), "nf");

        Handler h = StaticFileHandler.builder()
                .addPath(root)
                .notFoundFile("404.html")
                .build();

        Response r = h.handle(req("/missing"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
        assertTrue(bodyAsString(r).contains("nf"));
    }

    private static Request req(String path) {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("http://test" + path); }
            @Override public String path() { return path; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
            @Override public String pathInfo() { return path; }
        };
    }

    private static String bodyAsString(Response r) throws IOException {
        Body b = r.body();
        assertNotNull(b);
        try (var in = b.asInputStream()) {
            return new String(in.readAllBytes());
        }
    }
}
