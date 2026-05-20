package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/2 stream-level tests using {@link HttpClient} with HTTP_2 prior-knowledge (h2c).
 * <p>
 * These tests validate correct stream handling: request/response on individual streams,
 * multiplexed concurrent streams, and POST body handling over HTTP/2.
 */
class Http2StreamTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("Hello HTTP/2 Stream!"))
                .get("/stream-test", _ -> Response.ok("stream-ok"))
                .post("/echo", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok(body);
                })
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();

        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * Simple GET on a single HTTP/2 stream should return 200 with body.
     */
    @Test
    void simpleGetStream() throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello HTTP/2 Stream!", response.body());
    }

    /**
     * HTTP/2 multiplexing: 5 concurrent GET requests should all succeed.
     * The client sends them concurrently over the same connection.
     */
    @Test
    void multipleStreams() throws Exception {
        var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();

        for (int i = 0; i < 5; i++) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/stream-test"))
                    .GET()
                    .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }

        // Wait for all responses
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        for (var future : futures) {
            var response = future.get();
            assertEquals(200, response.statusCode(), "Each concurrent stream should get 200");
            assertEquals("stream-ok", response.body(), "Each concurrent stream should get correct body");
        }
    }

    /**
     * POST with body over HTTP/2 — the body should be echoed back correctly.
     */
    @Test
    void postWithBody() throws IOException, InterruptedException {
        var bodyText = "HTTP/2 POST body content";
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyText))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(bodyText, response.body(), "POST body should be echoed back");
    }
}
