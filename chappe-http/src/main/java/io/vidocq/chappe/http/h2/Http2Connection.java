package io.vidocq.chappe.http.h2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.RequestContext;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.http.HttpRequestImpl;

/**
 * Gestionnaire de connexion HTTP/2 — une instance par connexion,
 * exécutée dans un virtual thread dédié.
 *
 * <p>Implémente la boucle de lecture des frames HTTP/2 (RFC 9113),
 * dispatche chaque stream vers un virtual thread séparé pour le traitement.
 */
public final class Http2Connection {

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int WRITE_BUFFER_SIZE = 16_384;
    private static final int DATA_CHUNK_SIZE = 8_192;
    private static final int WINDOW_UPDATE_THRESHOLD = 32_768;

    // --- Connexion ---
    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Handler handler;

    @SuppressWarnings("UnusedVariable") // Réservé pour limits H2 (maxStreams, timeouts) — à câbler
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
    public Http2Connection(SocketChannel channel, Handler handler, ServerConfig config, ByteBuffer readBuffer) {
        this((ReadableByteChannel) channel, (WritableByteChannel) channel, handler, config, readBuffer);
    }

    /**
     * Constructeur acceptant des channels séparés (pour TLS via SslHandler).
     */
    public Http2Connection(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            Handler handler,
            ServerConfig config,
            ByteBuffer readBuffer) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.handler = handler;
        this.config = config;
        this.readBuffer = readBuffer;
        this.writeBuffer = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE);
        this.frameReader = new Http2FrameReader(readBuffer, readChannel);
        this.frameWriter = new Http2FrameWriter(writeBuffer, writeChannel);
        this.hpackDecoder = new HpackDecoder(localSettings.headerTableSize(), localSettings.maxHeaderListSize());
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
                    throw new Http2ConnectionException(
                            Http2ErrorCode.PROTOCOL_ERROR,
                            "Expected CONTINUATION for stream " + expectingContinuationForStream);
                }
                handleContinuation(cont);
                continue;
            }

            switch (frame) {
                case Http2Frame.DataFrame f -> handleData(f);
                case Http2Frame.HeadersFrame f -> handleHeaders(f);
                case Http2Frame.RstStreamFrame f -> handleRstStream(f);
                case Http2Frame.SettingsFrame f -> handleSettings(f);
                case Http2Frame.PingFrame f -> handlePing(f);
                case Http2Frame.GoawayFrame f -> handleGoaway(f);
                case Http2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
                case Http2Frame.ContinuationFrame _ ->
                    throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "Unexpected CONTINUATION frame");
                case Http2Frame.UnknownFrame _ -> {} // ignore
            }
        }
    }

    // -------------------------------------------------------------------------
    // Handlers par type de frame
    // -------------------------------------------------------------------------

    private void handleSettings(Http2Frame.SettingsFrame frame) throws IOException {
        if (frame.ack()) return;

        if (frame.streamId() != 0) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "SETTINGS frame on non-zero stream " + frame.streamId());
        }

        var payload = frame.payload();
        Http2Settings oldSettings = remoteSettings;
        remoteSettings = remoteSettings.applyFrom(payload, payload.remaining());
        hpackDecoder.updateMaxTableSize(remoteSettings.headerTableSize());

        // Propagate INITIAL_WINDOW_SIZE change to all open streams (RFC 9113 §6.5.2)
        int oldWindowSize = oldSettings.initialWindowSize();
        int newWindowSize = remoteSettings.initialWindowSize();
        if (newWindowSize != oldWindowSize) {
            int delta = newWindowSize - oldWindowSize;
            for (var stream : streams.values()) {
                int newVal = stream.sendWindow() + delta;
                if (newVal > Integer.MAX_VALUE / 2) {
                    // Overflow: send RST_STREAM with FLOW_CONTROL_ERROR per RFC 9113 §6.9.2
                    frameWriter.writeRstStream(stream.streamId(), Http2ErrorCode.FLOW_CONTROL_ERROR);
                    streams.remove(stream.streamId());
                    stream.close();
                } else {
                    stream.incrementSendWindow(delta);
                }
            }
        }

        frameWriter.writeSettingsAck();
    }

    private void handlePing(Http2Frame.PingFrame frame) throws IOException {
        if (frame.ack()) return;

        if (frame.streamId() != 0) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "PING frame on non-zero stream " + frame.streamId());
        }

        frameWriter.writePingAck(frame.opaqueData());
    }

    private void handleHeaders(Http2Frame.HeadersFrame frame) throws IOException {
        int streamId = frame.streamId();

        // Existing stream → trailers (RFC 9113 §8.1) ; END_STREAM doit être positionné.
        var existing = streams.get(streamId);
        if (existing != null) {
            if (!frame.endStream()) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR,
                        "Trailers HEADERS frame without END_STREAM on stream " + streamId);
            }
            existing.markTrailers();
            existing.beginHeaders(frame.headerBlock(), true);
            if (frame.endHeaders()) {
                completeHeaders(existing);
            } else {
                expectingContinuationForStream = streamId;
            }
            return;
        }

        // Stream ID must be odd (client-initiated) and greater than lastStreamId
        if (streamId % 2 == 0 || streamId <= lastStreamId) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID: " + streamId + " (last=" + lastStreamId + ")");
        }
        lastStreamId = streamId;

        // Check max concurrent streams (notre limite, pas celle du client)
        if (streams.size() >= localSettings.maxConcurrentStreams()) {
            frameWriter.writeRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            return;
        }

        // Create and register the stream
        var stream = new Http2Stream(streamId, localSettings.initialWindowSize(), remoteSettings.initialWindowSize());
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

    private void handleContinuation(Http2Frame.ContinuationFrame frame) throws IOException {
        int streamId = frame.streamId();
        var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "CONTINUATION for unknown stream " + streamId);
        }

        stream.appendHeaderFragment(frame.headerBlock());

        if (frame.endHeaders()) {
            expectingContinuationForStream = -1;
            completeHeaders(stream);
        }
    }

    private void completeHeaders(Http2Stream stream) throws Http2ConnectionException {
        ByteBuffer headerBlock = stream.completeHeaderBlock();

        if (stream.inTrailers()) {
            // Trailers : décoder dans un Headers.Builder à part, l'attacher à la requête.
            // RFC 9113 §8.1 : pas de pseudo-header autorisé dans les trailers.
            var trailersBuilder = Headers.builder();
            hpackDecoder.decode(headerBlock, (name, value) -> {
                if (!name.isEmpty() && name.charAt(0) == ':') {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.PROTOCOL_ERROR, "Pseudo-header '" + name + "' not allowed in trailers");
                }
                trailersBuilder.add(name, value);
            });
            stream.request().setTrailers(trailersBuilder.build());
            // Trailers portent toujours END_STREAM.
            stream.signalEndStream();
            stream.halfCloseRemote();
            return;
        }

        // Initial HEADERS : décodage direct dans la requête.
        var req = stream.request();
        hpackDecoder.decode(headerBlock, req::addHeader);

        // Extract pseudo-headers
        extractPseudoHeaders(req);

        // Half-close remote if END_STREAM was set on HEADERS
        if (stream.headersEndStream()) {
            stream.halfCloseRemote();
        }

        // Dispatch in a dedicated virtual thread
        int streamId = stream.streamId();
        Thread.ofVirtual().name("chappe-h2-stream-" + streamId).start(() -> dispatchStream(stream));
    }

    private void handleData(Http2Frame.DataFrame frame) throws IOException {
        int streamId = frame.streamId();
        var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "DATA for unknown stream " + streamId);
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

    private void handleWindowUpdate(Http2Frame.WindowUpdateFrame frame) throws IOException {
        int increment = frame.windowIncrement();
        if (increment == 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE with zero increment");
        }

        int streamId = frame.streamId();
        if (streamId == 0) {
            // Check overflow: connection send window must not exceed 2^31-1 (RFC 9113 §6.9.1)
            int current = connectionSendWindow.get();
            if (current > Integer.MAX_VALUE - increment) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.FLOW_CONTROL_ERROR, "Connection send window overflow");
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
            stream.cancel();
        }
    }

    @SuppressWarnings("UnusedVariable") // payload GOAWAY non interprété (best-effort)
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
                var ctx = new RequestContext(request);
                response = ScopedValue.where(RequestContext.CURRENT, ctx).call(() -> handler.handle(request));
            } catch (Exception _) {
                response = Response.of(StatusCode.INTERNAL_SERVER_ERROR);
            }

            // Bascule gRPC : le routeur a retourné un marker GrpcDispatch.
            if (response instanceof io.vidocq.chappe.api.GrpcDispatch gd) {
                var call = new io.vidocq.chappe.http.grpc.GrpcCallImpl(stream, request, this);
                io.vidocq.chappe.http.grpc.GrpcCallImpl.run(call, gd.handler());
                return; // GrpcCallImpl gère initial headers, DATA, trailers, half-close
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
        byte[] encodedHeaders = hpackEncoder.encode(response.status().code(), response.headers());

        Body body = response.body();
        boolean hasBody = body != null && body.contentLength() != 0;

        if (hasBody) {
            // HEADERS (sans END_STREAM, le body suit)
            frameWriter.writeHeaders(streamId, encodedHeaders, false);

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
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for flow control window");
            } catch (IOException e) {
                throw e; // propagée au dispatchStream
            }

            // Trailers calculés APRÈS consommation du body (permet aux impl. streaming de
            // déterminer les trailers à la fin — ex. grpc-status).
            Headers trailers = response.trailers();
            boolean hasTrailers = trailers != null && !trailers.isEmpty();

            if (hasTrailers) {
                byte[] encodedTrailers = hpackEncoder.encodeTrailers(trailers);
                // RFC 9113 §8.1 : trailers HEADERS frame avec END_STREAM=1
                frameWriter.writeHeaders(streamId, encodedTrailers, true);
            } else {
                // Final empty DATA frame avec END_STREAM
                frameWriter.writeData(streamId, new byte[0], 0, 0, true);
            }

            stream.halfCloseLocal();
        } else {
            // Pas de body : trailers connus immédiatement (impl. par défaut ne dépend pas du body)
            Headers trailers = response.trailers();
            boolean hasTrailers = trailers != null && !trailers.isEmpty();

            if (hasTrailers) {
                // HEADERS initial sans END_STREAM, puis HEADERS trailers avec END_STREAM
                frameWriter.writeHeaders(streamId, encodedHeaders, false);
                byte[] encodedTrailers = hpackEncoder.encodeTrailers(trailers);
                frameWriter.writeHeaders(streamId, encodedTrailers, true);
                stream.halfCloseLocal();
            } else {
                // Cas usuel : un seul HEADERS avec END_STREAM
                frameWriter.writeHeaders(streamId, encodedHeaders, true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SPI publique pour transports embarqués (ex. gRPC dans le sous-package grpc)
    // -------------------------------------------------------------------------

    /** Frame writer partagé — accès direct pour les transports built-in. */
    public Http2FrameWriter frameWriter() {
        return frameWriter;
    }

    /** Encodeur HPACK partagé — accès direct pour les transports built-in. */
    public HpackEncoder hpackEncoder() {
        return hpackEncoder;
    }

    /**
     * Émet un payload via une ou plusieurs DATA frames, en respectant le flow control H2.
     * <p>
     * Utilisé par {@code sendResponse} et par {@code io.vidocq.chappe.http.grpc.GrpcCallImpl}
     * pour streamer des messages individuels.
     */
    public void sendDataChunked(Http2Stream stream, byte[] data, int offset, int len, boolean endStream)
            throws IOException {
        if (len == 0) {
            frameWriter.writeData(stream.streamId(), new byte[0], 0, 0, endStream);
            return;
        }
        int off = offset;
        int remaining = len;
        try {
            while (remaining > 0) {
                int allowed = waitForSendWindow(stream);
                int chunk = Math.min(remaining, allowed);
                boolean last = (remaining - chunk) == 0;
                frameWriter.writeData(stream.streamId(), data, off, chunk, last && endStream);
                connectionSendWindow.addAndGet(-chunk);
                stream.consumeSendWindow(chunk);
                off += chunk;
                remaining -= chunk;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for flow control window");
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
    private static final java.util.Set<String> FORBIDDEN_HEADERS =
            java.util.Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

    private void extractPseudoHeaders(HttpRequestImpl request) throws Http2ConnectionException {
        String method = null;
        String path = null;
        String authority = null;
        boolean seenRegularHeader = false;
        boolean seenMethod = false;
        boolean seenPath = false;
        boolean seenScheme = false;
        boolean seenAuthority = false;

        // Walk headers: validate ordering, duplicates, forbidden headers (RFC 9113 §8.2.2, §8.3)
        int count = request.headerCount();
        for (int i = 0; i < count; i++) {
            String name = request.headerName(i);
            if (name == null) continue;

            if (name.charAt(0) == ':') {
                // Pseudo-headers must appear before regular headers (RFC 9113 §8.3)
                if (seenRegularHeader) {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.PROTOCOL_ERROR, "Pseudo-header after regular header: " + name);
                }
                switch (name) {
                    case ":method" -> {
                        if (seenMethod)
                            throw new Http2ConnectionException(
                                    Http2ErrorCode.PROTOCOL_ERROR, "Duplicate :method pseudo-header");
                        seenMethod = true;
                        method = request.headerValue(i);
                    }
                    case ":path" -> {
                        if (seenPath)
                            throw new Http2ConnectionException(
                                    Http2ErrorCode.PROTOCOL_ERROR, "Duplicate :path pseudo-header");
                        seenPath = true;
                        path = request.headerValue(i);
                        if (path == null || path.isEmpty()) {
                            throw new Http2ConnectionException(
                                    Http2ErrorCode.PROTOCOL_ERROR, ":path must not be empty");
                        }
                    }
                    case ":scheme" -> {
                        if (seenScheme)
                            throw new Http2ConnectionException(
                                    Http2ErrorCode.PROTOCOL_ERROR, "Duplicate :scheme pseudo-header");
                        seenScheme = true;
                    }
                    case ":authority" -> {
                        if (seenAuthority)
                            throw new Http2ConnectionException(
                                    Http2ErrorCode.PROTOCOL_ERROR, "Duplicate :authority pseudo-header");
                        seenAuthority = true;
                        authority = request.headerValue(i);
                    }
                    default ->
                        throw new Http2ConnectionException(
                                Http2ErrorCode.PROTOCOL_ERROR, "Unknown pseudo-header: " + name);
                }
            } else {
                seenRegularHeader = true;

                // Forbidden connection-specific headers (RFC 9113 §8.2.2)
                if (FORBIDDEN_HEADERS.contains(name)) {
                    throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "Forbidden header: " + name);
                }
                // TE header is only allowed with value "trailers" (RFC 9113 §8.2.2)
                if ("te".equals(name)) {
                    String val = request.headerValue(i);
                    if (val == null || !val.equals("trailers")) {
                        throw new Http2ConnectionException(
                                Http2ErrorCode.PROTOCOL_ERROR,
                                "te header only allowed with value 'trailers', got: " + val);
                    }
                }
            }
        }

        // :method and :path are required (RFC 9113 §8.3.1)
        if (!seenMethod) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "Missing required :method pseudo-header");
        }
        if (!seenPath) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "Missing required :path pseudo-header");
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
            String name = request.headerName(read);
            if (name != null && name.charAt(0) != ':') {
                if (write != read) {
                    request.setHeaderName(write, name);
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
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR, "Invalid HTTP/2 client connection preface");
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

        // Close channels — best-effort, on ignore les erreurs de fermeture
        try {
            readChannel.close();
        } catch (IOException _) {
            // ignored : best-effort cleanup
        }
        try {
            writeChannel.close();
        } catch (IOException _) {
            // ignored : best-effort cleanup
        }
    }
}
