package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests d'intégration HTTP/2 (h2c cleartext).
 * <p>
 * Utilise {@code HttpClient} avec {@code HTTP_2} qui fait un upgrade h2c
 * automatique, ou envoie le preface HTTP/2 directement.
 */
class Http2Test {

    private Server server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("Hello HTTP/2!"))
                .get("/echo-path", req -> Response.ok(req.path()))
                .post("/echo", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok(body);
                })
                .get("/json", _ -> Response.builder()
                        .status(StatusCode.OK)
                        .header("Content-Type", "application/json")
                        .body("{\"protocol\":\"h2c\"}")
                        .build())
                .get("/large", _ -> {
                    // Réponse de 100 Ko pour tester le flow control
                    var data = "x".repeat(100_000);
                    return Response.ok(data);
                })
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
        baseUrl = "http://127.0.0.1:" + port;

        // HttpClient avec HTTP/2 — fera un prior-knowledge h2c
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void http2GetRoot() throws IOException, InterruptedException {
        var response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("Hello HTTP/2!", response.body());
    }

    @Test
    void http2GetPath() throws IOException, InterruptedException {
        var response = get("/echo-path");
        assertEquals(200, response.statusCode());
        assertEquals("/echo-path", response.body());
    }

    @Test
    void http2PostWithBody() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("http2 body"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http2 body", response.body());
    }

    @Test
    void http2JsonResponse() throws IOException, InterruptedException {
        var response = get("/json");
        assertEquals(200, response.statusCode());
        assertEquals("{\"protocol\":\"h2c\"}", response.body());
    }

    @Test
    void http2LargeResponse() throws IOException, InterruptedException {
        var response = get("/large");
        assertEquals(200, response.statusCode());
        assertEquals(100_000, response.body().length());
    }

    @Test
    void http2MultipleRequests() throws IOException, InterruptedException {
        // HTTP/2 multiplexe sur la même connexion
        for (int i = 0; i < 5; i++) {
            var response = get("/");
            assertEquals(200, response.statusCode());
            assertEquals("Hello HTTP/2!", response.body());
        }
    }

    @Test
    void http2NotFound() throws IOException, InterruptedException {
        var response = get("/does-not-exist");
        assertEquals(404, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
