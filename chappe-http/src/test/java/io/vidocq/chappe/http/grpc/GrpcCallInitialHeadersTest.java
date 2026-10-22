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
package io.vidocq.chappe.http.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcWebDispatch;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.http.ConnectionInfo;
import io.vidocq.chappe.http.h2.Http2Connection;
import io.vidocq.chappe.http.h2.Http2Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The initial headers of a gRPC call are settled once. Until they leave, {@code addHeader} and
 * {@code useResponseEncoding} change what they carry; once they have left, both throw. A thread that calls
 * them while another thread's {@code send} writes those headers must get one outcome or the other: never a
 * header dropped in between, nor a message compressed with a codec the headers do not announce (CHAPPE-039,
 * found by Infer's RacerD, Vidocq/vidocq#269).
 *
 * <p>The connection writes through a gate the test holds closed, so {@code send} stops inside the write of the
 * initial headers it has already built, and the other thread acts at that moment.
 */
class GrpcCallInitialHeadersTest {

    @Test
    @Timeout(10)
    void aHeaderAddedWhileSendWritesTheInitialHeadersIsRefused() throws Exception {
        var gate = new GatedChannel();
        var call = grpcCall(gate);
        assertRefusedWhileTheInitialHeadersLeave(gate, call, () -> call.addHeader("x-late", "1"));
    }

    @Test
    @Timeout(10)
    void aCodecChosenWhileSendWritesTheInitialHeadersIsRefused() throws Exception {
        var gate = new GatedChannel();
        var call = grpcCall(gate);
        assertRefusedWhileTheInitialHeadersLeave(gate, call, () -> call.useResponseEncoding("gzip"));
        assertEquals(0, gate.firstDataByte(), "the message leaves uncompressed, as the initial headers announced");
    }

    @Test
    @Timeout(10)
    void aHeaderAddedWhileSendWritesTheInitialHeadersIsRefusedInGrpcWeb() throws Exception {
        var gate = new GatedChannel();
        var call = grpcWebCall(gate);
        assertRefusedWhileTheInitialHeadersLeave(gate, call, () -> call.addHeader("x-late", "1"));
    }

    @Test
    @Timeout(10)
    void aCodecChosenWhileSendWritesTheInitialHeadersIsRefusedInGrpcWeb() throws Exception {
        var gate = new GatedChannel();
        var call = grpcWebCall(gate);
        assertRefusedWhileTheInitialHeadersLeave(gate, call, () -> call.useResponseEncoding("gzip"));
        assertEquals(0, gate.firstDataByte(), "the message leaves uncompressed, as the initial headers announced");
    }

    /**
     * Holds {@code send} inside the write of the initial headers, runs {@code late} on another thread, then
     * lets the write go on: {@code late} must end with the {@link IllegalStateException} of headers already
     * sent, since it came after they were built.
     */
    private static void assertRefusedWhileTheInitialHeadersLeave(GatedChannel gate, GrpcCall call, Action late)
            throws Exception {
        gate.holdNextWrite();
        var sent = onAnotherThread(() -> call.send(new byte[] {42}));
        gate.awaitHeldWrite();
        var outcome = onAnotherThread(late);
        gate.release();
        assertNull(sent.get(), "send completes");
        assertInstanceOf(
                IllegalStateException.class,
                outcome.get(),
                "a change of the initial headers made while they are written must be refused, not dropped");
    }

    private static GrpcCall grpcCall(GatedChannel gate) {
        var stream = openStream();
        return new GrpcCallImpl(stream, stream.request(), connection(gate));
    }

    private static GrpcCall grpcWebCall(GatedChannel gate) throws IOException {
        var stream = openStream();
        return new GrpcWebCallImpl(stream, stream.request(), connection(gate), GrpcWebDispatch.Mode.BINARY);
    }

    private static Http2Stream openStream() {
        var stream = new Http2Stream(1, 65_535, 65_535);
        stream.open();
        return stream;
    }

    private static Http2Connection connection(GatedChannel gate) {
        return new Http2Connection(
                Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                gate,
                new ConnectionInfo(0, null, null, null),
                _ -> Response.ok("unused"),
                ServerConfig.DEFAULT,
                ByteBuffer.allocate(0));
    }

    /** Runs {@code action} on a virtual thread; the future completes with what it threw, or {@code null}. */
    private static CompletableFuture<Throwable> onAnotherThread(Action action) {
        var outcome = new CompletableFuture<Throwable>();
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        return outcome;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** Records what the connection writes, and holds one write until released. */
    private static final class GatedChannel implements WritableByteChannel {

        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean holdNext;

        void holdNextWrite() {
            holdNext = true;
        }

        void awaitHeldWrite() throws InterruptedException {
            held.await();
        }

        void release() {
            released.countDown();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (holdNext) {
                holdNext = false;
                held.countDown();
                try {
                    released.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("released never came");
                }
            }
            int length = src.remaining();
            byte[] bytes = new byte[length];
            src.get(bytes);
            synchronized (wire) {
                wire.write(bytes, 0, length);
            }
            return length;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release: the recorded bytes stay readable once the connection closes the channel
        }

        /** The first byte of the first DATA frame's payload: the gRPC compressed flag of the first message. */
        int firstDataByte() {
            byte[] bytes;
            synchronized (wire) {
                bytes = wire.toByteArray();
            }
            var frames = ByteBuffer.wrap(bytes);
            while (frames.remaining() >= 9) {
                int length = ((frames.get() & 0xFF) << 16) | ((frames.get() & 0xFF) << 8) | (frames.get() & 0xFF);
                int type = frames.get() & 0xFF;
                frames.get(); // flags
                frames.getInt(); // stream id
                if (type == 0x0 && length > 0) {
                    return frames.get(frames.position()) & 0xFF;
                }
                frames.position(frames.position() + length);
            }
            return -1;
        }
    }
}
