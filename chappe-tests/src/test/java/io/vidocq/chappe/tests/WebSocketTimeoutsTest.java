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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * WebSocket writes are bounded by {@code writeTimeout} (CHAPPE-055): a peer that stops reading
 * fills the socket buffers, and the write that can no longer progress is cut instead of blocking
 * the session, and everything waiting for its write lock, forever. The bound is per 16 KiB slice of
 * progress, not per frame: over TLS too, a large frame to a peer that keeps reading is never cut.
 */
class WebSocketTimeoutsTest {

    /** One frame of the flood: a few of them fill the 4 KiB receive buffer and the server's send buffer. */
    private static final int FRAME_SIZE = 64 * 1024;

    /** A receive buffer the peer never drains. */
    private static final int RECEIVE_BUFFER = 4096;

    /** How long a send must stay blocked to count as stalled: well inside the 1 s write timeout. */
    private static final long STALL_NANOS = Duration.ofMillis(300).toNanos();

    /** The value of {@code inFlightSince} while no send is in flight. */
    private static final long NOT_SENDING = Long.MIN_VALUE;

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The frame of the slow-reader test: 24 MiB. Linux loopback buffers a good part of any frame
     * (send-buffer autotuning, {@code tcp_wmem} up to about 4 MiB, plus the receive buffer), so the
     * send returns early for a frame those buffers can absorb. At the paced rate below, what 4 to
     * 6 MiB of kernel buffering leaves unabsorbed still takes about 3 s to drain, well past
     * {@link #WRITE_TIMEOUT}.
     */
    private static final int LARGE_FRAME_SIZE = 24 * 1024 * 1024;

    /** The slow peer reads this much at a time... */
    private static final int SLOW_CHUNK = 64 * 1024;

    /**
     * ...then pauses this long: about 6.4 MB/s, so the frame takes about 3.9 s, and no pause comes
     * near {@link #WRITE_TIMEOUT}.
     */
    private static final Duration SLOW_PAUSE = Duration.ofMillis(10);

    /** The slow peer's receive buffer: small, so the server's writes wait on the peer's pace. */
    private static final int SLOW_RECEIVE_BUFFER = 64 * 1024;

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

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void aPeerThatStopsReadingIsCutByWriteTimeout(boolean tls) throws Exception {
        var sendFailed = new CountDownLatch(1);
        var closeCode = new CompletableFuture<Integer>();
        int port = start(
                new WebSocketHandler() {
                    @Override
                    public void onOpen(WebSocket ws, Request handshake) {
                        floodUntilCut(ws, new AtomicLong(NOT_SENDING));
                        sendFailed.countDown();
                    }

                    @Override
                    public void onClose(WebSocket ws, int code, String reason) {
                        closeCode.complete(code);
                    }
                },
                tls ? serverContext : null);

        try (var peer = RawWebSocket.open(tls ? clientContext : null, port, "/flood", Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            assertTrue(sendFailed.await(10, SECONDS), "the stalled send must fail after writeTimeout");
            assertEquals(CloseCodes.ABNORMAL_CLOSURE, closeCode.get(10, SECONDS));
        }
    }

    /**
     * Over TLS, {@code SslHandler} writes the whole buffer it is handed before it returns. A frame
     * handed whole to one bounded write would be cut after {@code writeTimeout} although the peer
     * never stops reading; written 16 KiB per bounded write, it arrives whole.
     */
    @Test
    void aSlowButSteadyPeerGetsALargeFrameWholeOverTls() throws Exception {
        var frame = new byte[LARGE_FRAME_SIZE];
        for (int i = 0; i < frame.length; i++) frame[i] = (byte) (i * 31);
        var sendNanos = new CompletableFuture<Long>();
        var closeCode = new CompletableFuture<Integer>();
        int port = start(
                new WebSocketHandler() {
                    @Override
                    public void onOpen(WebSocket ws, Request handshake) {
                        long start = System.nanoTime();
                        try {
                            ws.sendBinary(ByteBuffer.wrap(frame));
                            sendNanos.complete(System.nanoTime() - start);
                        } catch (IOException e) {
                            sendNanos.completeExceptionally(e);
                        }
                    }

                    @Override
                    public void onClose(WebSocket ws, int code, String reason) {
                        closeCode.complete(code);
                    }
                },
                serverContext);

        try (var peer = RawWebSocket.open(clientContext, port, "/flood", Map.of(), SLOW_RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            var received = assertDoesNotThrow(
                    () -> peer.readFrameSlowly(SLOW_CHUNK, SLOW_PAUSE), "a peer that keeps reading gets the frame");
            assertEquals(new RawWebSocket.Frame(0x2, frame), received);
            long sendMillis = Duration.ofNanos(sendNanos.get(5, SECONDS)).toMillis();
            assertTrue(
                    sendMillis > WRITE_TIMEOUT.toMillis(),
                    "the send must outlast writeTimeout, or nothing was tested: " + sendMillis + " ms");
            peer.sendMasked(0x8, new byte[] {0x03, (byte) 0xE8}); // Close 1000
            assertEquals(CloseCodes.NORMAL_CLOSURE, closeCode.get(5, SECONDS), "no 1006: the frame was not cut");
        }
    }

    @Test
    void closeDoesNotBlockBehindAStalledWrite() throws Exception {
        var inFlightSince = new AtomicLong(NOT_SENDING);
        var sendFailed = new CountDownLatch(1);
        var closeReturned = new CountDownLatch(1);
        int port = start(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, Request handshake) {
                Thread.ofVirtual().start(() -> closeOnceStalled(ws, inFlightSince, sendFailed, closeReturned));
                floodUntilCut(ws, inFlightSince);
                sendFailed.countDown();
            }
        });

        try (var peer = RawWebSocket.open(port, "/flood", Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            assertTrue(closeReturned.await(5, SECONDS), "close() must not wait for a write that never ends");
        }
    }

    @Test
    void aServerCloseWithoutThePeersCloseEndsAfterReadTimeout() throws Exception {
        var closeCode = new CompletableFuture<Integer>();
        int port = start(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, Request handshake) {
                try {
                    ws.close(CloseCodes.NORMAL_CLOSURE, "bye");
                } catch (IOException e) {
                    closeCode.completeExceptionally(e);
                }
            }

            @Override
            public void onClose(WebSocket ws, int code, String reason) {
                closeCode.complete(code);
            }
        });

        try (var peer = RawWebSocket.open(port, "/flood", Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            assertEquals(CloseCodes.NORMAL_CLOSURE, peer.readFrame().closeCode());
            // the peer never answers with its own Close
            assertEquals(CloseCodes.ABNORMAL_CLOSURE, closeCode.get(5, SECONDS));
            assertTrue(peer.awaitEof(Duration.ofSeconds(5)), "the connection must be closed");
        }
    }

    @Test
    void concurrentClosesSendOneCloseFrameAndThrowNothing() throws Exception {
        int threads = 16;
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var done = new CountDownLatch(threads);
        int port = start(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, Request handshake) {
                var go = new CountDownLatch(1);
                for (int i = 0; i < threads; i++) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            go.await();
                            ws.close(CloseCodes.NORMAL_CLOSURE, "bye");
                        } catch (Throwable t) {
                            failures.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                go.countDown();
            }
        });

