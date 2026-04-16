package fr.vidocq.chappe.conformance;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Router;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de conformite HTTP/1.1 pour la ligne de requete (request-line).
 * RFC 9112, Section 3.
 */
class Http11RequestLineTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("OK"))
                .post("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .put("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .delete("/echo", _ -> Response.ok("deleted"))
                .patch("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .head("/", _ -> Response.ok())
                .options("/", _ -> Response.ok("options"))
                .build();

        server = Server.builder()
                .port(0)
                .handler(router)
                .build();
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
    void validGetRequest() {
        String response = RawHttp.sendAndReceive(port,
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("OK", RawHttp.extractBody(response));
    }

    @Test
    void validPostRequest() {
        String body = "Hello, Chappe!";
        String response = RawHttp.sendAndReceive(port,
                "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(body, RawHttp.extractBody(response));
    }

    @Test
    void allMethodsGet() {
        String response = RawHttp.sendAndReceive(port,
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void allMethodsPost() {
        String body = "test";
        String response = RawHttp.sendAndReceive(port,
                "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body);
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void allMethodsPut() {
        String body = "test";
        String response = RawHttp.sendAndReceive(port,
                "PUT /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body);
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void allMethodsDelete() {
        String response = RawHttp.sendAndReceive(port,
                "DELETE /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void allMethodsPatch() {
        String body = "patch";
        String response = RawHttp.sendAndReceive(port,
                "PATCH /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body);
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void allMethodsHead() {
        String response = RawHttp.sendAndReceive(port,
                "HEAD / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertEquals(200, RawHttp.extractStatusCode(response));
        // HEAD responses must not contain a body
        assertEquals("", RawHttp.extractBody(response));
    }

    @Test
    void allMethodsOptions() {
        String response = RawHttp.sendAndReceive(port,
                "OPTIONS / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertEquals(200, RawHttp.extractStatusCode(response));
    }

    @Test
    void unknownMethod() {
        String response = RawHttp.sendAndReceive(port,
                "FOOBAR / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        // Unknown method should return 501 Not Implemented
        int status = RawHttp.extractStatusCode(response);
        assertEquals(501, status, "Unknown method FOOBAR should return 501 Not Implemented");
    }

    @Test
    void http10Version() {
        String response = RawHttp.sendAndReceive(port,
                "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        // HTTP/1.0 default is Connection: close
        String connection = RawHttp.extractHeader(response, "Connection");
        assertEquals("close", connection, "HTTP/1.0 response should have Connection: close");
    }

    @Test
    void unsupportedVersion() {
        String response = RawHttp.sendAndReceive(port,
                "GET / HTTP/2.0\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        int status = RawHttp.extractStatusCode(response);
        assertEquals(400, status, "Unsupported HTTP version should return 400 Bad Request");
    }

    @Test
    void missingHostHeader() {
        // RFC 9112 requires Host header for HTTP/1.1, but we are lenient for now
        String response = RawHttp.sendAndReceive(port,
                "GET / HTTP/1.1\r\n" +
                "\r\n");

        int status = RawHttp.extractStatusCode(response);
        // Should still work (lenient mode)
        assertTrue(status >= 200 && status < 500,
                "Missing Host header should still work (lenient): got " + status);
    }
}
