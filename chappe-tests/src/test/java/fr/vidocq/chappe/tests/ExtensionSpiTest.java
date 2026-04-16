package fr.vidocq.chappe.tests;

import fr.vidocq.chappe.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des SPI d'extension : mount(), StaticFileHandler, RequestContext,
 * Body.ofFile(), Body.ofOutputStream(), MimeTypes.
 */
class ExtensionSpiTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;
    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private void startServer(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── mount() ──

    @Test
    void mountPathStripping() throws Exception {
        var router = Router.builder()
                .get("/", _ -> Response.ok("root"))
                .mount("/api", req -> Response.ok("api:" + req.path()))
                .mount("/admin", req -> Response.ok("admin:" + req.path()))
                .build();
        startServer(router);

        assertEquals("root", get("/").body());
        assertEquals("api:/hello", get("/api/hello").body());
        assertEquals("api:/", get("/api").body());
        assertEquals("admin:/dashboard", get("/admin/dashboard").body());
    }

    @Test
    void mountContextPath() throws Exception {
        var router = Router.builder()
                .mount("/servlet", req -> Response.ok(
                        "ctx=" + req.contextPath() + " pathInfo=" + req.pathInfo()))
                .build();
        startServer(router);

        var resp = get("/servlet/hello");
        assertEquals("ctx=/servlet pathInfo=/hello", resp.body());
    }

    @Test
    void mountCoexistence() throws Exception {
        var router = Router.builder()
                .mount("/api", _ -> Response.ok("jaxrs"))
                .mount("/app", _ -> Response.ok("servlet"))
                .get("/health", _ -> Response.ok("up"))
                .build();
        startServer(router);

        assertEquals("jaxrs", get("/api/v1/users").body());
        assertEquals("servlet", get("/app/index.jsp").body());
        assertEquals("up", get("/health").body());
    }

    @Test
    void routesTakePriorityOverMounts() throws Exception {
        var router = Router.builder()
                .get("/api/special", _ -> Response.ok("exact"))
                .mount("/api", _ -> Response.ok("mount"))
                .build();
        startServer(router);

        assertEquals("exact", get("/api/special").body());
        assertEquals("mount", get("/api/other").body());
    }

    // ── StaticFileHandler ──

    @Test
    void staticFileServing() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "Hello World");
        Files.writeString(tempDir.resolve("style.css"), "body { color: red; }");

        var router = Router.builder()
                .mount("/static", StaticFileHandler.of(tempDir))
                .build();
        startServer(router);

        var txt = get("/static/hello.txt");
        assertEquals(200, txt.statusCode());
        assertEquals("Hello World", txt.body());
        assertTrue(txt.headers().firstValue("Content-Type").orElse("").contains("text/plain"));

        var css = get("/static/style.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElse("").contains("text/css"));
    }

    @Test
    void staticFileNotFound() throws Exception {
        var router = Router.builder()
                .mount("/static", StaticFileHandler.of(tempDir))
                .build();
        startServer(router);

        assertEquals(404, get("/static/nonexistent.txt").statusCode());
    }

    @Test
    void staticFilePathTraversal() throws Exception {
        var router = Router.builder()
                .mount("/static", StaticFileHandler.of(tempDir))
                .build();
        startServer(router);

        assertEquals(403, get("/static/../../../etc/passwd").statusCode());
    }

    @Test
    void staticFileDirectoryIndex() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "<html>Home</html>");

        var router = Router.builder()
                .mount("/static", StaticFileHandler.of(tempDir))
                .build();
        startServer(router);

        var resp = get("/static/");
        assertEquals(200, resp.statusCode());
        assertEquals("<html>Home</html>", resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }

    // ── RequestContext ScopedValue ──

    @Test
    void requestContextAccessible() throws Exception {
        startServer(_ -> {
            var ctx = RequestContext.current();
            assertNotNull(ctx);
            return Response.ok("method=" + ctx.request().method());
        });

        assertEquals("method=GET", get("/").body());
    }

    // ── Request metadata ──

    @Test
    void requestRemoteAddress() throws Exception {
        startServer(req -> {
            var remote = req.remoteAddress();
            return Response.ok(remote != null ? remote.getAddress().getHostAddress() : "null");
        });

        var resp = get("/");
        assertEquals("127.0.0.1", resp.body());
    }

    @Test
    void requestScheme() throws Exception {
        startServer(req -> Response.ok(req.scheme()));

        assertEquals("http", get("/").body());
    }

    @Test
    void requestAttributes() throws Exception {
        var filter = (Filter) next -> req -> {
            req.attribute("user", "admin");
            return next.handle(req);
        };

        var router = Router.builder()
                .filter(filter)
                .get("/", req -> Response.ok("user=" + req.attribute("user")))
                .build();
        startServer(router);

        assertEquals("user=admin", get("/").body());
    }

    // ── Body.ofOutputStream ──

    @Test
    void bodyOfOutputStream() throws Exception {
        startServer(_ -> Response.builder()
                .status(StatusCode.OK)
                .body(Body.ofOutputStream(out -> {
                    try {
                        out.write("streaming ".getBytes());
                        out.write("body".getBytes());
                    } catch (IOException _) {}
                }))
                .build());

        assertEquals("streaming body", get("/").body());
    }

    // ── Body.ofFile ──

    @Test
    void bodyOfFile() throws Exception {
        var file = tempDir.resolve("data.json");
        Files.writeString(file, "{\"key\":\"value\"}");

        startServer(_ -> Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", "application/json")
                .body(Body.ofFile(file))
                .build());

        var resp = get("/");
        assertEquals("{\"key\":\"value\"}", resp.body());
        assertEquals("15", resp.headers().firstValue("Content-Length").orElse(""));
    }

    // ── MimeTypes ──

    @Test
    void mimeTypeDetection() {
        assertEquals("text/html", MimeTypes.detect("index.html"));
        assertEquals("text/css", MimeTypes.detect("style.css"));
        assertEquals("text/javascript", MimeTypes.detect("app.js"));
        assertEquals("application/json", MimeTypes.detect("data.json"));
        assertEquals("image/png", MimeTypes.detect("logo.png"));
        assertEquals("image/svg+xml", MimeTypes.detect("icon.svg"));
        assertEquals("application/octet-stream", MimeTypes.detect("unknown.xyz"));
    }
}
