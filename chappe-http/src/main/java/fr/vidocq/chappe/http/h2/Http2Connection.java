package fr.vidocq.chappe.http.h2;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.HttpMethod;
import fr.vidocq.chappe.api.HttpVersion;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.ServerConfig;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.chappe.http.HttpRequestImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestionnaire de connexion HTTP/2 — une instance par connexion,
 * exécutée dans un virtual thread dédié.
 *
 * <p>Implémente la boucle de lecture des frames HTTP/2 (RFC 9113),
 * dispatche chaque stream vers un virtual thread séparé pour le traitement.
 */
public final class Http2Connection {

    private static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int WRITE_BUFFER_SIZE = 16_384;
    private static final int DATA_CHUNK_SIZE = 8_192;
    private static final int WINDOW_UPDATE_THRESHOLD = 32_768;

    // --- Connexion ---
    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Handler handler;
    private final ServerConfig config;

    // --- Buffers ---
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    // --- Codec ---
    private final Http2FrameReader frameReader;
    private final Http2FrameWriter frameWriter;
    private final HpackDecoder hpackDecoder;
    private final HpackEncoder hpackEncoder;

    // --- Settings ---
    private Http2Settings localSettings = Http2Settings.DEFAULT;
    private Http2Settings remoteSettings = Http2Settings.DEFAULT;

    // --- Streams ---
    private final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    private volatile int lastStreamId = 0;
    private volatile boolean goawaySent = false;

    // --- Flow control (connexion) ---
    private final AtomicInteger connectionRecvWindow = new AtomicInteger(65_535);
    private final AtomicInteger connectionSendWindow = new AtomicInteger(65_535);
    private final ReentrantLock connectionSendLock = new ReentrantLock();
    private final Condition connectionSendWindowAvailable = connectionSendLock.newCondition();

    // --- CONTINUATION state ---
    private int expectingContinuationForStream = -1;

    /**
     * Crée une nouvelle connexion HTTP/2.
     *
     * @param channel    le canal socket de la connexion
     * @param handler    le handler applicatif
     * @param config     la configuration serveur
     * @param readBuffer le buffer de lecture pré-rempli (depuis le protocol sniffing)
     */
    public Http2Connection(SocketChannel channel, Handler handler,
                           ServerConfig config, ByteBuffer readBuffer) {
        this((ReadableByteChannel) channel, (WritableByteChannel) channel,
                handler, config, readBuffer);
    }

