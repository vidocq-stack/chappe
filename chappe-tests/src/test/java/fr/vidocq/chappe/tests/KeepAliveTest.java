package fr.vidocq.chappe.tests;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KeepAliveTest {

    private Server server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok("ok"))
                .build();
        server.start();
        port = server.port();
        baseUrl = "http://127.0.0.1:" + port;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void multipleRequestsSameConnection() throws IOException, InterruptedException {
        for (int i = 0; i < 10; i++) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/"))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
        }
    }

    @Test
    void connectionCloseHeaderViaRawSocket() throws IOException {
        // HttpClient interdit le header Connection — on utilise un raw socket
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "GET / HTTP/1.1\r\n");
            write(out, "Host: localhost\r\n");
            write(out, "Connection: close\r\n");
            write(out, "\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(response.contains("ok"), "Body should contain 'ok': " + response);
            assertTrue(response.toLowerCase().contains("connection: close"),
                    "Should have Connection: close: " + response);
        }
    }

    private void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    private String readResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }
}
