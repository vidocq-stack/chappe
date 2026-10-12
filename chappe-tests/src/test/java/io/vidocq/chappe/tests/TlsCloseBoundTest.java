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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.time.Duration;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.http.SslHandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The close_notify of {@link SslHandler#close()} stays bounded when the server's {@code writeTimeout}
 * means "no bound" for data writes ({@code null}, zero or negative). Before, such a value made the
 * close_notify write plain: a peer that stopped reading, with the send buffer full, pinned the
 * closing thread (and the connection's buffers) until {@code stop()} force-closed the socket.
 */
class TlsCloseBoundTest {

    /** The close budget used when {@code writeTimeout} is not positive: the lingering budget, 2 s. */
    private static final long CLOSE_BUDGET_MS = 2000;

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    @BeforeAll
    static void contexts() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    static Stream<Arguments> unboundedWriteTimeouts() {
        return Stream.of(
                Arguments.of(Duration.ZERO), Arguments.of(Duration.ofSeconds(-1)), Arguments.of((Duration) null));
    }

    @ParameterizedTest
    @MethodSource("unboundedWriteTimeouts")
    void aPeerThatStopsReadingCannotPinTheClose(Duration writeTimeout) throws Exception {
        try (var listener =
                        ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                var client = (SSLSocket) clientContext.getSocketFactory().createSocket()) {
            client.setReceiveBufferSize(4096);
            client.connect(listener.getLocalAddress());
            SocketChannel server = listener.accept();
            // A fixed send buffer: the kernel cannot grow it later and accept the close_notify after all
            server.setOption(StandardSocketOptions.SO_SNDBUF, 8192);
            try (server) {
                SSLEngine engine = serverContext.createSSLEngine();
                engine.setUseClientMode(false);
                var ssl = new SslHandler(server, engine, writeTimeout);

                Thread clientHandshake = Thread.ofVirtual().start(() -> {
                    try {
                        client.startHandshake();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                ssl.doHandshake();
                assertTrue(clientHandshake.join(Duration.ofSeconds(10)), "the client handshake must end");

                // From here on the client reads nothing. A raw write far larger than both socket
                // buffers stalls on the full send buffer and holds the channel's write side, so the
                // close_notify write cannot be accepted however the kernel sizes its buffers.
                Thread stalledWriter = startStalledWriter(server);

                long start = System.nanoTime();
                Thread closer = Thread.ofVirtual().name("closer").start(() -> {
                    try {
                        ssl.close();
                    } catch (IOException _) {
                        // the outcome checked is that the thread ends and the socket is closed
                    }
                });
                boolean ended = closer.join(Duration.ofMillis(CLOSE_BUDGET_MS + 3000));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (!ended) {
                    server.close(); // release the pinned closer before failing
                    closer.join(Duration.ofSeconds(5));
                }
                assertTrue(ended, "close() must not block behind a peer that stopped reading (" + elapsedMs + " ms)");
                assertFalse(server.isOpen(), "the socket must be closed");
                assertTrue(
                        stalledWriter.join(Duration.ofSeconds(5)), "the closed socket must release the stalled write");
                assertTrue(
                        elapsedMs >= CLOSE_BUDGET_MS - 500,
                        "the close_notify write must have blocked until the close budget (" + elapsedMs + " ms)");
            }
        }
    }

    /**
     * Starts a raw blocking write of 32 MiB, far more than both socket buffers hold, and returns once
     * it has had time to stall. A blocking write returns after a partial write, hence the loop.
     */
    private static Thread startStalledWriter(SocketChannel channel) throws InterruptedException {
        Thread writer = Thread.ofVirtual().name("stalled-writer").start(() -> {
            try {
                ByteBuffer junk = ByteBuffer.allocate(32 * 1024 * 1024);
                while (junk.hasRemaining()) {
                    channel.write(junk);
                }
            } catch (IOException _) {
                // the close at the budget ends the stalled write
            }
        });
        assertFalse(writer.join(Duration.ofMillis(300)), "the raw write must stall: the client reads nothing");
        return writer;
    }
}