    /**
     * Constructeur acceptant des channels séparés (pour TLS via SslHandler).
     */
    public Http2Connection(ReadableByteChannel readChannel, WritableByteChannel writeChannel,
                           Handler handler, ServerConfig config, ByteBuffer readBuffer) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.handler = handler;
        this.config = config;
        this.readBuffer = readBuffer;
        this.writeBuffer = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE);
        this.frameReader = new Http2FrameReader(readBuffer, readChannel);
        this.frameWriter = new Http2FrameWriter(writeBuffer, writeChannel);
        this.hpackDecoder = new HpackDecoder(
                localSettings.headerTableSize(), localSettings.maxHeaderListSize());
        this.hpackEncoder = new HpackEncoder();
    }

    /**
     * Point d'entrée principal — exécuté dans un virtual thread.
     * <ol>
     *   <li>Valide le client preface</li>
     *   <li>Envoie nos SETTINGS</li>
     *   <li>Entre dans la boucle de frames</li>
     * </ol>
     */
    public void run() {
        try {
            validateClientPreface();
            frameWriter.writeSettings(localSettings);
            frameLoop();
        } catch (Http2ConnectionException e) {
            sendGoaway(e.errorCode());
        } catch (IOException _) {
            // Connexion perdue
        } finally {
            close();
        }
    }

    // -------------------------------------------------------------------------
    // Boucle principale
    // -------------------------------------------------------------------------

    private void frameLoop() throws IOException {
        while (!goawaySent) {
            Http2Frame frame = frameReader.readFrame(remoteSettings.maxFrameSize());

            // PRIORITY frames are skipped (null)
            if (frame == null) continue;


            // CONTINUATION state enforcement (RFC 9113 §6.10)
            if (expectingContinuationForStream >= 0) {
                if (!(frame instanceof Http2Frame.ContinuationFrame cont)
                        || cont.streamId() != expectingContinuationForStream) {
                    throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                            "Expected CONTINUATION for stream " + expectingContinuationForStream);
                }
                handleContinuation(cont);
                continue;
            }

            switch (frame) {
                case Http2Frame.DataFrame f         -> handleData(f);
                case Http2Frame.HeadersFrame f      -> handleHeaders(f);
                case Http2Frame.RstStreamFrame f    -> handleRstStream(f);
                case Http2Frame.SettingsFrame f     -> handleSettings(f);
                case Http2Frame.PingFrame f         -> handlePing(f);
                case Http2Frame.GoawayFrame f       -> handleGoaway(f);
                case Http2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
                case Http2Frame.ContinuationFrame _ ->
                    throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                            "Unexpected CONTINUATION frame");
                case Http2Frame.UnknownFrame _      -> {} // ignore
            }
        }
    }

    // -------------------------------------------------------------------------
    // Handlers par type de frame
    // -------------------------------------------------------------------------

    private void handleSettings(Http2Frame.SettingsFrame frame) throws IOException {
        if (frame.ack()) return;

        if (frame.streamId() != 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "SETTINGS frame on non-zero stream " + frame.streamId());
        }

        var payload = frame.payload();
        remoteSettings = remoteSettings.applyFrom(payload, payload.remaining());
        hpackDecoder.updateMaxTableSize(remoteSettings.headerTableSize());
        frameWriter.writeSettingsAck();
    }

    private void handlePing(Http2Frame.PingFrame frame) throws IOException {
        if (frame.ack()) return;

        if (frame.streamId() != 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "PING frame on non-zero stream " + frame.streamId());
        }

        frameWriter.writePingAck(frame.opaqueData());
    }

    private void handleHeaders(Http2Frame.HeadersFrame frame) throws IOException {
        int streamId = frame.streamId();

        // Stream ID must be odd (client-initiated) and greater than lastStreamId
        if (streamId % 2 == 0 || streamId <= lastStreamId) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "Invalid stream ID: " + streamId + " (last=" + lastStreamId + ")");
        }
        lastStreamId = streamId;

        // Check max concurrent streams (notre limite, pas celle du client)
        if (streams.size() >= localSettings.maxConcurrentStreams()) {
            frameWriter.writeRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            return;
        }

        // Create and register the stream
        var stream = new Http2Stream(streamId,
                localSettings.initialWindowSize(), remoteSettings.initialWindowSize());
        stream.open();
        streams.put(streamId, stream);

        // Begin header accumulation
        stream.beginHeaders(frame.headerBlock(), frame.endStream());

        if (frame.endHeaders()) {
            completeHeaders(stream);
        } else {
            expectingContinuationForStream = streamId;
        }
    }

    private void handleContinuation(Http2Frame.ContinuationFrame frame)
            throws IOException {
        int streamId = frame.streamId();
        var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "CONTINUATION for unknown stream " + streamId);
        }

        stream.appendHeaderFragment(frame.headerBlock());

        if (frame.endHeaders()) {
            expectingContinuationForStream = -1;
            completeHeaders(stream);
        }
    }

    private void completeHeaders(Http2Stream stream) throws Http2ConnectionException {
        // Decode HPACK into the request
        ByteBuffer headerBlock = stream.completeHeaderBlock();
        hpackDecoder.decode(headerBlock, stream.request());

        // Extract pseudo-headers
        extractPseudoHeaders(stream.request());


        // Half-close remote if END_STREAM was set on HEADERS
        if (stream.headersEndStream()) {
            stream.halfCloseRemote();
        }

        // Dispatch in a dedicated virtual thread
        int streamId = stream.streamId();
        Thread.ofVirtual()
                .name("chappe-h2-stream-" + streamId)
                .start(() -> dispatchStream(stream));
    }

    private void handleData(Http2Frame.DataFrame frame) throws IOException {
        int streamId = frame.streamId();
        var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "DATA for unknown stream " + streamId);
        }

        int dataLength = frame.data().remaining();

        // Update flow control windows (atomic)
        connectionRecvWindow.addAndGet(-dataLength);
        stream.consumeRecvWindow(dataLength);

        // Copy data and offer to stream
        byte[] copy = new byte[dataLength];
        frame.data().get(copy);
        stream.offerData(ByteBuffer.wrap(copy));

        if (frame.endStream()) {
            stream.signalEndStream();
            stream.halfCloseRemote();
        }

        // Auto WINDOW_UPDATE if recv window is getting low
        int connRecv = connectionRecvWindow.get();
        if (connRecv < WINDOW_UPDATE_THRESHOLD) {
            int increment = 65_535 - connRecv;
            connectionRecvWindow.addAndGet(increment);
            frameWriter.writeWindowUpdate(0, increment);
        }
        if (stream.recvWindow() < WINDOW_UPDATE_THRESHOLD) {
            int increment = localSettings.initialWindowSize() - stream.recvWindow();
            stream.incrementRecvWindow(increment);
            frameWriter.writeWindowUpdate(streamId, increment);
        }
    }

    private void handleWindowUpdate(Http2Frame.WindowUpdateFrame frame)
            throws IOException {
        int increment = frame.windowIncrement();
        if (increment == 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                    "WINDOW_UPDATE with zero increment");
        }

        int streamId = frame.streamId();
        if (streamId == 0) {
            // Check overflow: connection send window must not exceed 2^31-1 (RFC 9113 §6.9.1)
            int current = connectionSendWindow.get();
            if (current > Integer.MAX_VALUE - increment) {
                throw new Http2ConnectionException(Http2ErrorCode.FLOW_CONTROL_ERROR,
                        "Connection send window overflow");
            }
            connectionSendWindow.addAndGet(increment);
            connectionSendLock.lock();
            try {
                connectionSendWindowAvailable.signalAll();
            } finally {
                connectionSendLock.unlock();
            }
        } else {
            var stream = streams.get(streamId);
            if (stream != null) {
                // Check overflow: stream send window must not exceed 2^31-1 (RFC 9113 §6.9.1)
                int current = stream.sendWindow();
                if (current > Integer.MAX_VALUE - increment) {
                    frameWriter.writeRstStream(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR);
                    streams.remove(streamId);
                    stream.close();
                    return;
                }
                stream.incrementSendWindow(increment);
            }
            // Ignore WINDOW_UPDATE for unknown/closed streams (RFC 9113 §6.9)
        }
    }

    private void handleRstStream(Http2Frame.RstStreamFrame frame) {
        int streamId = frame.streamId();
        var stream = streams.remove(streamId);
        if (stream != null) {
            stream.signalEndStream();
            stream.close();
        }
    }

    private void handleGoaway(Http2Frame.GoawayFrame frame) {
        goawaySent = true;
    }

    // -------------------------------------------------------------------------
    // Dispatch et réponse
    // -------------------------------------------------------------------------

    private void dispatchStream(Http2Stream stream) {
        try {
            HttpRequestImpl request = stream.request();

            // If stream is OPEN (not half-closed remote), there's a body
            if (stream.state() == Http2Stream.State.OPEN) {
                request.setBody(stream.createBody());
            }

            Response response;
            try {
                response = handler.handle(request);
            } catch (Exception _) {
                response = Response.of(StatusCode.INTERNAL_SERVER_ERROR);
            }

            sendResponse(stream, response);
        } catch (IOException _) {
            // Connexion perdue pendant l'écriture de la réponse
        } finally {
            stream.close();
            streams.remove(stream.streamId());
        }
    }

    private void sendResponse(Http2Stream stream, Response response) throws IOException {
        int streamId = stream.streamId();

        // Encode response headers via HPACK
        byte[] encodedHeaders = hpackEncoder.encode(
                response.status().code(), response.headers());

        Body body = response.body();
        boolean hasBody = body != null && body.contentLength() != 0;

        // Write HEADERS frame
        frameWriter.writeHeaders(streamId, encodedHeaders, !hasBody);

        if (hasBody) {
            try (InputStream is = body.asInputStream()) {
                byte[] buf = new byte[DATA_CHUNK_SIZE];
                int read;
                while ((read = is.read(buf)) != -1) {
                    int offset = 0;
                    int remaining = read;
                    while (remaining > 0) {
                        // Wait for flow control window availability
                        int allowed = waitForSendWindow(stream);
                        int chunkSize = Math.min(remaining, allowed);

                        frameWriter.writeData(streamId, buf, offset, chunkSize, false);

                        // Decrement both windows
                        connectionSendWindow.addAndGet(-chunkSize);
                        stream.consumeSendWindow(chunkSize);

                        offset += chunkSize;
                        remaining -= chunkSize;
                    }
                }

                // Send final empty DATA frame with END_STREAM
                frameWriter.writeData(streamId, new byte[0], 0, 0, true);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for flow control window");
            } catch (IOException e) {
                throw e; // propagée au dispatchStream
            }

            stream.halfCloseLocal();
        }
    }

    // -------------------------------------------------------------------------
    // Flow control helpers
    // -------------------------------------------------------------------------

    /**
     * Waits until both the connection and stream send windows allow sending data.
     * Returns the maximum number of bytes that can be sent in one DATA frame,
     * respecting both windows and the remote max frame size.
     */
    private int waitForSendWindow(Http2Stream stream) throws InterruptedException {
        while (true) {
            int connWindow = connectionSendWindow.get();
            int streamWindow = stream.sendWindow();
            int maxFrame = remoteSettings.maxFrameSize();
            int allowed = Math.min(Math.min(connWindow, streamWindow), maxFrame);

            if (allowed > 0) {
                return allowed;
            }

            // Wait on connection-level condition if connection window is the bottleneck
            if (connWindow <= 0) {
                connectionSendLock.lock();
                try {
                    while (connectionSendWindow.get() <= 0) {
                        connectionSendWindowAvailable.await();
                    }
                } finally {
                    connectionSendLock.unlock();
                }
            }

            // Wait on stream-level condition if stream window is the bottleneck
            if (streamWindow <= 0) {
                stream.sendLock().lock();
                try {
                    while (stream.sendWindow() <= 0) {
                        stream.sendWindowAvailable().await();
                    }
                } finally {
                    stream.sendLock().unlock();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pseudo-headers
    // -------------------------------------------------------------------------

    /**
     * Extrait les pseudo-headers HTTP/2 (:method, :path, :scheme, :authority)
     * et les convertit en propriétés de la requête, puis les supprime du tableau.
     */
    private void extractPseudoHeaders(HttpRequestImpl request) {
        String method = null;
        String path = null;
        String authority = null;

        // Walk headers, extract pseudo-headers
        int count = request.headerCount();
        for (int i = 0; i < count; i++) {
            String name = request.headerName(i);
            if (name.charAt(0) == ':') {
                switch (name) {
                    case ":method"    -> method = request.headerValue(i);
                    case ":path"      -> path = request.headerValue(i);
                    case ":authority" -> authority = request.headerValue(i);
                    case ":scheme"    -> {} // ignored
                    default           -> {} // unknown pseudo-header
                }
            }
        }

        // Apply to request
        if (method != null) request.setMethod(HttpMethod.of(method));
        if (path != null) request.setRawUri(path);

        // Add :authority as Host header
        if (authority != null) {
            request.addHeader("host", authority);
        }

        // Compact: remove all pseudo-headers (names starting with ":")
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (request.headerName(read).charAt(0) != ':') {
                if (write != read) {
                    request.setHeaderName(write, request.headerName(read));
                    request.setHeaderValue(write, request.headerValue(read));
                }
                write++;
            }
        }
        // Clear trailing slots
        for (int i = write; i < count; i++) {
            request.setHeaderName(i, null);
            request.setHeaderValue(i, null);
        }
        request.setHeaderCount(write);
    }

    // -------------------------------------------------------------------------
    // Client preface & GOAWAY
    // -------------------------------------------------------------------------

    private void validateClientPreface() throws IOException {
        // The client preface is 24 bytes: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        for (int i = 0; i < CLIENT_PREFACE.length; i++) {
            if (!readBuffer.hasRemaining()) {
                readBuffer.compact();
                int read = readChannel.read(readBuffer);
                readBuffer.flip();
                if (read == -1) {
                    throw new IOException("Connection closed during client preface");
                }
            }
            byte b = readBuffer.get();
            if (b != CLIENT_PREFACE[i]) {
                throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR,
                        "Invalid HTTP/2 client connection preface");
            }
        }
    }

    private void sendGoaway(Http2ErrorCode errorCode) {
        try {
            frameWriter.writeGoaway(lastStreamId, errorCode);
            goawaySent = true;
        } catch (IOException _) {
            // Connection already broken
        }
    }

    // -------------------------------------------------------------------------
    // Fermeture
    // -------------------------------------------------------------------------

    private void close() {
        // Close all active streams
        for (var stream : streams.values()) {
            stream.signalEndStream();
            stream.close();
        }
        streams.clear();

        // Close channels
        try { readChannel.close(); } catch (IOException _) {}
        try { writeChannel.close(); } catch (IOException _) {}
    }
}
