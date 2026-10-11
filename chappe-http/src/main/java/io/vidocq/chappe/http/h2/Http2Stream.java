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
package io.vidocq.chappe.http.h2;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.http.HttpRequestImpl;

/**
 * HTTP/2 stream state — lifecycle, flow control, request construction.
 */
public final class Http2Stream {

    public enum State {
        IDLE,
        OPEN,
        HALF_CLOSED_LOCAL,
        HALF_CLOSED_REMOTE,
        CLOSED
    }

    private final int streamId;
    private volatile State state;
    private final HttpRequestImpl request;

    // Flow control (atomic for thread-safe concurrent access)
    private final AtomicInteger recvWindow;
    private final AtomicInteger sendWindow;
    private final ReentrantLock sendLock = new ReentrantLock();
    private final Condition sendWindowAvailable = sendLock.newCondition();

    // Stream receive window, credited back as the request body is consumed (CHAPPE-024)
    private final int windowUpdateThreshold;
    private final AtomicInteger consumedSinceUpdate = new AtomicInteger();
    private final IntConsumer windowCredit;

    // Bound on the wait for the next DATA frame; cleared for a gRPC stream (ruling F-04, CHAPPE-024)
    private volatile Duration bodyReadTimeout;

    // Header-block accumulation (HEADERS + CONTINUATION)
    private ByteArrayOutputStream headerBlockAccumulator;

    // END_STREAM flag from HEADERS frame (stored for completeHeaders after CONTINUATION)
    private volatile boolean headersEndStream;

    // Current header-accumulation phase: initial vs trailers.
    // RFC 9113 §8.1 — a stream may receive a second HEADERS frame after DATA:
    // those are trailers and must not contain pseudo-headers.
    private volatile boolean inTrailers;

    // Data queue for body
    private final LinkedBlockingQueue<ByteBuffer> dataQueue = new LinkedBlockingQueue<>();
    private volatile boolean endStreamReceived;
    private volatile boolean cancelled;

    public Http2Stream(int streamId, int initialRecvWindow, int initialSendWindow) {
        this(streamId, initialRecvWindow, initialSendWindow, null, _ -> {});
    }

    /**
     * @param bodyReadTimeout bound on a request body read waiting for the next DATA frame
     *     ({@code null}, zero or negative: no bound)
     * @param windowCredit sends a stream WINDOW_UPDATE of the given increment, once the request body
     *     reader consumed that many bytes
     */
    public Http2Stream(
            int streamId,
            int initialRecvWindow,
            int initialSendWindow,
            Duration bodyReadTimeout,
            IntConsumer windowCredit) {
        this.streamId = streamId;
        this.state = State.IDLE;
        this.recvWindow = new AtomicInteger(initialRecvWindow);
        this.sendWindow = new AtomicInteger(initialSendWindow);
        this.windowUpdateThreshold = Math.max(1, initialRecvWindow / 2);
        this.bodyReadTimeout = bodyReadTimeout;
        this.windowCredit = windowCredit;
        this.request = new HttpRequestImpl();
        this.request.setVersion(HttpVersion.HTTP_2);
    }

    public int streamId() {
        return streamId;
    }

    public State state() {
        return state;
    }

    public HttpRequestImpl request() {
        return request;
    }

    // --- State transitions ---

    public void open() {
        state = State.OPEN;
    }

    public void halfCloseRemote() {
        state = (state == State.HALF_CLOSED_LOCAL) ? State.CLOSED : State.HALF_CLOSED_REMOTE;
    }

    public void halfCloseLocal() {
        state = (state == State.OPEN) ? State.HALF_CLOSED_LOCAL : State.CLOSED;
    }

    public void close() {
        state = State.CLOSED;
    }

