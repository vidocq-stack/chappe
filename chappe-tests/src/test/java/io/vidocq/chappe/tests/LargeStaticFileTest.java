package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.SplittableRandom;

import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Régression : un client qui drain lentement ne doit pas recevoir une réponse
 * tronquée. Le passage zero-copy {@code FileChannel.transferTo(SocketChannel)}
 * peut renvoyer 0 quand le {@code SO_SNDBUF} kernel sature (cf. JDK-8264762,
 * sendfile(2) sur Linux/macOS) — un break silencieux sur ce retour 0 truncate
 * la réponse alors que {@code Content-Length} annonce la taille complète, ce
 * qui fait bloquer le browser indéfiniment.
 */
class LargeStaticFileTest {

    private static final int FILE_SIZE = 8 * 1024 * 1024; // 8 MiB > SO_SNDBUF typique
    private static final int CLIENT_RCVBUF = 16 * 1024;
    private static final int CHUNK = 4096;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void largeFileIsServedIntactWhenClientDrainsSlowly(@TempDir Path root) throws Exception {
        byte[] payload = randomBytes(FILE_SIZE);
        Path big = root.resolve("big.bin");
        Files.write(big, payload);

        server = Server.builder().port(0).handler(StaticFileHandler.of(root)).build();
        server.start();

        byte[] received = drainSlowly(server.port(), "/big.bin", payload.length);

        assertEquals(payload.length, received.length, "réponse tronquée — vraisemblablement transferTo==0 silencieux");
        assertEquals(sha256(payload), sha256(received), "intégrité du fichier compromise");
        assertArrayEquals(payload, received);
    }

    private static byte[] drainSlowly(int port, String path, long expectedBodyLen) throws IOException {
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(CLIENT_RCVBUF);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(30_000);

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n" + "Host: localhost\r\n" + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            String headers = readHeaders(in);
            assertTrue(headers.startsWith("HTTP/1.1 200"), "expected 200, got headers:\n" + headers);
            long contentLength = parseContentLength(headers);
            assertEquals(expectedBodyLen, contentLength, "Content-Length divergent du fichier");

            byte[] body = new byte[(int) contentLength];
            int total = 0;
            byte[] buf = new byte[CHUNK];
            while (total < body.length) {
                int n;
                try {
                    n = in.read(buf, 0, Math.min(CHUNK, body.length - total));
                } catch (SocketException e) {
                    break;
                }
                if (n < 0) break;
                System.arraycopy(buf, 0, body, total, n);
                total += n;
                // Drain ralenti — sature le SO_SNDBUF côté serveur et force
                // sendfile(2) à observer EAGAIN-like → transferTo peut renvoyer 0.
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            byte[] truncated = new byte[total];
            System.arraycopy(body, 0, truncated, 0, total);
            return truncated;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        var sb = new StringBuilder(512);
        int prev = -1;
        int crlfRun = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("connexion fermée pendant lecture des headers");
            sb.append((char) c);
            if (c == '\n' && prev == '\r') {
                crlfRun++;
                if (crlfRun == 2) return sb.toString();
            } else if (c != '\r') {
                crlfRun = 0;
            }
            prev = c;
        }
    }

    private static long parseContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                return Long.parseLong(line.substring(colon + 1).trim());
            }
        }
        throw new AssertionError("Content-Length absent");
    }

    private static byte[] randomBytes(int size) {
        var rng = new SplittableRandom(0xC0FFEEL);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) out[i] = (byte) rng.nextInt(256);
        return out;
    }

    private static String sha256(byte[] data) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
