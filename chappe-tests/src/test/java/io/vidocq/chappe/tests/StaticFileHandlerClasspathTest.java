package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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

/**
 * Covers resolution of {@code GET /} and {@code GET /sub/} in classpath mode:
 * {@code loader.getResource("static-test")} returns the URL of a jar
 * directory — without prefixing with {@code indexFile}, {@code URLConnection} returns
 * a listing instead of serving the index.
 */
class StaticFileHandlerClasspathTest {

    private final Handler handler = StaticFileHandler.builder()
            .addClasspath("static-test")
            .indexFile("index.html")
            .build();

    @Test
    void rootResolvesToIndex() throws Exception {
        Response r = handler.handle(req("/", ""));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("hello root"));
    }

    @Test
    void emptyPathInfoResolvesToIndex() throws Exception {
        Response r = handler.handle(req("/", ""));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("hello root"));
    }

    @Test
    void subDirectoryResolvesToIndex() throws Exception {
        Response r = handler.handle(req("/sub/", "/sub/"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("hello sub"));
    }

    @Test
    void regularFileIsServed() throws Exception {
        Response r = handler.handle(req("/style.css", "/style.css"));
        assertEquals(StatusCode.OK, r.status());
        assertTrue(bodyAsString(r).contains("body{margin:0}"));
    }

    @Test
    void notFoundIs404() throws Exception {
        Response r = handler.handle(req("/missing.html", "/missing.html"));
        assertEquals(StatusCode.NOT_FOUND, r.status());
    }

    private static Request req(String path, String pathInfo) {
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
                return pathInfo;
            }
        };
    }

    private static String bodyAsString(Response r) throws Exception {
        Body b = r.body();
        assertNotNull(b);
        try (var in = b.asInputStream()) {
            return new String(in.readAllBytes());
        }
    }
}