    /**
     * Marks the stream as cancelled by the peer (RST_STREAM) or by the end of its connection:
     * the response body being sent is released and a sender waiting for window is woken. A reader
     * of the request body is woken too, and fails: a cut body is not a complete one, so only a
     * received END_STREAM ends it cleanly.
     */
    public void cancel() {
        cancelled = true;
        dataQueue.add(ByteBuffer.allocate(0)); // sentinel to unblock pollData(); not an END_STREAM
        close();
        releaseResponseBody();
        sendLock.lock();
        try {
            sendWindowAvailable.signalAll();
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Records the response body this stream is sending, so a reset or the end of the connection
     * releases it. A stream already cancelled releases it at once.
     */
    public void attachResponseBody(Body body) {
        responseBody = body;
        if (cancelled) releaseResponseBody();
    }

    /** {@link Body#release() Releases} the attached response body, if any. Idempotent, never throws. */
    public void releaseResponseBody() {
        Body body = responseBody;
        if (body == null) return;
        try {
            body.release();
        } catch (RuntimeException _) {
            // best-effort: a release failure must not hide the exchange's outcome
        }
    }

    private volatile Body responseBody;

    public boolean isCancelled() {
        return cancelled;
    }

    // --- Flow control ---

    public int recvWindow() {
        return recvWindow.get();
    }

    public int sendWindow() {
        return sendWindow.get();
    }

    public void consumeRecvWindow(int delta) {
        recvWindow.addAndGet(-delta);
    }

    /**
     * Records {@code n} request body bytes the application consumed (or padding, which is never read)
     * and gives them back to the peer's stream window once half of it is pending. The window reopens
     * only as fast as the body is read, so the bytes buffered for a stream never exceed it (RFC 9113
     * §6.9, CHAPPE-024).
     */
    void bodyBytesConsumed(int n) {
        if (n <= 0 || endStreamReceived || cancelled) return;
        if (consumedSinceUpdate.addAndGet(n) < windowUpdateThreshold) return;
        int increment = consumedSinceUpdate.getAndSet(0);
        if (increment <= 0) return;
        recvWindow.addAndGet(increment);
        windowCredit.accept(increment);
    }

    /** The bound on a body read waiting for the next DATA frame; {@code null} when unbounded. */
    Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }

    /**
     * Sets the bound on a body read waiting for the next DATA frame. Called on the connection thread
     * once the request headers are decoded, before the stream is dispatched: a gRPC stream clears it.
     */
    void bodyReadTimeout(Duration timeout) {
        this.bodyReadTimeout = timeout;
    }

    public void consumeSendWindow(int delta) {
        sendWindow.addAndGet(-delta);
    }

    public void incrementSendWindow(int delta) {
        sendWindow.addAndGet(delta);
        sendLock.lock();
        try {
            sendWindowAvailable.signalAll();
        } finally {
            sendLock.unlock();
        }
    }

    public ReentrantLock sendLock() {
        return sendLock;
    }

    public Condition sendWindowAvailable() {
        return sendWindowAvailable;
    }

    // --- Header-block accumulation ---

    public void beginHeaders(ByteBuffer fragment, boolean endStream) {
        this.headersEndStream = endStream;
        headerBlockAccumulator = new ByteArrayOutputStream(fragment.remaining() * 2);
        appendHeaderFragment(fragment);
    }

    /** Marks the next block as trailers (RFC 9113 §8.1). */
    public void markTrailers() {
        this.inTrailers = true;
    }

    public boolean inTrailers() {
        return inTrailers;
    }

    public void appendHeaderFragment(ByteBuffer fragment) {
        byte[] bytes = new byte[fragment.remaining()];
        fragment.get(bytes);
        headerBlockAccumulator.write(bytes, 0, bytes.length);
    }

    /** The size of the header block accumulated so far (HEADERS plus CONTINUATION fragments). */
    int headerBlockSize() {
        return headerBlockAccumulator == null ? 0 : headerBlockAccumulator.size();
    }

    public ByteBuffer completeHeaderBlock() {
        var result = ByteBuffer.wrap(headerBlockAccumulator.toByteArray());
        headerBlockAccumulator = null;
        return result;
    }

    public boolean headersEndStream() {
        return headersEndStream;
    }

    // --- Data (body) ---

    public void offerData(ByteBuffer data) {
        // .add() instead of .offer(): queue is unbounded (LinkedBlockingQueue by default),
        // an add failure indicates an impossible state and must throw IllegalStateException
        dataQueue.add(data);
    }

    public void signalEndStream() {
        endStreamReceived = true;
        dataQueue.add(ByteBuffer.allocate(0)); // sentinel to unblock pollData()
    }

    /** The next body chunk, waiting at most the body read timeout; {@code null} when it expired. */
    ByteBuffer pollData() throws InterruptedException {
        Duration timeout = bodyReadTimeout;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return dataQueue.take();
        return dataQueue.poll(saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /** Longest wait applied, about 73 years: the saturation HTTP/1.1 applies too (CHAPPE-022). */
    private static final long MAX_TIMEOUT_NANOS = Long.MAX_VALUE / 4;

    /** {@code timeout} in nanoseconds, saturated at {@link #MAX_TIMEOUT_NANOS}. */
    private static long saturatedNanos(Duration timeout) {
        try {
            return Math.min(timeout.toNanos(), MAX_TIMEOUT_NANOS);
        } catch (ArithmeticException _) {
            return MAX_TIMEOUT_NANOS;
        }
    }

    boolean isEndStreamReceived() {
        return endStreamReceived;
    }

    boolean isDataQueueEmpty() {
        return dataQueue.isEmpty();
    }

    /** Creates a Body fed by the data queue. */
    public Body createBody() {
        return Body.of(new Http2BodyInputStream(this));
    }
}
