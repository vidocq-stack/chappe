package io.vidocq.chappe.http.ws;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;

/**
 * Active WebSocket connection — implements {@link WebSocket} and the server I/O loop.
 * <p>
 * Executed on the virtual thread initially allocated to the HTTP connection by {@code chappe-core}.
 * Writes are serialized via {@link #writeLock}: multiple threads (for example an application timer
 * calling {@code sendText}) can write without risking frame interleaving (RFC 6455 §5.4).
 * All incoming frames are dispatched to the {@link WebSocketHandler} on that same thread —
 * no synchronization is required for handler-side reads.
 */
public final class WebSocketConnection implements WebSocket {

    private static final System.Logger LOG = System.getLogger(WebSocketConnection.class.getName());

    private static final long DEFAULT_MAX_PAYLOAD = 64L * 1024 * 1024; // 64 MiB
    private static final int MAX_MESSAGE_SIZE = (int) DEFAULT_MAX_PAYLOAD;

    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Closeable closeable;
    private final ByteBuffer readBuffer;
    private final WebSocketHandler handler;
    private final Request handshake;
    private final String subprotocol;
    private final boolean secure;
    private final InetSocketAddress remoteAddress;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final WebSocketFrameReader reader = new WebSocketFrameReader(DEFAULT_MAX_PAYLOAD);

    private volatile boolean open = true;
    private volatile boolean closeSent;
    private volatile boolean closeReceived;
    private int receivedCloseCode = CloseCodes.NO_STATUS_RCVD;
    private String receivedCloseReason = "";

