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
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The TLS handshake is bounded by {@code idleTimeout}, as one total deadline (CHAPPE-028). Before,
 * {@code SslHandler.doHandshake()} read without any bound: a client that connected and never
 * finished its handshake held its virtual thread forever (same class as CHAPPE-005).
 *
 * <p>Each assertion waits for the server's close with a client {@code SO_TIMEOUT} well above the
 * bound under test, so a close seen before it is the server's, not the client's.
 */
class TlsHandshakeTimeoutTest {

    private static final Duration IDLE = Duration.ofMillis(500);

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void contexts() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private int start(Duration idleTimeout) {
        server = Server.builder()
                .host("127.0.0.1")
                .port(0)
                .tls(serverContext)
                .idleTimeout(idleTimeout)
                .writeTimeout(Duration.ofSeconds(5))
                .handler(req -> {
                    if ("/slow".equals(req.path())) {
                        // Spans the handshake deadline inside the handler: no keep-alive gap is timed
                        try {
                            Thread.sleep(idleTimeout.multipliedBy(2));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return Response.ok("ok");
                })
                .build();
        server.start();
        return server.port();
    }

    /** A connect-only client (slowloris at the TLS layer) is closed at the deadline. */
    @Test
    void aClientThatNeverSendsAClientHelloIsClosed() throws IOException {
        int port = start(IDLE);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            long start = System.nanoTime();
            int eof = socket.getInputStream().read();
            long elapsedMs = elapsedMs(start);
            assertEquals(-1, eof, "the server must close a client that never sends its ClientHello");
            assertTrue(elapsedMs >= 400, "closed by the handshake deadline, not earlier (" + elapsedMs + " ms)");
            assertTrue(elapsedMs < 4500, "closed by the server, not by the client SO_TIMEOUT (" + elapsedMs + " ms)");
        }
    }

    /** A ClientHello cut short leaves the engine waiting for the rest: the deadline still fires. */
    @Test
    void aPartialClientHelloIsClosed() throws Exception {
        int port = start(IDLE);
        byte[] hello = clientHello(port);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(hello, 0, 15);
            out.flush();
            long start = System.nanoTime();
            int eof = socket.getInputStream().read();
            long elapsedMs = elapsedMs(start);
            assertEquals(-1, eof, "the server must close a client that sends half a ClientHello");
            assertTrue(elapsedMs < 4500, "closed by the server, not by the client SO_TIMEOUT (" + elapsedMs + " ms)");
        }
    }

    /**
     * One byte every 100 ms: a per-read timer of 500 ms would never fire, since every read
     * completes. The deadline covers the whole handshake, so the client is cut long before its
     * ClientHello is complete.
     */
    @Test
    void aDripFeedingClientIsStillCutAtTheDeadline() throws Exception {
        int port = start(IDLE);
        byte[] hello = clientHello(port);
        var sent = new AtomicInteger();
        var done = new AtomicBoolean();
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            long start = System.nanoTime();
            Thread writer = Thread.ofVirtual().name("drip-feeder").start(() -> {
                try {
                    for (byte b : hello) {
                        if (done.get()) return;
                        out.write(b);
                        out.flush();
                        sent.incrementAndGet();
                        Thread.sleep(100);
                    }
                } catch (IOException | InterruptedException _) {
                    // the server closed the connection, or the test ended
                }
            });
            try {
                boolean closed = awaitServerClose(socket.getInputStream());
                long elapsedMs = elapsedMs(start);
                int sentAtClose = sent.get();
                assertTrue(closed, "the server must close a drip-feeding client");
                assertTrue(elapsedMs >= 400, "closed by the handshake deadline, not earlier (" + elapsedMs + " ms)");
                assertTrue(
                        sentAtClose < hello.length,
                        "cut before the whole ClientHello was sent (" + sentAtClose + "/" + hello.length + ")");
                assertTrue(
                        elapsedMs < IDLE.toMillis() + 4000,
                        "closed by the handshake deadline, not by the client SO_TIMEOUT (" + elapsedMs + " ms)");
            } finally {
                done.set(true);
                writer.interrupt();
                assertTrue(writer.join(Duration.ofSeconds(5)), "the drip feeder must stop");
            }
        }
    }

    /**
     * A handshake that completes disarms the deadline. A {@code /slow} request, sent right after the
     * handshake, keeps the server busy for twice the deadline before it answers: a watchdog left
     * armed would close the socket in the middle of it. No keep-alive gap is timed, so the only
     * time-sensitive window is the handshake itself.
     */
    @Test
    void aHandshakeThatCompletesIsNotCut() throws Exception {
        // A wider bound than the others: a cold first handshake must complete well within it.
        Duration idle = Duration.ofSeconds(1);
        int port = start(idle);
        try (var socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            assertTrue(
                    get(out, in, "/slow").startsWith("HTTP/1.1 200"),
                    "a response that ends past the handshake deadline: the watchdog must be disarmed");
            assertTrue(get(out, in, "/").startsWith("HTTP/1.1 200"), "an immediate keep-alive request");
        }
    }

    /**
     * Plaintext on the TLS port: the engine rejects it at once, and the connection ends within the
     * 2 s lingering budget of the rejected-handshake path (CHAPPE-027). A smoke test only: the
     * engine may queue no alert for plaintext, so it proves neither the alert nor the linger.
     */
    @Test
    void plaintextOnTheTlsPortEndsWithinTheLingerBudget() throws IOException {
        int port = start(IDLE);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long start = System.nanoTime();
            InputStream in = socket.getInputStream();
            int b;
            do {
                b = in.read(); // an alert record may come first
            } while (b != -1);
            long elapsedMs = elapsedMs(start);
            assertTrue(elapsedMs < 2000 + 4000, "closed within the lingering budget (" + elapsedMs + " ms)");
        }
    }

    // -------------------------------------------------------------------------

    /** A real ClientHello, as the first flight of a client-mode engine. */
    private static byte[] clientHello(int port) throws IOException {
        SSLEngine engine = clientContext.createSSLEngine("127.0.0.1", port);
        engine.setUseClientMode(true);
        engine.beginHandshake();
        ByteBuffer buf = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        engine.wrap(ByteBuffer.allocate(0), buf);
        buf.flip();
        byte[] hello = Arrays.copyOf(buf.array(), buf.remaining());
        assertTrue(hello.length > 100, "a ClientHello is well over 100 bytes: " + hello.length);
        return hello;
    }

    /**
     * Waits for the server's close: EOF, or a reset (a byte the client dripped in after the
     * server's close may make the stack answer with one). A client {@code SO_TIMEOUT} is a failure.
     */
    private static boolean awaitServerClose(InputStream in) throws IOException {
        try {
            return in.read() == -1;
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (SocketException _) {
            return true;
        }
    }

    private static String get(OutputStream out, InputStream in, String path) throws IOException {
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readOneResponse(in);
    }

    private static String readOneResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int headerEnd = sb.indexOf("\r\n\r\n");
            if (headerEnd >= 0) {
                String headers = sb.substring(0, headerEnd + 4);
                var clIdx = headers.toLowerCase().indexOf("content-length: ");
                int contentLength = 0;
                if (clIdx >= 0) {
                    var clEnd = headers.indexOf("\r\n", clIdx);
                    contentLength = Integer.parseInt(
                            headers.substring(clIdx + 16, clEnd).trim());
                }
                if (sb.length() - (headerEnd + 4) >= contentLength) return sb.toString();
            }
        }
        return sb.toString();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
