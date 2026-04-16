package fr.vidocq.chappe.http.h2;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.HttpVersion;
import fr.vidocq.chappe.http.HttpRequestImpl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * État d'un stream HTTP/2 — lifecycle, flow control, construction de la requête.
 */
public final class Http2Stream {

    public enum State {
        IDLE, OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED
    }

    private final int streamId;
    private volatile State state;
    private final HttpRequestImpl request;

    // Flow control
    private volatile int recvWindow;
    private volatile int sendWindow;

    // Accumulation du header block (HEADERS + CONTINUATION)
    private ByteArrayOutputStream headerBlockAccumulator;

    // END_STREAM flag du HEADERS frame (stocké pour completeHeaders après CONTINUATION)
    private volatile boolean headersEndStream;

    // Queue de données pour le body
    private final LinkedBlockingQueue<ByteBuffer> dataQueue = new LinkedBlockingQueue<>();
    private volatile boolean endStreamReceived;

    public Http2Stream(int streamId, int initialRecvWindow, int initialSendWindow) {
        this.streamId = streamId;
        this.state = State.IDLE;
        this.recvWindow = initialRecvWindow;
        this.sendWindow = initialSendWindow;
        this.request = new HttpRequestImpl();
        this.request.setVersion(HttpVersion.HTTP_2);
    }

    public int streamId() { return streamId; }
    public State state() { return state; }
    public HttpRequestImpl request() { return request; }

    // --- Transitions d'état ---

    public void open() { state = State.OPEN; }

    public void halfCloseRemote() {
        state = (state == State.HALF_CLOSED_LOCAL) ? State.CLOSED : State.HALF_CLOSED_REMOTE;
    }

    public void halfCloseLocal() {
        state = (state == State.OPEN) ? State.HALF_CLOSED_LOCAL : State.CLOSED;
    }

    public void close() { state = State.CLOSED; }

    // --- Flow control ---

    public int recvWindow() { return recvWindow; }
    public int sendWindow() { return sendWindow; }

    public void consumeRecvWindow(int delta) { recvWindow -= delta; }
    public void consumeSendWindow(int delta) { sendWindow -= delta; }
    public void incrementSendWindow(int delta) { sendWindow += delta; }
    public void incrementRecvWindow(int delta) { recvWindow += delta; }

    // --- Accumulation du header block ---

    public void beginHeaders(ByteBuffer fragment, boolean endStream) {
        this.headersEndStream = endStream;
        headerBlockAccumulator = new ByteArrayOutputStream(fragment.remaining() * 2);
        appendHeaderFragment(fragment);
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

    public boolean headersEndStream() { return headersEndStream; }

    // --- Données (body) ---

    public void offerData(ByteBuffer data) {
        dataQueue.offer(data);
    }

    public void signalEndStream() {
        endStreamReceived = true;
        dataQueue.offer(ByteBuffer.allocate(0)); // sentinelle pour débloquer take()
    }

    ByteBuffer takeData() throws InterruptedException {
        return dataQueue.take();
    }

    boolean isEndStreamReceived() { return endStreamReceived; }
    boolean isDataQueueEmpty() { return dataQueue.isEmpty(); }

    /** Crée un Body alimenté par la queue de données. */
    public Body createBody() {
        return Body.of(new Http2BodyInputStream(this));
    }
}
