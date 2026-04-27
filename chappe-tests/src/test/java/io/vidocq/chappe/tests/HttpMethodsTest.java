package io.vidocq.chappe.tests;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpMethodsTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .post("/echo", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok(body);
                })
                .put("/echo", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.builder()
                            .status(StatusCode.OK)
                            .header("X-Method", "PUT")
                            .body(body)
                            .build();
                })
                .delete("/resource", _ -> Response.of(StatusCode.NO_CONTENT))
                .head("/exists", _ -> Response.ok("this body should not be sent"))
                .patch("/patch", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok("patched:" + body);
                })
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
    void postWithBody() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("hello post"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("hello post", response.body());
    }

    @Test
    void putWithBody() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo"))
                .PUT(HttpRequest.BodyPublishers.ofString("hello put"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("hello put", response.body());
        assertEquals("PUT", response.headers().firstValue("X-Method").orElse(""));
    }

    @Test
    void deleteNoBody() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/resource"))
                .DELETE()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    @Test
    void headRequest() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/exists"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // HEAD response should have no body
        assertTrue(response.body().isEmpty());
    }

    @Test
    void patchWithBody() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/patch"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("data"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("patched:data", response.body());
    }
}
