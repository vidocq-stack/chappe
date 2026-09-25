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
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TLS integration tests — HTTPS with a self-signed certificate.
 * <p>
 * Covers HTTP/1.1 over TLS, multiple keep-alive requests,
 * and HTTP/2 via ALPN negotiation.
 */
class TlsTest {

    private static Path keystorePath;
    private static SSLContext serverSslContext;
    private static SSLContext trustAllContext;

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll
    static void generateKeystore() throws Exception {
        // Generate a self-signed keystore via keytool
        keystorePath = Files.createTempFile("chappe-test-", ".p12");
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

        // Load keystore on server side
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(keystorePath)) {
            keyStore.load(is, "changeit".toCharArray());
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());

        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        // Client SSLContext that trusts everything
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
                .handler(_ -> Response.ok("Hello TLS!"))
                .build();
        server.start();
        baseUrl = "https://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(trustAllContext)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void httpsGetRequest() throws IOException, InterruptedException {
        var response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("Hello TLS!", response.body());
    }

    @Test
    void httpsMultipleRequests() throws IOException, InterruptedException {
        for (int i = 0; i < 5; i++) {
            var response = get("/");
            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void httpsH2ViaAlpn() throws IOException, InterruptedException {
        var h2Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustAllContext)
                .build();
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/")).GET().build();
        var response = h2Client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Hello TLS!", response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // -------------------------------------------------------------------------
    // Regression: CHAPPE-004 (bug C) — application data coalesced with the final
    // TLS handshake flight must not deadlock the server.
    // -------------------------------------------------------------------------

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    /**
     * Regression for the {@code SslHandler.readInternal} deadlock: when a client sends
     * its final handshake flight and the first application record in a single TCP
     * segment, {@code doHandshake()} leaves the encrypted request buffered in
     * {@code netInBuffer}. The old code called {@code channel.read()} before unwrapping
     * that buffer, so the server blocked forever waiting for bytes the client (now
     * waiting for the response) would never send.
     * <p>
     * We drive an {@link SSLEngine} by hand so we can deliberately coalesce the client
     * Finished with the wrapped HTTP request into one socket write, then assert the
     * server still answers. {@code assertTimeoutPreemptively} turns a regression (the
     * server hanging) into a test failure instead of a hung build.
     */
    @Test
    void requestCoalescedWithHandshakeFlightDoesNotHang() throws Exception {
        String response = withThreadDumpOnTimeout(Duration.ofSeconds(10), this::sendCoalescedRequest);
        assertTrue(response.contains("200"), "expected a 200 status line, got:\n" + response);
        assertTrue(response.contains("Hello TLS!"), "expected the handler body, got:\n" + response);
    }

    /**
     * Runs {@code call} on a virtual thread and fails if it has not finished within
     * {@code timeout}. Before failing, it dumps every thread of the JVM, virtual threads
     * included, to the test output. The server runs in this JVM, so the dump shows
     * which side waits and on what. {@code ThreadMXBean} would miss the virtual threads,
     * hence {@code HotSpotDiagnosticMXBean.dumpThreads}. This is how CHAPPE-006 was found.
     */
    private static <T> T withThreadDumpOnTimeout(Duration timeout, Callable<T> call) throws Exception {
        var task = new FutureTask<>(call);
        Thread runner = Thread.ofVirtual().name("tls-test-client").start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Path dump = Files.createTempFile("chappe-tls-timeout-", ".txt");
            Files.delete(dump); // dumpThreads refuses an existing file
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(dump.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            System.err.println("=== thread dump on timeout (" + dump + ") ===");
            System.err.println(Files.readString(dump));
            runner.interrupt();
            return fail("execution timed out after " + timeout.toMillis() + " ms; thread dump above");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private String sendCoalescedRequest() throws Exception {
        SSLEngine engine = trustAllContext.createSSLEngine("127.0.0.1", server.port());
        engine.setUseClientMode(true);
        SSLParameters params = engine.getSSLParameters();
        params.setApplicationProtocols(new String[] {"http/1.1"});
        engine.setSSLParameters(params);

        int pkt = engine.getSession().getPacketBufferSize();
        int app = engine.getSession().getApplicationBufferSize();
        ByteBuffer netIn = ByteBuffer.allocate(pkt);
        ByteBuffer appIn = ByteBuffer.allocate(app);

        try (SocketChannel ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", server.port()))) {
            ch.configureBlocking(true);

            engine.beginHandshake();
            HandshakeStatus hs = engine.getHandshakeStatus();
            ByteBuffer heldFinalFlight = null; // the client Finished — deferred so we can coalesce it
            boolean needMoreBytes = true; // nothing buffered yet, or the last unwrap underflowed

            while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
                switch (hs) {
                    case NEED_WRAP -> {
                        ByteBuffer netOut = ByteBuffer.allocate(pkt);
                        SSLEngineResult r = engine.wrap(EMPTY, netOut);
                        netOut.flip();
                        hs = r.getHandshakeStatus();
                        if (hs == HandshakeStatus.FINISHED) {
                            heldFinalFlight = netOut; // don't send yet — coalesce with the request
                        } else {
                            writeFully(ch, netOut);
                        }
                    }
                    case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                        // Unwrap what is already buffered before reading (CHAPPE-006). The whole
                        // server flight often arrives in one read, and TLS 1.3 switches to
                        // NEED_WRAP right after the ServerHello (compatibility ChangeCipherSpec),
                        // leaving the rest of the flight buffered. Reading first then waits for
                        // bytes the server has already sent, while the server waits for us.
                        if (needMoreBytes || netIn.position() == 0) {
                            if (ch.read(netIn) < 0) {
                                throw new IOException("peer closed during client handshake");
                            }
                        }
                        netIn.flip();
                        SSLEngineResult r;
                        do {
                            appIn.clear();
                            r = engine.unwrap(netIn, appIn);
                            hs = r.getHandshakeStatus();
                            if (hs == HandshakeStatus.NEED_TASK) {
                                hs = runTasks(engine);
                            }
                        } while (netIn.hasRemaining()
                                && r.getStatus() == Status.OK
                                && (hs == HandshakeStatus.NEED_UNWRAP || hs == HandshakeStatus.NEED_UNWRAP_AGAIN));
                        needMoreBytes = r.getStatus() == Status.BUFFER_UNDERFLOW;
                        netIn.compact();
                    }
                    case NEED_TASK -> hs = runTasks(engine);
                    default -> throw new IOException("unexpected handshake status: " + hs);
                }
            }

            // Wrap the HTTP/1.1 request, then send [client Finished || request] in ONE write.
            byte[] request = ("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            ByteBuffer reqNet = ByteBuffer.allocate(pkt);
            engine.wrap(ByteBuffer.wrap(request), reqNet);
            reqNet.flip();

            int finalLen = heldFinalFlight != null ? heldFinalFlight.remaining() : 0;
            ByteBuffer coalesced = ByteBuffer.allocate(finalLen + reqNet.remaining());
            if (heldFinalFlight != null) {
                coalesced.put(heldFinalFlight);
            }
            coalesced.put(reqNet);
            coalesced.flip();
            writeFully(ch, coalesced);

            // Decrypt the response until the peer closes.
            StringBuilder sb = new StringBuilder();
            while (true) {
                int n = ch.read(netIn);
                if (n < 0) {
                    break;
                }
                netIn.flip();
                boolean progressed = true;
                while (netIn.hasRemaining() && progressed) {
                    appIn.clear();
                    SSLEngineResult r = engine.unwrap(netIn, appIn);
                    appIn.flip();
                    if (appIn.hasRemaining()) {
                        sb.append(StandardCharsets.US_ASCII.decode(appIn));
                    }
                    if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                        runTasks(engine);
                    }
                    if (r.getStatus() == Status.CLOSED) {
                        return sb.toString();
                    }
                    progressed = r.getStatus() == Status.OK;
                }
                netIn.compact();
            }
            return sb.toString();
        }
    }

    private static HandshakeStatus runTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
        return engine.getHandshakeStatus();
    }

    private static void writeFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }
}
