package fr.vidocq.chappe.tests;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Router;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var filterCallCount = new AtomicInteger();

        var router = Router.builder()
                .get("/users/{id}", req -> {
                    var id = req.pathParams().get("id");
                    return Response.ok("user:" + id);
                })
                .get("/files/{name}/download", req -> {
                    var name = req.pathParams().get("name");
                    return Response.ok("download:" + name);
                })
                .group("/api/v1", api -> api
                        .get("/health", _ -> Response.ok("UP"))
                        .get("/version", _ -> Response.ok("1.0"))
                )
                .group("/admin", admin -> admin
                        .filter(next -> request -> {
                            filterCallCount.incrementAndGet();
                            var auth = request.header("Authorization").orElse(null);
                            if (auth == null) {
                                return Response.of(StatusCode.UNAUTHORIZED);
                            }
                            return next.handle(request);
                        })
                        .get("/dashboard", _ -> Response.ok("admin"))
                )
                .get("/static/*", req -> Response.ok("static:" + req.path()))
                .notFound(_ -> Response.builder()
                        .status(StatusCode.NOT_FOUND)
                        .body("custom 404")
                        .build())
                .build();

        server = Server.builder()
                .port(0)
                .handler(router)
                .build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void pathParam() throws IOException, InterruptedException {
        var response = get("/users/42");
        assertEquals(200, response.statusCode());
        assertEquals("user:42", response.body());
    }

    @Test
    void multiplePathSegments() throws IOException, InterruptedException {
        var response = get("/files/report.pdf/download");
        assertEquals(200, response.statusCode());
        assertEquals("download:report.pdf", response.body());
    }

    @Test
    void group() throws IOException, InterruptedException {
        assertEquals("UP", get("/api/v1/health").body());
        assertEquals("1.0", get("/api/v1/version").body());
    }

    @Test
    void filterBlocksUnauthenticated() throws IOException, InterruptedException {
        var response = get("/admin/dashboard");
        assertEquals(401, response.statusCode());
    }

    @Test
    void filterAllowsAuthenticated() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/dashboard"))
                .header("Authorization", "Bearer token")
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("admin", response.body());
    }

    @Test
    void wildcardRoute() throws IOException, InterruptedException {
        assertEquals("static:/static/css/style.css", get("/static/css/style.css").body());
        assertEquals("static:/static/js/app.js", get("/static/js/app.js").body());
    }

    @Test
    void customNotFound() throws IOException, InterruptedException {
        var response = get("/nonexistent");
        assertEquals(404, response.statusCode());
        assertEquals("custom 404", response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
