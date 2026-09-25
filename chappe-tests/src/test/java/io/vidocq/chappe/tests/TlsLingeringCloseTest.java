/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lingering close over TLS (#21), the counterpart of {@link LingeringCloseTest}:
 * when the server ends an HTTPS/1.1 connection while TLS records it never read
 * are still waiting in its receive buffer, a plain close makes the TCP stack
 * answer with a reset, and the reset destroys the response the client has not
 * read yet.
 */
class TlsLingeringCloseTest {

    private static Path keystorePath;
    private static SSLContext serverSslContext;
    private static SSLContext trustAllContext;

    private Server server;
    private int port;

    @BeforeAll
    static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("chappe-linger-", ".p12");
        Files.delete(keystorePath); // keytool refuses to overwrite an existing file
        var process = new ProcessBuilder(
                        "keytool", "-genkeypair",
                        "-alias", "chappe",
                        "-keyalg", "RSA",
                        "-keysize", "2048",
                        "-validity", "1",
                        "-dname", "CN=localhost",
                        "-storetype", "PKCS12",
                        "-keystore", keystorePath.toString(),
                        "-storepass", "changeit",
                        "-keypass", "changeit",
                        "-ext", "san=ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        assertEquals(0, process.exitValue(), "keytool failed");

        var keyStore = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(keystorePath)) {
            keyStore.load(is, "changeit".toCharArray());
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        trustAllContext = SSLContext.getInstance("TLS");
        trustAllContext.init(
                null,
                new javax.net.ssl.TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] c, String a) {}

                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                    }
                },
                null);
    }

    @AfterAll
    static void cleanupKeystore() throws IOException {
        if (keystorePath != null) Files.deleteIfExists(keystorePath);
    }

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .tls(serverSslContext)
                .maxHeaderSize(8192)
                .handler(_ -> Response.ok("pong"))
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** The 431 must reach a client that sent more than the server reads before rejecting. */
    @Test
    void headerTooLargeResponseSurvivesUnreadRequestBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "X-Oversized: " + "X".repeat(16_384) + "\r\n"
                + "X-More: " + "Y".repeat(64 * 1024) + "\r\n"
                + "\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 431"), "expected the 431 response, got: " + head(response));
    }

    /** Same for a 400 sent after the headers were parsed, with a pipelined request left unread. */
    @Test
    void badRequestResponseSurvivesPipelinedBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\n\r\n" // no Host: 400
                + "GET / HTTP/1.1\r\nHost: localhost\r\nX-Pad: " + "Z".repeat(64 * 1024) + "\r\n\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 400"), "expected the 400 response, got: " + head(response));
    }

    /** A Connection: close response must reach a client that pipelined another request behind it. */
    @Test
    void connectionCloseResponseSurvivesPipelinedBytes() throws Exception {
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\nX-Pad: " + "Z".repeat(64 * 1024) + "\r\n\r\n";

        String response = sendThenRead(request);

        assertTrue(response.startsWith("HTTP/1.1 200"), "expected the 200 response, got: " + head(response));
        assertTrue(response.endsWith("pong"), "expected the whole body, got: " + head(response));
    }

    /** Lingering is bounded: a client that never stops sending is cut off, not served forever. */
    @Test
    void lingeringIsBoundedForAClientThatKeepsSending() throws Exception {
        try (var socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: localhost\r\nX-Oversized: " + "X".repeat(16_384) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            long start = System.nanoTime();
            byte[] chunk = "X".repeat(1024).getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class,
                    () -> {
                        while (System.nanoTime() - start < 15_000_000_000L) {
                            out.write(chunk);
                            out.flush();
                            Thread.sleep(20);
                        }
                    },
                    "the server must eventually close a connection that keeps sending after an error");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 8_000, "lingering must be bounded, took " + elapsedMs + " ms");
        }
    }

    private SSLSocket connect() throws IOException {
        var socket = (SSLSocket) trustAllContext.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        return socket;
    }

    /**
     * Writes the whole request at once and gives the server time to answer and close.
     * Then it reads the response over TLS up to the server's close_notify, and reads the
     * raw TCP socket to its end. That end must be a FIN: a reset means the server closed
     * with request bytes still unread, and the same reset destroys the response whenever
     * it reaches the client first. Whether the TLS read wins that race depends on timing,
     * so the TCP end is what the test checks.
     */
    private String sendThenRead(String request) throws Exception {
        try (var raw = new Socket("127.0.0.1", port)) {
            raw.setSoTimeout(10_000);
            var tls = (SSLSocket) trustAllContext.getSocketFactory().createSocket(raw, "127.0.0.1", port, false);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(300); // the server answers and closes before the client reads

            String response = readToEof(tls.getInputStream());
            try {
                while (raw.getInputStream().read() != -1) {
                    // anything after close_notify is discarded
                }
            } catch (SocketException e) {
                fail("the server closed with a TCP reset instead of a FIN: " + e.getMessage() + " (response read: "
                        + head(response) + ")");
            }
            return response;
        }
    }

    private static String readToEof(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static String head(String response) {
        return response.length() <= 200 ? response : response.substring(0, 200) + "…";
    }
}