    public WebSocketConnection(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            Closeable closeable,
            ByteBuffer readBuffer,
            WebSocketHandler handler,
            Request handshake,
            String subprotocol) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.closeable = closeable;
        this.readBuffer = readBuffer;
        this.handler = handler;
        this.handshake = handshake;
        this.subprotocol = subprotocol;
        this.secure = handshake.isSecure();
        this.remoteAddress = handshake.remoteAddress();
    }

    /**
     * Read loop — blocks until closure (Close handshake or EOF).
     * Called by {@code HttpConnection} after {@link WebSocketHandshake#writeResponse}.
     */
    public void run() {
        try {
            handler.onOpen(this, handshake);
        } catch (Exception e) {
            safeError(e);
            forceClose(CloseCodes.INTERNAL_ERROR, "onOpen failed");
            return;
        }

        int currentDataOpcode = -1; // -1 = no data message currently being assembled
        ByteArrayOutputStream messageBuf = null;

        try {
            while (open && !closeReceived) {
                WebSocketFrame frame;
                try {
                    frame = reader.readFrame(readBuffer, readChannel);
                } catch (EOFException eof) {
                    // Peer closed TCP without Close frame — abnormal closure.
                    receivedCloseCode = CloseCodes.ABNORMAL_CLOSURE;
                    break;
                }

                if (frame.isControl()) {
                    handleControl(frame);
                    continue;
                }

                // -- Data frames (TEXT / BINARY / CONTINUATION) --
                if (frame.opcode() == WebSocketFrame.OP_CONTINUATION) {
                    if (currentDataOpcode == -1) {
                        throw new WebSocketProtocolException(
                                CloseCodes.PROTOCOL_ERROR, "CONTINUATION without prior data frame");
                    }
                } else {
                    if (currentDataOpcode != -1) {
                        throw new WebSocketProtocolException(
                                CloseCodes.PROTOCOL_ERROR, "New data frame while previous message incomplete");
                    }
                    currentDataOpcode = frame.opcode();
                    messageBuf = new ByteArrayOutputStream();
                }

                int payloadLen = frame.payload().remaining();
                if (messageBuf.size() + payloadLen > MAX_MESSAGE_SIZE) {
                    throw new WebSocketProtocolException(
                            CloseCodes.MESSAGE_TOO_BIG, "Re-assembled message exceeds " + MAX_MESSAGE_SIZE);
                }
                var bytes = new byte[payloadLen];
                frame.payload().get(bytes);
                messageBuf.write(bytes);

                if (frame.fin()) {
                    var full = messageBuf.toByteArray();
                    int op = currentDataOpcode;
                    currentDataOpcode = -1;
                    messageBuf = null;
                    dispatchMessage(op, full);
                }
            }
        } catch (WebSocketProtocolException pe) {
            safeError(pe);
            sendCloseSilent(pe.closeCode(), pe.getMessage());
        } catch (IOException ioe) {
            safeError(ioe);
            receivedCloseCode = CloseCodes.ABNORMAL_CLOSURE;
        } catch (Throwable t) {
            safeError(t);
            sendCloseSilent(CloseCodes.INTERNAL_ERROR, "handler error");
        } finally {
            try {
                handler.onClose(this, receivedCloseCode, receivedCloseReason);
            } catch (Exception e) {
                safeError(e);
            }
            forceClose();
        }
    }

    // -- Dispatch a full data frame --
    private void dispatchMessage(int opcode, byte[] payload) throws Exception {
        if (opcode == WebSocketFrame.OP_TEXT) {
            String text;
            try {
                var decoder = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                text = decoder.decode(ByteBuffer.wrap(payload)).toString();
            } catch (CharacterCodingException e) {
                throw new WebSocketProtocolException(CloseCodes.INVALID_PAYLOAD_DATA, "Invalid UTF-8 in TEXT frame");
            }
            handler.onText(this, text);
        } else {
            handler.onBinary(this, ByteBuffer.wrap(payload));
        }
    }

    // -- Control frames --
    private void handleControl(WebSocketFrame frame) throws Exception {
        switch (frame.opcode()) {
            case WebSocketFrame.OP_PING -> {
                // RFC §5.5.2: reply with PONG using exactly the same payload.
                var pongPayload = frame.payload().duplicate();
                writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_PONG, pongPayload));
                handler.onPing(this, frame.payload().duplicate());
            }
            case WebSocketFrame.OP_PONG -> handler.onPong(this, frame.payload());
            case WebSocketFrame.OP_CLOSE -> handleClose(frame);
            default ->
                throw new WebSocketProtocolException(
                        CloseCodes.PROTOCOL_ERROR,
                        "Unexpected control opcode 0x" + Integer.toHexString(frame.opcode()));
        }
    }

    private void handleClose(WebSocketFrame frame) throws IOException {
        var p = frame.payload();
        int code = CloseCodes.NO_STATUS_RCVD;
        String reason = "";
        if (p.remaining() == 1) {
            throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Close payload must be 0 or ≥ 2 bytes");
        }
        if (p.remaining() >= 2) {
            code = ((p.get() & 0xFF) << 8) | (p.get() & 0xFF);
            if (!CloseCodes.isValidOnWire(code)) {
                // §7.4.1: invalid received code -> PROTOCOL_ERROR
                throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Invalid close code: " + code);
            }
            if (p.hasRemaining()) {
                var reasonBytes = new byte[p.remaining()];
                p.get(reasonBytes);
                try {
                    reason = StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(reasonBytes))
                            .toString();
                } catch (CharacterCodingException e) {
                    throw new WebSocketProtocolException(
                            CloseCodes.INVALID_PAYLOAD_DATA, "Invalid UTF-8 in Close reason");
                }
            }
        }
        receivedCloseCode = code;
        receivedCloseReason = reason;
        closeReceived = true;
        // Echo close if we did not already initiate it.
        if (!closeSent) {
            sendCloseSilent(code == CloseCodes.NO_STATUS_RCVD ? CloseCodes.NORMAL_CLOSURE : code, "");
        }
    }

    // -- WebSocket implementation --

    @Override
    public void sendText(String message) throws IOException {
        checkOpen();
        var payload = StandardCharsets.UTF_8.encode(message);
        writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_TEXT, payload));
    }

    @Override
    public void sendBinary(ByteBuffer payload) throws IOException {
        checkOpen();
        writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_BINARY, payload));
    }

    @Override
    public void sendPing(ByteBuffer payload) throws IOException {
        checkOpen();
        if (payload.remaining() > 125) {
            throw new IOException("PING payload must be ≤ 125 bytes");
        }
        writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_PING, payload));
    }

    @Override
    public void sendPong(ByteBuffer payload) throws IOException {
        checkOpen();
        if (payload.remaining() > 125) {
            throw new IOException("PONG payload must be ≤ 125 bytes");
        }
        writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_PONG, payload));
    }

    @Override
    public void close(int code, String reason) throws IOException {
        if (closeSent) return;
        var payload = buildClosePayload(code, reason);
        writeLocked(() -> WebSocketFrameWriter.writeFrame(writeChannel, WebSocketFrame.OP_CLOSE, payload));
        closeSent = true;
    }

    @Override
    public boolean isOpen() {
        return open && !closeSent && !closeReceived;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String subprotocol() {
        return subprotocol;
    }

    @Override
    public Object attribute(String key) {
        return attributes.get(key);
    }

    @Override
    public WebSocket attribute(String key, Object value) {
        if (value == null) attributes.remove(key);
        else attributes.put(key, value);
        return this;
    }

    // -- Helpers --

    private void checkOpen() throws IOException {
        if (!open || closeSent) throw new IOException("WebSocket closed");
    }

    private static ByteBuffer buildClosePayload(int code, String reason) {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > 123) {
            // Truncate: max 125 for frame payload, 2 bytes reserved for code.
            var truncated = new byte[123];
            System.arraycopy(reasonBytes, 0, truncated, 0, 123);
            reasonBytes = truncated;
        }
        var buf = ByteBuffer.allocate(2 + reasonBytes.length);
        buf.putShort((short) code);
        buf.put(reasonBytes);
        buf.flip();
        return buf;
    }

    private void sendCloseSilent(int code, String reason) {
        if (closeSent) return;
        try {
            writeLocked(() -> WebSocketFrameWriter.writeFrame(
                    writeChannel, WebSocketFrame.OP_CLOSE, buildClosePayload(code, reason)));
            closeSent = true;
        } catch (IOException _) {
            // Ignore — TCP is already broken.
        }
    }

    private void forceClose(int code, String reason) {
        sendCloseSilent(code, reason);
        forceClose();
    }

    private void forceClose() {
        open = false;
        try {
            closeable.close();
        } catch (IOException _) {
            // Ignore
        }
        if (writeChannel instanceof SocketChannel sc) {
            try {
                sc.close();
            } catch (IOException _) {
            }
        }
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    private void writeLocked(IoTask task) throws IOException {
        try {
            writeLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while acquiring WebSocket write lock");
        }
        try {
            task.run();
        } finally {
            writeLock.unlock();
        }
    }

    private void safeError(Throwable t) {
        // Surface the failure through the framework log before delegating: a WebSocketHandler is not
        // required to override onError (its default is a no-op), so without this an exception thrown by
        // a handler callback would vanish without a trace. Routine network/protocol noise (peer dropped
        // the connection, client protocol violation) stays at DEBUG; an unexpected handler-side failure
        // is logged at WARNING so it is actually visible.
        if (t instanceof IOException || t instanceof WebSocketProtocolException) {
            LOG.log(System.Logger.Level.DEBUG, "WebSocket connection closed on error", t);
        } else {
            LOG.log(System.Logger.Level.WARNING, "WebSocket handler error", t);
        }
        try {
            handler.onError(this, t);
        } catch (Throwable _) {
            // Ignore — application raised an error in onError
        }
    }
}
