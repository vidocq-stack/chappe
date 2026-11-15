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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.http.HttpRequestImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * RFC 6455 §5.5.1: once the Close frame is sent, no data frame follows it. A send that checked the
 * session open before a concurrent {@code close()} took the write lock must not write its frame
 * after the Close frame once it gets the lock, nor may a ping or a pong of the application: once the Close
 * frame went out, no write of the application holds the write lock. The automatic Pong still answers a Ping
 * of the peer until the peer's Close arrives (RFC 6455 §5.5.2). An abort racing the closing handshake does not
 * hide how it ended. A {@code sendClose()} tells whether it sent the Close frame, so its caller knows which
 * side closed first.
 */
class WebSocketCloseRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String BYE = "bye";

    @Test
    void onlyTheFirstSendCloseSendsTheCloseFrame() throws IOException {
        var channel = GatedChannel.open(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});

        assertTrue(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE), "the first sendClose sends the Close frame");
        assertFalse(ws.sendClose(CloseCodes.GOING_AWAY, "again"), "a Close frame went out before the second");
        ws.close();
        assertArrayEquals(closeFrame(CloseCodes.NORMAL_CLOSURE, BYE), channel.written(), "one Close frame");
    }

    /** {@code close} sends the Close frame as {@code sendClose} does; it only does not tell whether it sent it. */
    @Test
    void closeStillSendsTheCloseFrame() throws IOException {
        var channel = GatedChannel.open(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});

        ws.close(CloseCodes.GOING_AWAY, BYE);

        assertArrayEquals(closeFrame(CloseCodes.GOING_AWAY, BYE), channel.written(), "close sends the Close frame");
        assertFalse(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE), "the Close frame of close went out first");
    }

    /**
     * A {@code sendClose} interrupted while it waits for the write lock writes nothing: it throws an
     * {@code InterruptedIOException}, with the caller's interrupt status set again, and that close never started,
     * so a later one still sends the Close frame. A caller interrupted before it calls fails the same way, since
     * the lock is taken interruptibly.
     */
    @Test
    void anInterruptedSendCloseWritesNothing() throws Exception {
        var channel = new GatedChannel(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});
        // A send holds the write lock, blocked in its write: the close queues behind it.
        var sender = Thread.ofVirtual().start(() -> sendQuietly(ws, "x"));
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the send must start its write");
        var failure = new CompletableFuture<IOException>();
        var stillInterrupted = new CompletableFuture<Boolean>();
        var closer = Thread.ofVirtual().start(() -> {
            try {
                ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE);
                failure.complete(null);
            } catch (IOException e) {
                failure.complete(e);
            }
            stillInterrupted.complete(Thread.currentThread().isInterrupted());
        });
        awaitParked(closer);

        closer.interrupt();

        assertTrue(closer.join(WAIT), "sendClose must return");
        assertInstanceOf(InterruptedIOException.class, failure.getNow(null), "the interrupted sendClose throws");
        assertTrue(stillInterrupted.getNow(false), "the caller's interrupt status is set again");
        channel.release.countDown();
        assertTrue(sender.join(WAIT), "sendText must return");
        assertArrayEquals(new byte[] {(byte) 0x81, 1, 'x'}, channel.written(), "the send only, no Close frame");
        assertTrue(ws.sendClose(CloseCodes.GOING_AWAY, BYE), "the interrupted close never started");
    }

    /** The read thread echoed the peer's Close: a close of this side sends nothing more. */
    @Test
    void aCloseAfterThePeersCloseWasEchoedSendsNothing() throws IOException {
        var channel = GatedChannel.open(maskedClose(CloseCodes.GOING_AWAY));
        var ws = connection(channel, new WebSocketHandler() {});
        ws.run();

        assertFalse(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE), "the echo of the peer's Close went out first");
        assertArrayEquals(closeFrame(CloseCodes.GOING_AWAY, ""), channel.written(), "only the echo");
    }

    @Test
    void aCloseAfterAnAbortSendsNothing() throws IOException {
        var channel = GatedChannel.open(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});
        ws.abort();

        assertFalse(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE), "the connection was aborted");
        assertArrayEquals(new byte[0], channel.written(), "no Close frame");
    }

    /**
     * A close queued on the write lock while the peer's Close is read leaves the answer to the read thread: the
     * peer closed first, so its Close is echoed, and this side's frame never goes out. Otherwise the caller would
     * hear that it sent the first Close although the peer's had arrived before it.
     */
    @Test
    void aCloseQueuedWhileThePeersCloseIsReadLeavesTheEcho() throws Exception {
        var channel = GatedChannel.heldInput(maskedClose(CloseCodes.GOING_AWAY));
        var closeCode = new CompletableFuture<Integer>();
        var ws = connection(channel, new WebSocketHandler() {
            @Override
            public void onClose(WebSocket connection, int code, String reason) {
                closeCode.complete(code);
            }
        });
        var session = Thread.ofVirtual().start(ws::run);
        // A send holds the write lock, blocked in its write: the close queues behind it.
        var sender = Thread.ofVirtual().start(() -> sendQuietly(ws, "x"));
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the send must start its write");
        var closed = new CompletableFuture<Boolean>();
        var closer = Thread.ofVirtual().start(() -> closed.complete(closeQuietly(ws)));
        awaitParked(closer);

        // The peer's Close arrives: the read thread queues its echo behind the close.
        channel.releaseInput();
        awaitClosing(ws);
        awaitParked(session);
        channel.release.countDown();

        assertTrue(closer.join(WAIT), "close() must return");
        assertTrue(sender.join(WAIT), "sendText must return");
        assertTrue(session.join(WAIT), "the session must end");
        assertFalse(closed.getNow(true), "the peer's Close was read before this close took the write lock");
        assertArrayEquals(
                concat(new byte[] {(byte) 0x81, 1, 'x'}, closeFrame(CloseCodes.GOING_AWAY, "")),
                channel.written(),
                "the send, then the echo of the peer's Close");
        assertEquals(CloseCodes.GOING_AWAY, closeCode.getNow(null), "onClose reports the peer's code");
    }

    /** What the application sends: a data frame, a ping or a pong, none of which may follow the Close frame. */
    enum Send {
        TEXT {
            @Override
            void send(WebSocketConnection ws) throws IOException {
                ws.sendText("late");
            }
        },
        PING {
            @Override
            void send(WebSocketConnection ws) throws IOException {
                ws.sendPing(ByteBuffer.wrap(new byte[] {'p'}));
            }
        },
        PONG {
            @Override
            void send(WebSocketConnection ws) throws IOException {
                ws.sendPong(ByteBuffer.wrap(new byte[] {'p'}));
            }
        };

        abstract void send(WebSocketConnection ws) throws IOException;
    }

    @ParameterizedTest
    @EnumSource(Send.class)
    void aSendRacingACloseNeverWritesAfterTheCloseFrame(Send send) throws Exception {
        var channel = new GatedChannel(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});

        var closer = Thread.ofVirtual().start(() -> closeQuietly(ws));
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the Close frame write must start");

        // The Close frame is being written under the write lock: the session still looks open.
        var sent = new CompletableFuture<Void>();
        var sender = Thread.ofVirtual().start(() -> {
            try {
                send.send(ws);
                sent.complete(null);
            } catch (IOException e) {
                sent.completeExceptionally(e);
            }
        });
        awaitParked(sender);
        channel.release.countDown();

        assertTrue(closer.join(WAIT), "close() must return");
        assertTrue(sender.join(WAIT), "the send must return");
        assertArrayEquals(
                new byte[] {(byte) 0x88, 5, 0x03, (byte) 0xE8, 'b', 'y', 'e'},
                channel.written(),
                "only the Close frame is on the wire");
        var failure = assertThrows(ExecutionException.class, sent::get);
        assertInstanceOf(IOException.class, failure.getCause());
    }

    /**
     * The peer's Ping read once this side's Close frame went out still gets its Pong: RFC 6455 §5.5.2 asks for one
     * unless the peer's Close was received, which comes after that Ping.
     */
    @Test
    void aPingReadOnceThisSidesCloseWentOutIsStillAnsweredWithAPong() throws IOException {
        var channel = GatedChannel.open(concat(maskedPing('p'), maskedClose(CloseCodes.NORMAL_CLOSURE)));
        var ws = connection(channel, new WebSocketHandler() {});

        assertTrue(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE), "this side closes first");
        ws.run();

        assertArrayEquals(
                concat(closeFrame(CloseCodes.NORMAL_CLOSURE, BYE), new byte[] {(byte) 0x8A, 1, 'p'}),
                channel.written(),
                "the Close, then the Pong of the peer's Ping");
    }

    /**
     * A ping that checked the session open before the Close frame went out, queued behind it on a peer that no
     * longer reads, writes nothing: a close queued behind that ping answers at once, so the handler's
     * {@code onClose}, which waits for that answer as Foy's session does, ends instead of waiting for a write that
     * only the end of the session would cut.
     */
    @Test
    void aPingQueuedBehindTheCloseFrameDoesNotHoldBackAQueuedClose() throws Exception {
        byte[] closeFrame = closeFrame(CloseCodes.NORMAL_CLOSURE, BYE);
        var channel = GatedChannel.stalledPeer(maskedClose(CloseCodes.GOING_AWAY), closeFrame.length);
        var answer = new CompletableFuture<Boolean>();
        var heardTheAnswer = new CompletableFuture<Boolean>();
        var ws = connection(channel, new WebSocketHandler() {
            @Override
            public void onClose(WebSocket connection, int code, String reason) {
                heardTheAnswer.complete(answered(answer));
            }
        });
        var session = Thread.ofVirtual().start(ws::run);
        var closer = Thread.ofVirtual().start(() -> closeQuietly(ws));
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the Close frame write must start");
        var pinger = Thread.ofVirtual().start(() -> sendQuietly(ws, Send.PING));
        awaitParked(pinger);
        var queued = Thread.ofVirtual().start(() -> answer.complete(closeQuietly(ws)));
        awaitParked(queued);

        channel.release.countDown(); // the Close frame goes out; the peer reads nothing from then on
        assertTrue(closer.join(WAIT), "close() must return");
        channel.releaseInput(); // the peer's Close arrives

        assertTrue(session.join(WAIT.multipliedBy(2)), "the session must end");
        assertTrue(heardTheAnswer.getNow(false), "onClose waited for a close queued behind a ping after the Close");
        assertFalse(answer.getNow(true), "the Close frame went out before the queued close");
        assertTrue(pinger.join(WAIT), "the ping must return");
        assertArrayEquals(closeFrame, channel.written(), "nothing after the Close");
    }

    /**
     * An abort that comes once the peer's Close frame arrived, while the session still writes its echo, does not
     * turn the end of the closing handshake into 1006: {@code onClose} reports the peer's code (CHAPPE-060).
     */
    @Test
    void anAbortAfterThePeersCloseKeepsItsCode() throws Exception {
        var channel = new GatedChannel(maskedClose(CloseCodes.GOING_AWAY));
        var closeCode = new CompletableFuture<Integer>();
        var ws = connection(channel, new WebSocketHandler() {
            @Override
            public void onClose(WebSocket connection, int code, String reason) {
                closeCode.complete(code);
            }
        });

        var session = Thread.ofVirtual().start(ws::run);
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the echo of the Close must start");
        ws.abort();

        assertTrue(session.join(WAIT), "the session must end");
        assertEquals(CloseCodes.GOING_AWAY, closeCode.getNow(null), "onClose reports the peer's code");
    }

    /**
     * A {@code sendClose()} whose Close frame write the abort cuts returns quietly: the connection is closed, which is
     * what it asked for (CHAPPE-060). It did not send the Close frame.
     */
    @Test
    void aCloseTheAbortCutsInItsWriteReturnsQuietly() throws Exception {
        var channel = new GatedChannel(new byte[0]);
        var ws = connection(channel, new WebSocketHandler() {});
        var closed = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> {
            try {
                closed.complete(ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE));
            } catch (IOException e) {
                closed.completeExceptionally(e);
            }
        });
        assertTrue(channel.firstWriteBlocked.await(WAIT.toSeconds(), SECONDS), "the Close frame write must start");

        ws.abort();

        var sent = assertDoesNotThrow(() -> closed.get(WAIT.toSeconds(), SECONDS), "sendClose() returns quietly");
        assertFalse(sent, "the abort cut the Close frame");
        assertArrayEquals(new byte[0], channel.written(), "the Close frame never reached the wire");
    }

    /** A session over {@code channel}, which is also what closes and aborts it. */
    private static WebSocketConnection connection(GatedChannel channel, WebSocketHandler handler) {
        return new WebSocketConnection(
                channel, channel, channel, ByteBuffer.allocate(64).flip(), handler, new HttpRequestImpl(), null);
    }

    /** The peer's Close frame carrying {@code code}, masked as a client frame must be, with a zero key. */
    private static byte[] maskedClose(int code) {
        return new byte[] {(byte) 0x88, (byte) 0x82, 0, 0, 0, 0, (byte) (code >>> 8), (byte) code};
    }

    /** The peer's Ping frame carrying {@code payload}, masked with a zero key. */
    private static byte[] maskedPing(char payload) {
        return new byte[] {(byte) 0x89, (byte) 0x81, 0, 0, 0, 0, (byte) payload};
    }

    /** The server's Close frame of {@code code} and {@code reason}, unmasked. */
    private static byte[] closeFrame(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        return concat(new byte[] {(byte) 0x88, (byte) (2 + text.length), (byte) (code >>> 8), (byte) code}, text);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /** Whether {@code ws.sendClose()} sent the Close frame; {@code false} as well when it threw. */
    private static boolean closeQuietly(WebSocketConnection ws) {
        try {
            return ws.sendClose(CloseCodes.NORMAL_CLOSURE, BYE);
        } catch (IOException _) {
            return false; // the written bytes tell what happened
        }
    }

    private static void sendQuietly(WebSocketConnection ws, String text) {
        try {
            ws.sendText(text);
        } catch (IOException _) {
            // the written bytes tell what happened
        }
    }

    private static void sendQuietly(WebSocketConnection ws, Send send) {
        try {
            send.send(ws);
        } catch (IOException _) {
            // the written bytes tell what happened
        }
    }

    /** Whether {@code answer} came within {@link #WAIT}. */
    private static boolean answered(CompletableFuture<Boolean> answer) {
        try {
            answer.get(WAIT.toSeconds(), SECONDS);
            return true;
        } catch (TimeoutException | ExecutionException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits until {@code thread} parks: queued on the session's write lock, or blocked in a read or a write. */
    private static void awaitParked(Thread thread) throws InterruptedException {
        awaitUntil(() -> thread.getState() == Thread.State.WAITING, "the thread never parked");
    }

    /** Waits until the session no longer reads as open: here, until the read thread marked the peer's Close. */
    private static void awaitClosing(WebSocketConnection ws) throws InterruptedException {
        awaitUntil(() -> !ws.isOpen(), "the peer's Close was never read");
    }

    private static void awaitUntil(BooleanSupplier condition, String failure) throws InterruptedException {
        var never = new CountDownLatch(1); // never counted down: each wait on it is a pause
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, failure);
            never.await(1, MILLISECONDS);
        }
    }

    /**
     * A channel whose first write blocks until {@link #release}; it records every byte written, and reads its
     * input, then EOF, once {@link #releaseInput()} let it. Like a socket, it fails a write once closed, the
     * blocked one included. A {@linkplain #stalledPeer stalled peer} blocks every write past its first bytes
     * until the channel closes, as a peer that stopped reading does.
     */
    private static final class GatedChannel implements ByteChannel {

        final CountDownLatch firstWriteBlocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch inputReleased;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ByteBuffer input;
        private final CountDownLatch closing = new CountDownLatch(1);
        /** The bytes the peer reads before it stops reading; past them, a write blocks until the channel closes. */
        private final int readBytes;

        private volatile boolean closed;

        /** A channel whose first write blocks and whose input is there at once. */
        GatedChannel(byte[] input) {
            this(input, 0);
        }

        private GatedChannel(byte[] input, int heldReads) {
            this(input, heldReads, Integer.MAX_VALUE);
        }

        private GatedChannel(byte[] input, int heldReads, int readBytes) {
            this.input = ByteBuffer.wrap(input);
            this.inputReleased = new CountDownLatch(heldReads);
            this.readBytes = readBytes;
        }

        /** A channel whose writes never block. */
        static GatedChannel open(byte[] input) {
            var channel = new GatedChannel(input);
            channel.firstWriteBlocked.countDown();
            return channel;
        }

        /** A channel whose first write blocks and whose reads wait for {@link #releaseInput()}. */
        static GatedChannel heldInput(byte[] input) {
            return new GatedChannel(input, 1);
        }

        /**
         * A channel whose first write blocks until {@link #release}, whose writes past the first {@code readBytes}
         * bytes block until it closes, and whose reads wait for {@link #releaseInput()}.
         */
        static GatedChannel stalledPeer(byte[] input, int readBytes) {
            return new GatedChannel(input, 1, readBytes);
        }

        void releaseInput() {
            inputReleased.countDown();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (firstWriteBlocked.getCount() > 0) {
                firstWriteBlocked.countDown();
                awaitRelease();
            }
            if (bytes.size() >= readBytes) {
                await(closing, WAIT.multipliedBy(3)); // longer than any wait of a test on what it holds back
            }
            if (closed) throw new AsynchronousCloseException();
            int n = src.remaining();
            var chunk = new byte[n];
            src.get(chunk);
            bytes.write(chunk, 0, n); // ByteArrayOutputStream is synchronized
            return n;
        }

        private void awaitRelease() throws IOException {
            await(release, WAIT);
        }

        private static void await(CountDownLatch latch, Duration bound) throws IOException {
            try {
                if (!latch.await(bound.toMillis(), MILLISECONDS)) throw new IOException("never released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        byte[] written() {
            return bytes.toByteArray();
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            await(inputReleased, WAIT);
            if (!input.hasRemaining()) return -1;
            int n = Math.min(dst.remaining(), input.remaining());
            dst.put(input.slice(input.position(), n));
            input.position(input.position() + n);
            return n;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        /** Closes the channel and wakes the blocked write, which then fails. */
        @Override
        public void close() {
            closed = true;
            release.countDown();
            closing.countDown();
        }
    }
}
