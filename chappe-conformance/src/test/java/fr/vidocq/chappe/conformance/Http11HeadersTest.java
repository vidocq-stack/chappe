package fr.vidocq.chappe.conformance;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Router;
import fr.vidocq.chappe.api.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de conformite HTTP/1.1 pour les en-tetes (headers).
 * RFC 9110, Section 5 ; RFC 9112, Section 5.
 */
class Http11HeadersTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/echo-header", req -> {
                    // Echo back the value of the requested header
                    String headerName = req.header("X-Echo-Header").orElse("Content-Type");
                    String value = req.header(headerName).orElse("<not found>");
                    return Response.ok(value);
                })
                .get("/echo-all-custom", req -> {
                    // Echo back all values of X-Custom header
                    var values = req.headers().all("X-Custom");
                    return Response.ok(String.join(", ", values));
                })
                .get("/echo-headers-dump", req -> {
                    // Dump all headers as name: value lines
                    var sb = new StringBuilder();
                    for (var entry : req.headers()) {
                        sb.append(entry.name()).append(": ").append(entry.value()).append("\n");
                    }
                    return Response.ok(sb.toString());
                })
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
    void caseInsensitiveHeaders() {
        // Send "content-TYPE" (mixed case) and verify the server reads it correctly
        String response = RawHttp.sendAndReceive(port,
                "GET /echo-header HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "X-Echo-Header: content-TYPE\r\n" +
                "content-TYPE: text/plain\r\n" +
                "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals("text/plain", RawHttp.extractBody(response),
                "Header lookup should be case-insensitive");
    }

    @Test
    void duplicateHeaders() {
        // Send two X-Custom headers — handler should see both via headers().all()
        String response = RawHttp.sendAndReceive(port,
                "GET /echo-all-custom HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "X-Custom: value1\r\n" +
                "X-Custom: value2\r\n" +
                "\r\n");

        assertEquals(200, RawHttp.extractStatusCode(response));
        String body = RawHttp.extractBody(response);
        assertTrue(body.contains("value1"), "Should contain first value: " + body);
        assertTrue(body.contains("value2"), "Should contain second value: " + body);
    }

    @Test
    void spaceBeforeColon() {
        // RFC 9112 Section 5: No whitespace is allowed between the field name
        // and colon. A server MUST reject with 400.
        String response = RawHttp.sendAndReceive(port,
                "GET /echo-header HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "X-Bad : value\r\n" +
                "\r\n");

        int status = RawHttp.extractStatusCode(response);
        assertEquals(400, status,
                "Space before colon in header should result in 400 Bad Request");
    }

    @Test
    void obsFoldRejected() {
        // RFC 9112 Section 5.2: obs-fold (continuation line starting with
        // space/tab) MUST be rejected with 400 by a server.
        String response = RawHttp.sendAndReceive(port,
                "GET /echo-header HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "X-Folded: first\r\n" +
                " continuation\r\n" +
                "\r\n");

        int status = RawHttp.extractStatusCode(response);
        assertEquals(400, status,
                "Obsolete header folding (obs-fold) should be rejected with 400");
    }

    @Test
    void largeHeaders() {
        // Send headers that approach the maxHeaderSize limit.
        // Default maxHeaderSize is 8192 bytes. Send a header larger than that.
        String largeValue = "X".repeat(9000);
        String response = RawHttp.sendAndReceive(port,
                "GET /echo-header HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "X-Large: " + largeValue + "\r\n" +
                "\r\n");

        int status = RawHttp.extractStatusCode(response);
        // Server should respond with 431 Request Header Fields Too Large
        // or close the connection
        assertTrue(status == 431 || status == 400,
                "Headers exceeding maxHeaderSize should return 431 or 400, got: " + status);
    }
}
