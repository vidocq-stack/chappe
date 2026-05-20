package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests d'intégration pour Body.streaming(InputStream).
 * Vérifie le chunked transfer HTTP/1.1 et le streaming HTTP/2 via PipedInputStream.
 */
class StreamingBodyTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void streamingContentLengthIsMinusOne() throws IOException {
        var pis = new PipedInputStream();
        new PipedOutputStream(pis); // évite BrokenPipeException si on appelle contentLength
        assertEquals(-1, Body.streaming(pis).contentLength());
    }

    /**
     * HTTP/1.1 keep-alive → Transfer-Encoding: chunked
     * On vérifie le header et le contenu décodé.
     */
    @Test
    void streamingBodySentAsChunkedHttp11() throws IOException {
        var events = List.of("data: event1\n\n", "data: event2\n\n", "data: event3\n\n");

        server = Server.builder()
                .port(0)
                .handler(_ -> {
                    try {
                        var pis = new PipedInputStream(8192);
                        var pos = new PipedOutputStream(pis);
                        Thread.startVirtualThread(() -> {
                            try (pos) {
                                for (var event : events) {
                                    pos.write(event.getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (IOException _) {
                            }
                        });
                        return Response.builder()
                                .status(StatusCode.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Body.streaming(pis))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        server.start();

        // HTTP/1.1 keep-alive par défaut → chunked transfer encoding
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String headers = readHeaders(in);
            assertTrue(
                    headers.contains("Transfer-Encoding: chunked"),
                    "Doit utiliser Transfer-Encoding: chunked, headers reçus:\n" + headers);
            assertTrue(headers.contains("text/event-stream"), "Doit avoir Content-Type: text/event-stream");

            String body = readChunkedBody(in);
            assertEquals(String.join("", events), body, "Corps décodé doit contenir tous les events SSE");
        }
    }

    /**
     * HTTP/2 : le body streamé arrive en DATA frames successives.
     */
    @Test
    void streamingBodyHttp2() throws IOException, InterruptedException {
        var events = List.of("data: e1\n\n", "data: e2\n\n");

        server = Server.builder()
                .port(0)
                .handler(_ -> {
                    try {
                        var pis = new PipedInputStream(8192);
                        var pos = new PipedOutputStream(pis);
                        Thread.startVirtualThread(() -> {
                            try (pos) {
                                for (var event : events) {
                                    pos.write(event.getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (IOException _) {
                            }
                        });
                        return Response.builder()
                                .status(StatusCode.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Body.streaming(pis))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        server.start();

        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/"))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(String.join("", events), response.body());
    }

    // --- Helpers de parsing HTTP brut ---

    /** Lit les headers HTTP/1.1 jusqu'au double CRLF. */
    private String readHeaders(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int len = sb.length();
            if (len >= 4
                    && sb.charAt(len - 4) == '\r'
                    && sb.charAt(len - 3) == '\n'
                    && sb.charAt(len - 2) == '\r'
                    && sb.charAt(len - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    /** Lit et décode un body en chunked transfer depuis un InputStream. */
    private String readChunkedBody(InputStream in) throws IOException {
        var result = new StringBuilder();
        while (true) {
            // Lire la ligne de taille du chunk (hex)
            var sizeLine = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                sizeLine.append((char) b);
            }
            String sizeStr = sizeLine.toString().trim();
            if (sizeStr.isEmpty()) continue;
            // Ignorer les extensions de chunk (";ext=val")
            int semicolon = sizeStr.indexOf(';');
            if (semicolon >= 0) sizeStr = sizeStr.substring(0, semicolon);
            int chunkSize = Integer.parseInt(sizeStr.trim(), 16);
            if (chunkSize == 0) break;
            // Lire exactement chunkSize octets
            byte[] data = in.readNBytes(chunkSize);
            result.append(new String(data, StandardCharsets.UTF_8));
            // Consommer le CRLF après les données
            in.read(); // \r
            in.read(); // \n
        }
        return result.toString();
    }
}
