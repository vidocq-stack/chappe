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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * WebSocket writes are bounded by {@code writeTimeout} (CHAPPE-055): a peer that stops reading
 * fills the socket buffers, and the write that can no longer progress is cut instead of blocking
 * the session, and everything waiting for its write lock, forever.
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

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void aPeerThatStopsReadingIsCutByWriteTimeout() throws Exception {
        var sendFailed = new CountDownLatch(1);
        var closeCode = new CompletableFuture<Integer>();
        int port = start(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, Request handshake) {
                floodUntilCut(ws, new AtomicLong(NOT_SENDING));
                sendFailed.countDown();
            }

            @Override
            public void onClose(WebSocket ws, int code, String reason) {
                closeCode.complete(code);
            }
        });

        try (var peer = RawWebSocket.open(port, "/flood", Map.of(), RECEIVE_BUFFER)) {
            assertEquals(101, peer.status());
            assertTrue(sendFailed.await(10, SECONDS), "the stalled send must fail after writeTimeout");
            assertEquals(CloseCodes.ABNORMAL_CLOSURE, closeCode.get(10, SECONDS));
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

    private int start(WebSocketHandler handler) {
        var router = Router.builder().webSocket("/flood", handler).build();
        server = Server.builder()
                .port(0)
                .writeTimeout(Duration.ofSeconds(1))
                .handler(router)
                .build();
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
