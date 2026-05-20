package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGetTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("Hello, Chappe!"))
                .get("/echo-path", req -> Response.ok(req.path()))
                .get("/echo-query", req -> {
                    var name = req.queryParam("name").orElse("unknown");
                    return Response.ok("name=" + name);
                })
                .get("/echo-header", req -> {
                    var custom = req.header("X-Custom").orElse("missing");
                    return Response.ok("header=" + custom);
                })
                .get("/json", _ -> Response.builder()
                        .status(StatusCode.OK)
                        .header("Content-Type", "application/json")
                        .body("{\"status\":\"ok\"}")
                        .build())
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void getRoot() throws IOException, InterruptedException {
        var response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("Hello, Chappe!", response.body());
    }

    @Test
    void getPath() throws IOException, InterruptedException {
        var response = get("/echo-path");
        assertEquals(200, response.statusCode());
        assertEquals("/echo-path", response.body());
    }

    @Test
    void getNotFound() throws IOException, InterruptedException {
        var response = get("/does-not-exist");
        assertEquals(404, response.statusCode());
    }

    @Test
    void getWithQueryParams() throws IOException, InterruptedException {
        var response = get("/echo-query?name=chappe");
        assertEquals(200, response.statusCode());
        assertEquals("name=chappe", response.body());
    }

    @Test
    void getWithCustomHeader() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo-header"))
                .header("X-Custom", "test-value")
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("header=test-value", response.body());
    }

    @Test
    void getJsonResponse() throws IOException, InterruptedException {
        var response = get("/json");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"ok\"}", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void responseHasContentLength() throws IOException, InterruptedException {
        var response = get("/");
        var cl = response.headers().firstValue("Content-Length");
        assertTrue(cl.isPresent(), "Content-Length header should be present");
        assertEquals("Hello, Chappe!".length(), Integer.parseInt(cl.get()));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
