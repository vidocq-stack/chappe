package io.vidocq.chappe.http.h2;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
        this.streamId = streamId;
        this.state = State.IDLE;
        this.recvWindow = new AtomicInteger(initialRecvWindow);
        this.sendWindow = new AtomicInteger(initialSendWindow);
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

    /** Marks the stream as cancelled by the peer (RST_STREAM). */
    public void cancel() {
        cancelled = true;
        signalEndStream();
        close();
    }

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

    public void incrementRecvWindow(int delta) {
        recvWindow.addAndGet(delta);
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
        dataQueue.add(ByteBuffer.allocate(0)); // sentinel to unblock take()
    }

    ByteBuffer takeData() throws InterruptedException {
        return dataQueue.take();
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
