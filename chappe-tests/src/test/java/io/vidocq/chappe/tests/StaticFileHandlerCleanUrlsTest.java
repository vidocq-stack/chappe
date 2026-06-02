package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

/**
 * Clean-URL ("pretty URL") resolution: an extensionless request resolves to its {@code .html}
 * sibling so a multi-page build exposes {@code /admin} without the {@code .html} suffix and without a
 * per-app redirect. Surfaced by Arago (cf. arago {@code ARAGO-006}), where the admin console is a Vite
 * multi-page entry built as {@code static/admin.html}.
 */
class StaticFileHandlerCleanUrlsTest {

    @Test
    void extensionlessPathResolvesToHtmlSibling(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("admin.html"), "<h1>admin console</h1>");

        Handler h = StaticFileHandler.builder().addPath(root).cleanUrls(true).build();

        Response r = h.handle(req("/admin"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("admin console"));
        assertEquals("text/html", r.headers().first("Content-Type").orElseThrow());
    }

    @Test
    void trailingSlashOnExtensionlessPathAlsoResolvesToHtmlSibling(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("admin.html"), "<h1>admin console</h1>");

        Handler h = StaticFileHandler.builder().addPath(root).cleanUrls(true).build();

        Response r = h.handle(req("/admin/"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("admin console"));
    }

    @Test
    void explicitHtmlStillServedUnchanged(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("admin.html"), "<h1>admin console</h1>");

        Handler h = StaticFileHandler.builder().addPath(root).cleanUrls(true).build();

        Response r = h.handle(req("/admin.html"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("admin console"));
    }

    @Test
    void pathWithExtensionIsNotRewritten(@TempDir Path root) throws Exception {
        // A missing /style.css must NOT be looked up as /style.css.html.
        Files.writeString(root.resolve("index.html"), "home");

        Handler h = StaticFileHandler.builder().addPath(root).cleanUrls(true).build();

        Response r = h.handle(req("/style.css"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
    }

    @Test
    void extensionlessMissStillNotFoundWhenNoHtmlSibling(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.html"), "home");

        Handler h = StaticFileHandler.builder().addPath(root).cleanUrls(true).build();

        Response r = h.handle(req("/nope"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
    }

    @Test
    void cleanUrlsDisabledByDefaultLeavesExtensionlessPathAs404(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("admin.html"), "<h1>admin</h1>");

        Handler h = StaticFileHandler.builder().addPath(root).build();

        Response r = h.handle(req("/admin"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
    }

    private static Request req(String path) {
        return new Request() {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public URI uri() {
                return URI.create("http://test" + path);
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public HttpVersion version() {
                return HttpVersion.HTTP_1_1;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public Body body() {
                return Body.empty();
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParams() {
                return Map.of();
            }

            @Override
            public String pathInfo() {
                return path;
            }
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
