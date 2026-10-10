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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

import io.vidocq.chappe.api.ConnectionUpgrade;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.UpgradedConnection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ConnectionUpgrade} over TLS: the upgraded streams go through the TLS layer, and
 * {@link UpgradedConnection#close()} must not block behind a writer stalled by a peer that
 * stopped reading (the TLS layer serialises writes under a lock).
 */
@Timeout(30)
class ConnectionUpgradeTlsTest {

    private static Path keystorePath;
    private static SSLContext serverSslContext;
    private static SSLContext trustAllContext;

    private Server server;

    @BeforeAll
    static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("chappe-upgrade-test-", ".p12");
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

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void upgradedStreamsWorkOverTls() throws Exception {
        start(conn -> {
            byte[] buf = conn.input().readNBytes(5);
            conn.output()
                    .write(("echo:" + new String(buf, StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII));
            conn.close();
        });

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write(("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\nHello")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
            assertEquals("echo:Hello", new String(in.readNBytes(10), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void closeWakesAWriterStalledByAPeerThatDoesNotRead() throws Exception {
        var stored = new AtomicReference<UpgradedConnection>();
        var written = new AtomicLong();
        var writerEnd = new AtomicReference<Throwable>();
        var writerDone = new CountDownLatch(1);
        start(conn -> {
            stored.set(conn);
            Thread.ofVirtual().start(() -> {
                byte[] chunk = new byte[16 * 1024];
                try {
                    while (true) {
                        conn.output().write(chunk);
                        written.addAndGet(chunk.length);
                    }
                } catch (Throwable t) {
                    writerEnd.set(t);
                } finally {
                    writerDone.countDown();
                }
            });
        });

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write(("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 101 "));
            ConnectionUpgradeTest.awaitStalled(written);
            assertEquals(1, writerDone.getCount(), "writer must be blocked, not finished");

            long t0 = System.nanoTime();
            stored.get().close();
            long closeMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(closeMs < 2000, "close() took " + closeMs + " ms");
            assertTrue(writerDone.await(2, TimeUnit.SECONDS), "close() must wake the stalled writer");
            assertInstanceOf(IOException.class, writerEnd.get());
        }
    }

    private void start(io.vidocq.chappe.api.UpgradeHandler handler) {
        var headers = Headers.builder()
                .add("Upgrade", "echo")
                .add("Connection", "Upgrade")
                .build();
        server = Server.builder()
                .port(0)
                .tls(serverSslContext)
                .handler(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, headers, handler))
                .build();
        server.start();
    }

    private SSLSocket connect() throws IOException {
        var socket = (SSLSocket) trustAllContext.getSocketFactory().createSocket("127.0.0.1", server.port());
        socket.setSoTimeout(5000);
        socket.startHandshake();
        return socket;
    }

    private static String readHead(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
                return sb.toString();
            }
        }
        throw new IOException("EOF before end of head: " + sb);
    }
}
