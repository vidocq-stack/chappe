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

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code ServerConfig.writeTimeout} on HTTP/2 (CHAPPE-029): HTTP/2 twin of {@link WriteTimeoutTest}.
 *
 * <p>Each flush of the connection's frame buffer is bounded by {@code writeTimeout}. A peer that
 * stops reading makes a flush stall, and the whole multiplexed connection is then closed. A slow
 * reader that keeps draining, and a connection with nothing to write, are not cut.</p>
 *
 * <p>Every test that sends a large body first opens the flow-control windows to their maximum:
 * otherwise the server would stop at 64 KiB in its wait for a WINDOW_UPDATE, never in the socket
 * write, and {@code writeTimeout} would not be exercised.</p>
 */
class Http2WriteTimeoutTest {

    private static final Duration WRITE_TIMEOUT = Duration.ofMillis(400);

    /**
     * The bound of the slow-reader test. On loopback, a reader that pauses lets the receive window
     * close, and the kernel then holds even a small write for about 420 ms (measured on macOS) before
     * it reopens: a 400 ms bound would cut a healthy reader there. 2 s stays far below the 6.4 s the
     * whole response takes, so a bound on the response instead of on each write would still cut it.
     */
    private static final Duration SLOW_READER_WRITE_TIMEOUT = Duration.ofSeconds(2);

