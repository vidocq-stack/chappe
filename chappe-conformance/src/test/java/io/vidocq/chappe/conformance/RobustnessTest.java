package io.vidocq.chappe.conformance;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robustness tests — verifies the server handles malformed, incomplete,
 * slow, and oversized requests gracefully without crashing.
 */
class RobustnessTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("ok"))
                .post("/echo", req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return Response.ok(body);
                })
                .build();

        server = Server.builder()
                .port(0)
                .readTimeout(Duration.ofSeconds(3))
                .maxHeaderSize(8192)
                .handler(router)
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * Sending garbage bytes should result in connection closed or a 400 Bad Request.
     * The server must not crash.
     */
    @Test
    void malformedRequestLine() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Send random garbage
            out.write(new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x0D, 0x0A});
            out.flush();

            var response = readResponse(in);
            // Server should either close the connection (empty response) or send 400
            assertTrue(response.isEmpty() || response.contains("400"),
                    "Garbage input should result in connection close or 400: " + truncate(response));
        }

        // Verify server is still running after the bad request
        assertTrue(server.isRunning(), "Server must survive malformed requests");
    }

    /**
     * Sending headers without the final empty line (\r\n\r\n) and then closing
     * the connection. The server should handle this gracefully.
     */
    @Test
    void truncatedHeaders() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();

            // Send partial headers — missing the final \r\n
            write(out, "GET / HTTP/1.1\r\n");
            write(out, "Host: localhost\r\n");
            // Intentionally NOT sending the final \r\n — close immediately
            out.flush();
        }
        // The socket is closed; server should handle the incomplete request

        // Small delay to let server process the close
        sleep(100);

        // Verify server is still running
        assertTrue(server.isRunning(), "Server must survive truncated headers");

        // Verify server still serves normal requests
        assertNormalRequestWorks();
    }

    /**
     * Open a connection, send a partial request, then abruptly close.
     * The server must not crash or leak resources.
     */
    @Test
    void connectionAbandoned() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();

            // Send just the beginning of a request line
            write(out, "GET /");
            out.flush();
            // Close without completing the request
        }

        sleep(100);

        assertTrue(server.isRunning(), "Server must survive abandoned connections");
        assertNormalRequestWorks();
    }

    /**
     * Send a request byte by byte with small delays between each byte.
     * The server should handle slow clients (within its read timeout).
     */
    @Test
    void slowClient() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            var request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            var bytes = request.getBytes(StandardCharsets.US_ASCII);

            // Send byte by byte with 10ms delay
            for (byte b : bytes) {
                out.write(b);
                out.flush();
                sleep(10);
            }

            var response = readResponse(in);
            // Should either succeed (200) or timeout (408)
            assertTrue(response.contains("200") || response.contains("408"),
                    "Slow client should get 200 or 408, got: " + truncate(response));
        }

        assertTrue(server.isRunning(), "Server must survive slow clients");
    }

    /**
     * Send a header that exceeds the configured maxHeaderSize (8192 bytes).
     * The server should respond with 431 Request Header Fields Too Large.
     */
    @Test
    void oversizedHeader() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Build a header value much larger than maxHeaderSize
            var largeValue = "X".repeat(16_384);
            write(out, "GET / HTTP/1.1\r\n");
            write(out, "Host: localhost\r\n");
            write(out, "X-Oversized: " + largeValue + "\r\n");
            write(out, "Connection: close\r\n");
            write(out, "\r\n");
            out.flush();

            try {
                var response = readResponse(in);
                // Expect 431 (Request Header Fields Too Large) or 400
                assertTrue(response.contains("431") || response.contains("400"),
                        "Oversized header should get 431 or 400, got: " + truncate(response));
            } catch (java.net.SocketException _) {
                // Connection reset by server is acceptable — server closed before response
            }
        }

        // Wait a bit for server to process
        try { Thread.sleep(100); } catch (InterruptedException _) {}
        assertTrue(server.isRunning(), "Server must survive oversized headers");
    }

    // --- Helpers ---

    private void assertNormalRequestWorks() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("200"),
                    "Normal request after abuse should succeed: " + truncate(response));
        }
    }

    private void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    private String readResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        var buf = new byte[4096];
        int read;
        try {
            while ((read = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
            }
        } catch (SocketTimeoutException _) {
            // Timeout is acceptable for some tests
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
