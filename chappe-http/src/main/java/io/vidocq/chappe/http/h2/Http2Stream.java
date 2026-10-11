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

    // Held from a reset's cancellation to its RST_STREAM, and by a WINDOW_UPDATE write: none follows
    // the RST_STREAM (RFC 9113 §5.1)
    private final ReentrantLock resetLock = new ReentrantLock();
    private volatile boolean resetSent;

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

    // Request body limit (maxRequestSize) and its race with the response (CHAPPE-025). The DATA of a
    // stream passes the limit before or after the router chose its handler: until the reader, the
    // response or the gRPC route decides, a body over the limit stays BODY_OVER_LIMIT, still buffered
    // (the stream window bounds it).
    private static final int RESPONSE_PENDING = 0;
    private static final int RESPONSE_STARTED = 1;
    private static final int BODY_OVER_LIMIT = 2;
    private static final int BODY_TOO_LARGE = 3;
    private static final int BODY_LIMIT_EXEMPT = 4;
    private final AtomicInteger bodyPhase = new AtomicInteger(RESPONSE_PENDING);
    private volatile long bodyLimit = Long.MAX_VALUE;
    private long bodyBytesReceived; // connection thread only

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
        cancelKeepingResponseBody();
        releaseResponseBody();
    }

    /**
     * {@link #cancel() Cancels} the stream but leaves the release of its response body to the caller,
     * which runs {@link #releaseResponseBody()} once it holds no lock: {@link Body#release()} is
     * application code, and one that blocks must not stall the threads waiting on that lock.
     */
    void cancelKeepingResponseBody() {
        cancelled = true;
        dataQueue.add(ByteBuffer.allocate(0)); // sentinel to unblock pollData(); not an END_STREAM
        close();
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
        if (n <= 0 || endStreamReceived || cancelled || bodyTooLarge()) return;
        if (consumedSinceUpdate.addAndGet(n) < windowUpdateThreshold) return;
        int increment = consumedSinceUpdate.getAndSet(0);
        if (increment <= 0) return;
        recvWindow.addAndGet(increment);
        windowCredit.accept(increment);
    }

    ReentrantLock resetLock() {
        return resetLock;
    }

    /**
     * Records that this endpoint sends RST_STREAM on the stream; called under {@link #resetLock()}.
     * {@code false} when one was already sent: a stream gets a single RST_STREAM (RFC 9113 §5.1).
     */
    boolean markResetSent() {
        if (resetSent) return false;
        resetSent = true;
        return true;
    }

    boolean isResetSent() {
        return resetSent;
    }

    /** The bound on a body read waiting for the next DATA frame; {@code null} when unbounded. */
    Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }

    /**
     * Sets the bound on a body read waiting for the next DATA frame. Called on the handler thread, in
     * the dispatch, once the router sent the call to a gRPC handler: that clears it (ruling F-04).
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

    // --- Request body limit (CHAPPE-025) ---

    void bodyLimit(long limit) {
        this.bodyLimit = limit;
    }

    long bodyLimit() {
        return bodyLimit;
    }

    /**
     * Counts {@code n} received body bytes (DATA payload, padding excluded); {@code true} once the body
     * went past its limit. Connection thread only.
     */
    boolean exceedsBodyLimit(int n) {
        bodyBytesReceived += n;
        return bodyBytesReceived > bodyLimit;
    }

    /**
     * Records that the body went past its limit. {@code true} when the response has already started:
     * the caller resets the stream with CANCEL. Otherwise the body stays buffered until the reader, the
     * response or a gRPC route decides: a reader fails, a response becomes a 413, a gRPC call reads on.
     * Connection thread only.
     */
    boolean passBodyLimit() {
        bodyPhase.compareAndSet(RESPONSE_PENDING, BODY_OVER_LIMIT);
        return bodyPhase.get() == RESPONSE_STARTED;
    }

    /**
     * Claims the response for the dispatch. {@code false} when the request body went past its limit
     * first: the dispatch then answers 413 instead of the handler's response.
     */
    boolean startResponse() {
        return bodyPhase.compareAndSet(RESPONSE_PENDING, RESPONSE_STARTED) || !rejectBody();
    }

    /**
     * Turns a body over its limit into a rejected one: the buffered DATA is dropped and every read
     * fails from then on. {@code true} when the body is rejected, {@code false} when it is within its
     * limit, exempt, or its response already started.
     */
    boolean rejectBody() {
        if (bodyPhase.compareAndSet(BODY_OVER_LIMIT, BODY_TOO_LARGE)) {
            dataQueue.clear();
            dataQueue.add(ByteBuffer.allocate(0)); // wakes a reader waiting on another thread
        }
        return bodyPhase.get() == BODY_TOO_LARGE;
    }

    /** Whether the body was rejected for going past its limit: its DATA is dropped and not credited. */
    boolean bodyTooLarge() {
        return bodyPhase.get() == BODY_TOO_LARGE;
    }

    /**
     * Exempts the stream from the body limit: the router sent it to a gRPC handler, whose call carries
     * a sequence of messages rather than one body (ruling P-1). Decided by the route, never by a client
     * header. A body already over the limit but not yet rejected is read on.
     */
    void exemptFromBodyLimit() {
        if (!bodyPhase.compareAndSet(RESPONSE_PENDING, BODY_LIMIT_EXEMPT)) {
            bodyPhase.compareAndSet(BODY_OVER_LIMIT, BODY_LIMIT_EXEMPT);
        }
    }

    /** Creates a Body fed by the data queue. */
    public Body createBody() {
        return Body.of(new Http2BodyInputStream(this));
    }
}