    private static final int BIG = 32 * 1024 * 1024;
    private static final int MEDIUM = 4 * 1024 * 1024;
    private static final int ONE_MIB = 1024 * 1024;
    private static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
    private static final int MAX_WINDOW = Integer.MAX_VALUE; // 2^31 - 1
    private static final int DEFAULT_WINDOW = 65_535;

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void tls() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * A peer that sends a request and then reads nothing makes the server's flush stall; after
     * {@code writeTimeout} the server closes the connection, so the peer sees a truncated body
     * then EOF, and the server still serves new connections.
     */
    @Test
    void aPeerThatStopsReadingIsCut() throws Exception {
        startServer(null, WRITE_TIMEOUT);
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(2048);
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            assertCutAfterStall(socket);
        }
        assertSmallResponse(plainSocket());
    }

    /** The same over TLS (ALPN h2), where the writes go through the {@code SslHandler}. */
    @Test
    void aPeerThatStopsReadingIsCutOverTls() throws Exception {
        startServer(serverContext, WRITE_TIMEOUT);
        var socket = (SSLSocket) clientContext.getSocketFactory().createSocket();
        try (socket) {
            socket.setReceiveBufferSize(2048);
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            handshakeH2(socket);
            assertCutAfterStall(socket);
        }
        var second = (SSLSocket) clientContext.getSocketFactory().createSocket();
        second.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
        handshakeH2(second);
        assertSmallResponse(second);
    }

    /**
     * A reader that drains 64 KiB every 100 ms makes progress, so it is never cut, although the
     * whole response takes longer than {@code writeTimeout}.
     */
    @Test
    void aSlowButProgressingReaderIsNotCut() throws Exception {
        startServer(null, SLOW_READER_WRITE_TIMEOUT);
        // Default socket buffers: they fill well before 4 MiB, so the flushes do wait on this reader.
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            socket.setSoTimeout(5_000);
            var h2 = H2TestConnection.over(socket);
            requestWithOpenWindows(h2, "/medium");

            long received = 0;
            long sinceSleep = 0;
            boolean ended = false;
            while (!ended) {
                Frame f;
                try {
                    f = h2.read();
                } catch (EOFException e) {
                    throw new AssertionError("the slow reader was cut after " + received + " bytes", e);
                }
                if (f.type() == TYPE_GOAWAY || f.type() == TYPE_RST_STREAM) {
                    fail("the slow reader was cut after " + received + " bytes (frame type " + f.type() + ")");
                }
                if (f.streamId() != 1) continue;
                if (f.type() == TYPE_DATA) {
                    received += f.payload().length;
                    sinceSleep += f.payload().length;
                    if (sinceSleep >= 64 * 1024) {
                        sinceSleep = 0;
                        Thread.sleep(100);
                    }
                }
                ended = f.endStream();
            }
            assertEquals(MEDIUM, received, "the whole body arrives, up to END_STREAM");
        }
    }

    /** With nothing to write, no flush runs, so a quiet connection outlives several {@code writeTimeout}s. */
    @Test
    void aQuietConnectionIsNotCut() throws Exception {
        startServer(null, WRITE_TIMEOUT);
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, requestHeaders("GET", "/small"));
            h2.flush();
            assertEquals(new Reply("200", "small"), h2.reply(1));

            Thread.sleep(3 * WRITE_TIMEOUT.toMillis());

            h2.headers(3, true, requestHeaders("GET", "/small"));
            h2.flush();
            assertEquals(new Reply("200", "small"), h2.reply(3), "the quiet connection still serves requests");
        }
    }

    /**
     * A zero {@code writeTimeout} means no bound, as for the other timeouts: the flushes run
     * unbounded, where a watchdog armed with zero would close the connection at once.
     */
    @Test
    void aZeroWriteTimeoutIsNoBound() throws Exception {
        startServer(null, Duration.ZERO);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            socket.setSoTimeout(5_000);
            var h2 = H2TestConnection.over(socket);
            requestWithOpenWindows(h2, "/mib");

            long received = 0;
            boolean ended = false;
            while (!ended) {
                Frame f;
                try {
                    f = h2.read();
                } catch (EOFException e) {
                    throw new AssertionError("the connection was closed after " + received + " bytes", e);
                }
                if (f.type() == TYPE_GOAWAY || f.type() == TYPE_RST_STREAM) {
                    fail("the response was cut after " + received + " bytes (frame type " + f.type() + ")");
                }
                if (f.streamId() != 1) continue;
                if (f.type() == TYPE_DATA) received += f.payload().length;
                ended = f.endStream();
            }
            assertEquals(ONE_MIB, received, "the whole body arrives, up to END_STREAM");
        }
    }

    // -------------------------------------------------------------------------

    private void startServer(SSLContext tls, Duration writeTimeout) {
        byte[] big = new byte[BIG];
        byte[] medium = new byte[MEDIUM];
        byte[] mib = new byte[ONE_MIB];
        var builder = Server.builder()
                .port(0)
                .writeTimeout(writeTimeout)
                .idleTimeout(Duration.ofSeconds(30)) // keep the unrelated idle timeout out of the way
                .handler(req -> switch (req.path()) {
                    case "/big" -> octets(big);
                    case "/medium" -> octets(medium);
                    case "/mib" -> octets(mib);
                    default -> Response.ok("small");
                });
        if (tls != null) builder.tls(tls);
        server = builder.build();
        server.start();
    }

    private static Response octets(byte[] body) {
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", "application/octet-stream")
                .body(Body.of(body))
                .build();
    }

    private Socket plainSocket() throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
        return socket;
    }

    private static void handshakeH2(SSLSocket socket) throws IOException {
        var params = socket.getSSLParameters();
        params.setApplicationProtocols(new String[] {"h2"});
        socket.setSSLParameters(params);
        socket.setSoTimeout(5_000);
        socket.startHandshake();
        assertEquals("h2", socket.getApplicationProtocol(), "ALPN must select h2");
    }

    /**
     * Requests {@code /big}, reads nothing for 3 s, then drains to EOF: the server must have closed
     * the connection, so EOF arrives before the 10 s read timeout and with less than the body.
     */
    private void assertCutAfterStall(Socket socket) throws Exception {
        var h2 = H2TestConnection.over(socket);
        requestWithOpenWindows(h2, "/big");

        Thread.sleep(3_000); // read nothing: a flush stalls well past writeTimeout

        socket.setSoTimeout(10_000);
        long received = 0;
        try {
            while (true) {
                Frame f = h2.read();
                if (f.type() == TYPE_DATA) received += f.payload().length;
            }
        } catch (EOFException _) {
            // the server closed the connection
        } catch (SocketTimeoutException e) {
            fail("the server never closed the connection: the HTTP/2 write was not bounded (received " + received
                    + " bytes)");
        }
        assertTrue(received < BIG, "expected a truncated body, but received all " + received + " bytes");
    }

    /** Preface, SETTINGS with the largest stream window, the largest connection window, then a GET. */
    private static void requestWithOpenWindows(H2TestConnection h2, String path) throws IOException {
        h2.preface();
        h2.frame(
                TYPE_SETTINGS,
                0,
                0,
                ByteBuffer.allocate(6)
                        .putShort((short) SETTINGS_INITIAL_WINDOW_SIZE)
                        .putInt(MAX_WINDOW)
                        .array());
        h2.frame(
                TYPE_WINDOW_UPDATE,
                0,
                0,
                ByteBuffer.allocate(4).putInt(MAX_WINDOW - DEFAULT_WINDOW).array());
        h2.headers(1, true, requestHeaders("GET", path));
        h2.flush();
    }

    /** A new connection still gets a normal small response. */
    private static void assertSmallResponse(Socket socket) throws IOException {
        try (socket) {
            socket.setSoTimeout(5_000);
            var h2 = H2TestConnection.over(socket);
            h2.preface();
            h2.frame(TYPE_SETTINGS, 0, 0, new byte[0]);
            h2.headers(1, true, requestHeaders("GET", "/small"));
            h2.flush();
            assertEquals(new Reply("200", "small"), h2.reply(1));
        }
    }
}
