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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
 * {@link WebSocket#abort()} cuts a connection at once (CHAPPE-060). A send blocked on a peer that does not
 * read holds the write lock {@code close()} needs, so before it nothing but the write timeout, or the peer,
 * ended such a connection. The server's timeouts here are far longer than any wait of the tests: what ends
 * the connection is the abort.
 */
class WebSocketAbortTest {

    /** A receive buffer the peer never drains. */
    private static final int RECEIVE_BUFFER = 4096;

    /** How long a send must stay blocked to count as stalled. */
    private static final Duration STALL = Duration.ofMillis(300);

    /** The server's read and write timeouts: they end no connection of these tests. */
    private static final Duration SERVER_TIMEOUT = Duration.ofMinutes(1);

    /** The bound of every wait of these tests, in seconds. */
    private static final long WAIT_SECONDS = 5;

    private static final String PATH = "/ws";

    private static final int OP_CLOSE = 0x8;

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
    void abortCutsAConnectionWhoseSendIsBlockedOnAPeerThatDoesNotRead(boolean tls) throws Exception {
        var session = new Session(new WebSocketFlood());
        int port = start(session, tls ? serverContext : null);

        try (var peer = RawWebSocket.open(tls ? clientContext : null, port, PATH, Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            WebSocket ws = session.opened.get(WAIT_SECONDS, SECONDS);
            session.awaitWhileFlooding(() -> session.flood.stalled(STALL), "a send blocked");
            // A shutdown's close, queued on the write lock the blocked send holds.
            var closed = new CompletableFuture<Void>();
            Thread closer = Thread.ofVirtual().start(() -> closeGoingAway(ws, closed));
            session.awaitWhileFlooding(() -> closer.getState() == Thread.State.WAITING, "close() queued");

            ws.abort();

            assertNotNull(session.sendFailure.get(WAIT_SECONDS, SECONDS), "the blocked send fails at once");
            var closeFailure = assertThrows(ExecutionException.class, () -> closed.get(WAIT_SECONDS, SECONDS));
            assertInstanceOf(IOException.class, closeFailure.getCause(), "the queued close() fails at once");
            assertFalse(ws.isOpen(), "an aborted connection is not open");
            assertEquals(CloseCodes.ABNORMAL_CLOSURE, session.closeCode.get(WAIT_SECONDS, SECONDS));
            assertFalse(peer.readOpcodesUntilEof().contains(OP_CLOSE), "the peer reads EOF, after no Close frame");
        }
        assertEndedOnce(session);
    }

    @Test
    void abortIsIdempotentAndRacesAClose() throws Exception {
        var session = new Session(null);
        int port = start(session, null);

        try (var peer = RawWebSocket.open(port, PATH, Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            WebSocket ws = session.opened.get(WAIT_SECONDS, SECONDS);

            ws.close(CloseCodes.NORMAL_CLOSURE, "");
            ws.abort();
            ws.abort();

            // The peer never answered the Close: the abort ended the closing handshake.
            assertEquals(CloseCodes.ABNORMAL_CLOSURE, session.closeCode.get(WAIT_SECONDS, SECONDS));
            ws.abort();
            assertEquals(List.of(OP_CLOSE), peer.readOpcodesUntilEof(), "the Close frame of close(), then EOF");
        }
        assertEndedOnce(session);
    }

    private static void closeGoingAway(WebSocket ws, CompletableFuture<Void> closed) {
        try {
            ws.close(CloseCodes.GOING_AWAY, "");
            closed.complete(null);
        } catch (IOException e) {
            closed.completeExceptionally(e);
        }
    }

    /**
     * Stops the server, which waits for its connections' threads, then checks that the session reported its
     * end once and no error: the read the abort cut is not an error of the session.
     */
    private void assertEndedOnce(Session session) {
        server.stop();
        assertEquals(1, session.closes.get(), "onClose runs once");
        assertTrue(session.errors.isEmpty(), "no onError: " + session.errors);
    }

    /** Starts the server, over TLS when {@code tls} is not {@code null}. */
    private int start(WebSocketHandler handler, SSLContext tls) {
        var builder = Server.builder()
                .port(0)
                .writeTimeout(SERVER_TIMEOUT)
                .readTimeout(SERVER_TIMEOUT)
                .handler(Router.builder().webSocket(PATH, handler).build());
        if (tls != null) builder.tls(tls);
        server = builder.build();
        server.start();
        return server.port();
    }

    /** A handler that records what the connection reports; with a flood, its {@code onOpen} floods the peer. */
    private static final class Session implements WebSocketHandler {

        final CompletableFuture<WebSocket> opened = new CompletableFuture<>();
        final CompletableFuture<IOException> sendFailure = new CompletableFuture<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        final WebSocketFlood flood;
        private final CountDownLatch floodEnded = new CountDownLatch(1);

        Session(WebSocketFlood flood) {
            this.flood = flood;
        }

        @Override
        public void onOpen(WebSocket ws, Request handshake) {
            opened.complete(ws);
            if (flood != null) {
                sendFailure.complete(flood.untilCut(ws));
                floodEnded.countDown();
            }
        }

        @Override
        public void onClose(WebSocket ws, int code, String reason) {
            closes.incrementAndGet();
            closeCode.complete(code);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            errors.add(error);
        }

        /**
         * Waits until {@code condition} holds while the flood goes on. A blocked write or a parked thread gives no
         * signal of its own: the wait polls, on the latch of the flood's end.
         */
        void awaitWhileFlooding(BooleanSupplier condition, String what) throws InterruptedException {
            long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
            while (!condition.getAsBoolean()) {
                assertTrue(System.nanoTime() - deadline < 0, "never happened: " + what);
                assertFalse(floodEnded.await(10, MILLISECONDS), "the flood ended before: " + what);
            }
        }
    }
}
