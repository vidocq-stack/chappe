package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de conformite HTTP/1.1 pour le corps des messages (message body).
 * RFC 9112, Sections 6-7.
 */
class Http11BodyTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .post("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .get("/nobody", _ -> Response.ok("no-body-expected"))
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
    void fixedLengthBody() {
        String body = "Hello, Chappe! This is a fixed-length body.";
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n"
                        + "Content-Length: "
                        + body.length() + "\r\n" + "\r\n"
                        + body);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(body, RawHttp.extractBody(response));
    }

    @Test
    void chunkedBody() {
        // Transfer-Encoding: chunked with multiple chunks
        // Format: <size-hex>\r\n<data>\r\n ... 0\r\n\r\n
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n"
                        + "5\r\n"
                        + "Hello\r\n"
                        + "7\r\n"
                        + ", World\r\n"
                        + "0\r\n"
                        + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("Hello, World", RawHttp.extractBody(response));
    }

    @Test
    void chunkedWithExtensions() {
        // RFC 9112 Section 7.1.1: chunk extensions should be ignored
        // Format: <size>;ext=val\r\n<data>\r\n
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n"
                        + "5;ext=val\r\n"
                        + "Hello\r\n"
                        + "0\r\n"
                        + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("Hello", RawHttp.extractBody(response));
    }

    @Test
    void emptyChunkedBody() {
        // Immediately terminated chunked body: 0\r\n\r\n
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n"
                        + "0\r\n"
                        + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("", RawHttp.extractBody(response));
    }

    @Test
    void noBody() {
        // GET request with no Content-Length or Transfer-Encoding → body is empty
        String response = RawHttp.sendAndReceive(port, "GET /nobody HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("no-body-expected", RawHttp.extractBody(response));
    }
}
