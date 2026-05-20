package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Socket;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de conformite HTTP/1.1 pour les connexions persistantes (keep-alive).
 * RFC 9112, Section 9.3.
 */
class Http11KeepAliveTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("OK"))
                .get("/a", _ -> Response.ok("A"))
                .get("/b", _ -> Response.ok("B"))
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void keepAliveDefault() throws IOException {
        // HTTP/1.1 default is keep-alive: send 3 requests on the same socket
        try (Socket socket = RawHttp.openConnection(port, 5_000)) {
            for (int i = 0; i < 3; i++) {
                String response =
                        RawHttp.sendAndReceiveOnSocket(socket, "GET / HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n");

                assertEquals(
                        200,
                        RawHttp.extractStatusCode(response),
                        "Request " + (i + 1) + " should succeed on keep-alive connection");
                assertEquals("OK", RawHttp.extractBody(response));
            }
        }
    }

    @Test
    void connectionClose() {
        // Send Connection: close → response should have Connection: close
        // and server closes the connection after the response
        String response = RawHttp.sendAndReceive(
                port, "GET / HTTP/1.1\r\n" + "Host: localhost\r\n" + "Connection: close\r\n" + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        String connectionHeader = RawHttp.extractHeader(response, "Connection");
        assertEquals("close", connectionHeader, "Response should include Connection: close");
    }

    @Test
    void http10NoKeepAlive() {
        // HTTP/1.0 without Connection: keep-alive → connection closes after response
        String response = RawHttp.sendAndReceive(port, "GET / HTTP/1.0\r\n" + "Host: localhost\r\n" + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        // Connection should be closed (Connection: close in response)
        String connectionHeader = RawHttp.extractHeader(response, "Connection");
        assertEquals("close", connectionHeader, "HTTP/1.0 response without keep-alive should have Connection: close");
    }

    @Test
    void pipelining() throws IOException {
        // Send 2 requests back-to-back without reading between them
        // Server should return 2 responses in order
        try (Socket socket = RawHttp.openConnection(port, 5_000)) {
            // Send both requests at once
            String request1 = "GET /a HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n";
            String request2 = "GET /b HTTP/1.1\r\n" + "Host: localhost\r\n" + "Connection: close\r\n" + "\r\n";

            RawHttp.write(socket.getOutputStream(), request1 + request2);

            // Read both responses
            String response1 = RawHttp.readResponse(socket.getInputStream());
            String response2 = RawHttp.readResponse(socket.getInputStream());

            assertEquals(200, RawHttp.extractStatusCode(response1));
            assertEquals("A", RawHttp.extractBody(response1), "First pipelined response should be for /a");

            assertEquals(200, RawHttp.extractStatusCode(response2));
            assertEquals("B", RawHttp.extractBody(response2), "Second pipelined response should be for /b");
        }
    }
}