        try (var peer = RawWebSocket.open(port, "/flood", Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            assertTrue(done.await(5, SECONDS), "every close() must return");
            int closeFrames = 0;
            try {
                while (true) {
                    if (peer.readFrame().opcode() == 0x8) closeFrames++;
                }
            } catch (IOException _) {
                // the connection ended after readTimeout: all frames were read
            }
            assertEquals(1, closeFrames, "exactly one Close frame");
            assertTrue(failures.isEmpty(), "close() threw: " + failures);
        }
    }

    private int start(WebSocketHandler handler) {
        return start(handler, null);
    }

    /** Starts the server, over TLS when {@code tls} is not {@code null}. */
    private int start(WebSocketHandler handler, SSLContext tls) {
        var router = Router.builder().webSocket("/flood", handler).build();
        var builder = Server.builder()
                .port(0)
                .writeTimeout(WRITE_TIMEOUT)
                .readTimeout(Duration.ofSeconds(1))
                .handler(router);
        if (tls != null) builder.tls(tls);
        server = builder.build();
        server.start();
        return server.port();
    }

    /** Sends 64 KiB frames until a send fails; {@code inFlightSince} holds the start of the send in flight. */
    private static void floodUntilCut(WebSocket ws, AtomicLong inFlightSince) {
        var frame = new byte[FRAME_SIZE];
        boolean sending = true;
        while (sending) {
            inFlightSince.set(System.nanoTime());
            try {
                ws.sendBinary(ByteBuffer.wrap(frame));
            } catch (IOException _) {
                sending = false;
            }
            inFlightSince.set(NOT_SENDING);
        }
    }

    /**
     * Waits until a send has been blocked for {@link #STALL_NANOS}, then closes the session from
     * this other thread. A blocked write gives no signal of its own: the wait polls, on a latch.
     * {@code close()} returning or throwing both count: the timed write may cut the connection
     * under close's own write.
     */
    private static void closeOnceStalled(
            WebSocket ws, AtomicLong inFlightSince, CountDownLatch sendFailed, CountDownLatch closeReturned) {
        try {
            boolean failed = false;
            while (!failed && !stalled(inFlightSince.get())) {
                failed = sendFailed.await(10, MILLISECONDS);
            }
            ws.close(CloseCodes.GOING_AWAY, "");
        } catch (IOException _) {
            // the connection was cut: close() did not block, which is what is checked
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        closeReturned.countDown();
    }

    private static boolean stalled(long since) {
        return since != NOT_SENDING && System.nanoTime() - since > STALL_NANOS;
    }
}
