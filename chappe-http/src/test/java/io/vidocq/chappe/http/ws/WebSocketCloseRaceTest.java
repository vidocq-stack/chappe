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
package io.vidocq.chappe.http.ws;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.http.HttpRequestImpl;

import org.junit.jupiter.api.Test;

/**
 * RFC 6455 §5.5.1: once the Close frame is sent, no data frame follows it. A send that checked the
 * session open before a concurrent {@code close()} took the write lock must not write its frame
 * after the Close frame once it gets the lock.
 */
class WebSocketCloseRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void aDataSendRacingACloseNeverWritesAfterTheCloseFrame() throws Exception {
        var channel = new GatedChannel();
        var ws = new WebSocketConnection(
                channel,
                channel,
                channel,
                ByteBuffer.allocate(64),
                new WebSocketHandler() {},
                new HttpRequestImpl(),
                null);

        var closer = Thread.ofVirtual().start(() -> closeQuietly(ws));
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the Close frame write must start");

        // The Close frame is being written under the write lock: the session still looks open.
        var sent = new CompletableFuture<Void>();
        var sender = Thread.ofVirtual().start(() -> {
            try {
                ws.sendText("late");
                sent.complete(null);
            } catch (IOException e) {
                sent.completeExceptionally(e);
            }
        });
        awaitParked(sender);
        channel.release.countDown();

        assertTrue(closer.join(WAIT), "close() must return");
        assertTrue(sender.join(WAIT), "sendText must return");
        assertArrayEquals(
                new byte[] {(byte) 0x88, 5, 0x03, (byte) 0xE8, 'b', 'y', 'e'},
                channel.written(),
                "only the Close frame is on the wire");
        var failure = assertThrows(ExecutionException.class, sent::get);
        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static void closeQuietly(WebSocketConnection ws) {
        try {
            ws.close(CloseCodes.NORMAL_CLOSURE, "bye");
        } catch (IOException _) {
            // the written bytes tell what happened
        }
    }

    /** Waits until {@code sender} parks: past its open check, queued on the session's write lock. */
    private static void awaitParked(Thread sender) throws InterruptedException {
        var never = new CountDownLatch(1); // never counted down: each wait on it is a pause
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (sender.getState() != Thread.State.WAITING) {
            assertTrue(System.nanoTime() - deadline < 0, "the send never waited for the write lock");
            never.await(1, MILLISECONDS);
        }
    }

    /** A channel whose first write blocks until {@link #release}; it records every byte written, and reads EOF. */
    private static final class GatedChannel implements ByteChannel {

        final CountDownLatch firstWriteBlocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (firstWriteBlocked.getCount() > 0) {
                firstWriteBlocked.countDown();
                awaitRelease();
            }
            int n = src.remaining();
            var chunk = new byte[n];
            src.get(chunk);
            bytes.write(chunk, 0, n); // ByteArrayOutputStream is synchronized
            return n;
        }

        private void awaitRelease() throws IOException {
            try {
                if (!release.await(WAIT.toSeconds(), SECONDS)) throw new IOException("never released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        byte[] written() {
            return bytes.toByteArray();
        }

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
