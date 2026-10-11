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
import io.vidocq.chappe.api.internal.HeaderFields;
import io.vidocq.chappe.http.HttpRequestImpl;

/**
 * HTTP/2 connection handler — one instance per connection,
 * executed in a dedicated virtual thread.
 *
 * <p>Implements the HTTP/2 frame reading loop (RFC 9113),
 * dispatching each stream to a separate virtual thread for processing.
 */
public final class Http2Connection {

    private static final System.Logger LOG = System.getLogger(Http2Connection.class.getName());

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int WRITE_BUFFER_SIZE = 16_384;
    private static final int DATA_CHUNK_SIZE = 8_192;
    private static final int WINDOW_UPDATE_THRESHOLD = 32_768;
    private static final int CONNECTION_WINDOW = 65_535;

    /** How many reset stream ids are remembered, so the frames the peer already sent on them are ignored. */
    private static final int REMEMBERED_RESET_STREAMS = 256;

    // --- Connection ---
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

    // Streams this endpoint reset or refused: frames already in flight on them are ignored (RFC 9113 §5.1)
    private final ResetStreams resetStreams = new ResetStreams(REMEMBERED_RESET_STREAMS);

    // --- Flow control (connection) ---
    private final AtomicInteger connectionRecvWindow = new AtomicInteger(CONNECTION_WINDOW);
    private final AtomicInteger connectionSendWindow = new AtomicInteger(65_535);
    private final ReentrantLock connectionSendLock = new ReentrantLock();
    private final Condition connectionSendWindowAvailable = connectionSendLock.newCondition();

    // --- CONTINUATION state ---
    private int expectingContinuationForStream = -1;

    // The stream whose header block awaits CONTINUATION frames. A block of a stream that is not served
    // (reset or refused, before or while the block arrives) is still decoded, for the HPACK context.
    private Http2Stream pendingHeaderBlock;

    /**
     * Creates a new HTTP/2 connection.
     *
     * @param channel    the socket channel for the connection
     * @param handler    the application handler
     * @param config     the server configuration
     * @param readBuffer the pre-filled read buffer (from protocol sniffing)
     */
    public Http2Connection(SocketChannel channel, Handler handler, ServerConfig config, ByteBuffer readBuffer) {
        this((ReadableByteChannel) channel, (WritableByteChannel) channel, handler, config, readBuffer);
    }

    /**
     * Constructor accepting separate channels (for TLS via SslHandler).
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
     * Main entry point — executed in a virtual thread.
     * <ol>
     *   <li>Validates the client preface</li>
     *   <li>Sends our SETTINGS</li>
     *   <li>Enters the frame loop</li>
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
            // Lost connection
        } finally {
            close();
        }
    }

    // -------------------------------------------------------------------------
    // Main loop
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
    // Frame-type handlers
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
                    resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
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

        // Existing stream -> trailers (RFC 9113 §8.1); END_STREAM must be set.
        var existing = streams.get(streamId);
        if (existing != null) {
            if (!frame.endStream()) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR,
                        "Trailers HEADERS frame without END_STREAM on stream " + streamId);
            }
            boundHeaderBlock(0, frame.headerBlock().remaining());
            existing.markTrailers();
            existing.beginHeaders(frame.headerBlock(), true);
            if (frame.endHeaders()) {
                completeHeaders(existing);
            } else {
                awaitContinuation(existing);
            }
            return;
        }

        // A stream this endpoint reset: the peer may have sent HEADERS (trailers) before it saw the
        // RST_STREAM. Ignored, but decoded: the block may update the HPACK dynamic table (§5.1).
        if (resetStreams.contains(streamId)) {
            discardHeaderBlock(streamId, frame);
            return;
        }

        // Stream ID must be odd (client-initiated) and greater than lastStreamId
        if (streamId % 2 == 0 || streamId <= lastStreamId) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID: " + streamId + " (last=" + lastStreamId + ")");
        }
        lastStreamId = streamId;

        // Check max concurrent streams (our limit, not the client's)
        if (streams.size() >= localSettings.maxConcurrentStreams()) {
            resetStreams.add(streamId); // the DATA the client already sent on it is ignored
            frameWriter.writeRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            discardHeaderBlock(streamId, frame); // still decoded: it may update the HPACK dynamic table
            return;
        }

        // Create and register the stream
        var stream = new Http2Stream(
                streamId,
                localSettings.initialWindowSize(),
                remoteSettings.initialWindowSize(),
                config.readTimeout(),
                increment -> creditStreamWindow(streamId, increment));
        stream.open();
        streams.put(streamId, stream);

        // Begin header accumulation
        boundHeaderBlock(0, frame.headerBlock().remaining());
        stream.beginHeaders(frame.headerBlock(), frame.endStream());

        if (frame.endHeaders()) {
            completeHeaders(stream);
        } else {
            awaitContinuation(stream);
        }
    }

    /**
     * Decodes and drops the header block of a stream that is not served (reset or refused). HPACK is
     * connection state: skipping a block that inserts into the dynamic table would desynchronise every
     * later block (RFC 7541 §2.3.2, RFC 9113 §4.3).
     */
    private void discardHeaderBlock(int streamId, Http2Frame.HeadersFrame frame) throws Http2ConnectionException {
        boundHeaderBlock(0, frame.headerBlock().remaining());
        var discarded = new Http2Stream(streamId, 0, 0);
        discarded.beginHeaders(frame.headerBlock(), frame.endStream());
        if (frame.endHeaders()) {
            hpackDecoder.decode(discarded.completeHeaderBlock(), (name, value) -> {});
        } else {
            awaitContinuation(discarded);
        }
    }

    /**
     * Refuses a header block (HEADERS plus CONTINUATION) that would grow past the
     * SETTINGS_MAX_HEADER_LIST_SIZE this endpoint advertises, before a byte of {@code fragment} is
     * buffered, so a CONTINUATION flood never grows the heap past it (the CVE-2024-27316 class,
     * CHAPPE-024). The block cannot be decoded piecemeal, and RFC 9113 §10.5.1 requires it be
     * processed unless the connection is closed: the connection is closed, with ENHANCE_YOUR_CALM
     * (§10.5, the code the HPACK decoder already uses for a decoded list past that size). An encoded
     * block is not larger than the list it decodes to, unless Huffman coding is chosen where it
     * lengthens a string, which no encoder does.
     */
    private void boundHeaderBlock(int accumulated, int fragment) throws Http2ConnectionException {
        int limit = localSettings.maxHeaderListSize();
        if (fragment > limit - accumulated) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.ENHANCE_YOUR_CALM, "Header block exceeds max header list size " + limit);
        }
    }

    /** The header block of {@code stream} continues in CONTINUATION frames (RFC 9113 §6.10). */
    private void awaitContinuation(Http2Stream stream) {
        pendingHeaderBlock = stream;
        expectingContinuationForStream = stream.streamId();
    }

    private void handleContinuation(Http2Frame.ContinuationFrame frame) throws IOException {
        // frameLoop has checked that the frame continues the pending block, on its stream.
        var stream = pendingHeaderBlock;
        boundHeaderBlock(stream.headerBlockSize(), frame.headerBlock().remaining());
        stream.appendHeaderFragment(frame.headerBlock());
        if (!frame.endHeaders()) return;

        expectingContinuationForStream = -1;
        pendingHeaderBlock = null;
        if (streams.get(stream.streamId()) == stream) {
            completeHeaders(stream);
        } else {
            // Not served: refused or reset before its block began, or reset while it was arriving
            // (an early response ending the stream between a trailer HEADERS and its CONTINUATION).
            // Decoded all the same, for the HPACK context (§5.1, §4.3).
            hpackDecoder.decode(stream.completeHeaderBlock(), (name, value) -> {});
        }
    }

    private void completeHeaders(Http2Stream stream) throws Http2ConnectionException {
        ByteBuffer headerBlock = stream.completeHeaderBlock();

        if (stream.inTrailers()) {
            // Trailers: decode into a separate Headers.Builder and attach to request.
            // RFC 9113 §8.1: no pseudo-header allowed in trailers.
            var trailersBuilder = Headers.builder();
            hpackDecoder.decode(headerBlock, (name, value) -> {
                if (!name.isEmpty() && name.charAt(0) == ':') {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.PROTOCOL_ERROR, "Pseudo-header '" + name + "' not allowed in trailers");
                }
                trailersBuilder.add(name, value);
            });
            stream.request().setTrailers(trailersBuilder.build());
            // Trailers always carry END_STREAM.
            stream.signalEndStream();
            stream.halfCloseRemote();
            return;
        }

        // Initial HEADERS: decode directly into request.
        var req = stream.request();
        hpackDecoder.decode(headerBlock, req::addHeader);

        // Extract pseudo-headers
        extractPseudoHeaders(req);

        // Half-close remote if END_STREAM was set on HEADERS
        boolean hasBody = !stream.headersEndStream();
        if (!hasBody) {
            stream.halfCloseRemote();
        }

        // Dispatch in a dedicated virtual thread. Whether a body follows is decided here, from the
        // HEADERS frame: by the time the thread runs, a DATA frame carrying END_STREAM may already
        // have half-closed the stream, and its body must not be lost (CHAPPE-016).
        int streamId = stream.streamId();
        Thread.ofVirtual().name("chappe-h2-stream-" + streamId).start(() -> dispatchStream(stream, hasBody));
    }

    private void handleData(Http2Frame.DataFrame frame) throws IOException {
        int streamId = frame.streamId();
        int flowLength = flowControlledLength(frame);
        var stream = streams.get(streamId);
        if (stream == null) {
            discardData(streamId, flowLength);
            return;
        }
        // Every DATA frame counts against the connection window, whatever its stream (§6.9).
        consumeConnectionWindow(flowLength);
        if (remoteEnded(stream)) {
            // The request already ended: the stream is half-closed (remote), or closed and not yet
            // dropped. DATA on it is a stream error STREAM_CLOSED (RFC 9113 §5.1, §6.1).
            resetStream(stream, Http2ErrorCode.STREAM_CLOSED);
            replenishConnectionWindow();
            return;
        }

        // The connection window is credited back on receipt (replenishConnectionWindow), the stream
        // window only as the body is read (Http2Stream.bodyBytesConsumed): what a stream buffers is
        // bounded by its window, and a stream nobody reads never blocks the others (CHAPPE-024).
        if (flowLength > stream.recvWindow()) {
            // The peer overran the stream window (RFC 9113 §6.9.1): a stream error, the connection lives on.
            resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
            replenishConnectionWindow();
            return;
        }
        stream.consumeRecvWindow(flowLength);

        int dataLength = frame.data().remaining();
        if (dataLength > 0) {
            byte[] copy = new byte[dataLength];
            frame.data().get(copy);
            stream.offerData(ByteBuffer.wrap(copy));
        }
        // Padding is flow-controlled but never read: give it back to the stream at once.
        stream.bodyBytesConsumed(flowLength - dataLength);

        if (frame.endStream()) {
            stream.signalEndStream();
            stream.halfCloseRemote();
        }
        replenishConnectionWindow();
    }

    /**
     * Sends the stream WINDOW_UPDATE the body reader earned by consuming bytes, unless the stream is
     * gone or reset. Under the stream's reset lock, which {@link #writeRstStream} holds from the
     * cancellation to the RST_STREAM: no WINDOW_UPDATE follows a RST_STREAM this endpoint sent (RFC
     * 9113 §5.1).
     */
    private void creditStreamWindow(int streamId, int increment) {
        var stream = streams.get(streamId);
        if (stream == null) return;
        var lock = stream.resetLock();
        lock.lock();
        try {
            if (stream.isCancelled() || stream.isResetSent()) return;
            frameWriter.writeWindowUpdate(streamId, increment);
        } catch (IOException _) {
            // connection lost: the reader fails when the connection cancels its stream
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes RST_STREAM for {@code stream} under its reset lock, after {@code beforeReset} (the
     * cancellation, if any): a body reader crediting the window concurrently either wrote its
     * WINDOW_UPDATE before, or sees the reset and writes none.
     */
    private void writeRstStream(Http2Stream stream, Http2ErrorCode error, Runnable beforeReset) throws IOException {
        var lock = stream.resetLock();
        lock.lock();
        try {
            beforeReset.run();
            stream.markResetSent();
            frameWriter.writeRstStream(stream.streamId(), error);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Counts {@code flowLength} against the connection receive window. A peer sending past it is a
     * connection error FLOW_CONTROL_ERROR (RFC 9113 §6.9.1): the window is credited on receipt, so a
     * peer that honours its WINDOW_UPDATEs never gets there.
     */
    private void consumeConnectionWindow(int flowLength) throws Http2ConnectionException {
        if (flowLength > connectionRecvWindow.get()) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FLOW_CONTROL_ERROR, "DATA past the connection flow-control window");
        }
        connectionRecvWindow.addAndGet(-flowLength);
    }

    /**
     * Whether the peer ended its side of {@code stream}: END_STREAM on its HEADERS, its last DATA or
     * its trailers. Read on the connection thread, which sets both flags.
     */
    private static boolean remoteEnded(Http2Stream stream) {
        return stream.headersEndStream() || stream.isEndStreamReceived();
    }

    /**
     * DATA for a stream that is not open. On a stream this endpoint reset (an early response, a refused
     * or overrun stream) the peer may have sent it before it saw the RST_STREAM: ignored (RFC 9113
     * §5.1). On another closed stream it is a stream error STREAM_CLOSED (§6.1). Either way it counts
     * against the connection window, credited back like any received DATA (§6.9). DATA on stream 0 or
     * on a stream never opened stays a connection error (§6.1, §5.1).
     */
    private void discardData(int streamId, int flowLength) throws IOException {
        if (streamId == 0 || streamId % 2 == 0 || streamId > lastStreamId) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "DATA for idle stream " + streamId);
        }
        consumeConnectionWindow(flowLength);
        if (!resetStreams.contains(streamId)) {
            resetStreams.add(streamId);
            frameWriter.writeRstStream(streamId, Http2ErrorCode.STREAM_CLOSED);
        }
        replenishConnectionWindow();
    }

    /** The flow-controlled size of a DATA frame: its whole payload, padding included (RFC 9113 §6.9.1). */
    private static int flowControlledLength(Http2Frame.DataFrame frame) {
        boolean padded = (frame.flags() & Http2Frame.FLAG_PADDED) != 0;
        return frame.data().remaining() + (padded ? frame.padding() + 1 : 0);
    }

    /** Credits the connection receive window back once it runs low: the connection level is credited on receipt. */
    private void replenishConnectionWindow() throws IOException {
        int connRecv = connectionRecvWindow.get();
        if (connRecv < WINDOW_UPDATE_THRESHOLD) {
            int increment = CONNECTION_WINDOW - connRecv;
            connectionRecvWindow.addAndGet(increment);
            frameWriter.writeWindowUpdate(0, increment);
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
                    resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
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
            wakeConnectionWindowWaiters();
        }
    }

    /** Wakes the senders parked on the connection window, so cancelled streams stop waiting. */
    private void wakeConnectionWindowWaiters() {
        connectionSendLock.lock();
        try {
            connectionSendWindowAvailable.signalAll();
        } finally {
            connectionSendLock.unlock();
        }
    }

    @SuppressWarnings("UnusedVariable") // GOAWAY payload not interpreted (best-effort)
    private void handleGoaway(Http2Frame.GoawayFrame frame) {
        goawaySent = true;
    }

    // -------------------------------------------------------------------------
    // Dispatch and response
    // -------------------------------------------------------------------------

    private void dispatchStream(Http2Stream stream, boolean hasBody) {
        Response handled = null;
        boolean completed = false;
        try {
            HttpRequestImpl request = stream.request();

            // HEADERS without END_STREAM: DATA frames carry a body
            if (hasBody) {
                request.setBody(stream.createBody());
            }

            Response response;
            try {
                var ctx = new RequestContext(request);
                response = ScopedValue.where(RequestContext.CURRENT, ctx).call(() -> handler.handle(request));
            } catch (Exception _) {
                response = Response.of(StatusCode.INTERNAL_SERVER_ERROR);
            }
            // Released on every exit below (see the finally block), whatever happens to the stream.
            handled = response;

            // RFC 9113 §8.6: HTTP/2 removes the Upgrade mechanism — refuse a connection takeover.
            if (response instanceof io.vidocq.chappe.api.ConnectionUpgrade) {
                response = Response.of(StatusCode.HTTP_VERSION_NOT_SUPPORTED);
            }

            // gRPC switch: router returned a GrpcDispatch marker.
            if (response instanceof io.vidocq.chappe.api.GrpcDispatch gd) {
                // A call the router sent to a gRPC handler may stream its request for as long as it
                // lives (client or bidi streaming), with pauses of any length: readTimeout does not
                // bound it (ruling F-04). Decided by the route, never by a client header.
                stream.bodyReadTimeout(null);
                var call = new io.vidocq.chappe.http.grpc.GrpcCallImpl(stream, request, this);
                io.vidocq.chappe.http.grpc.GrpcCallImpl.run(call, gd.handler());
                completed = true;
                return; // GrpcCallImpl handles initial headers, DATA, trailers, half-close
            }

            // gRPC-Web switch: trailers serialized inline as a 0x80 DATA frame.
            if (response instanceof io.vidocq.chappe.api.GrpcWebDispatch gwd) {
                stream.bodyReadTimeout(null); // as for gRPC above (ruling F-04)
                var call = new io.vidocq.chappe.http.grpc.GrpcWebCallImpl(stream, request, this, gwd.mode());
                io.vidocq.chappe.http.grpc.GrpcWebCallImpl.run(call, gwd.handler());
                completed = true;
                return;
            }

            // A head HPACK cannot carry as is (a custom Response bypassing the builder's checks)
            // answers 500 rather than a mangled or malformed field (CHAPPE-008).
            if (!validHeaders(response.headers())) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Refusing an invalid response head on stream " + stream.streamId());
                response = Response.of(StatusCode.INTERNAL_SERVER_ERROR);
            }

            sendResponse(stream, response);
            completed = true;
        } catch (IOException | RuntimeException _) {
            // Connection lost, stream reset, or the response body failed mid-stream (a live body the
            // application aborted), or an unexpected failure such as a field the HPACK encoder refused.
            // Unless the stream is already gone, reset it: otherwise the client
            // would wait forever for an END_STREAM that never comes (RFC 9113 §5.4.2).
            resetAbortedStream(stream);
        } finally {
            if (completed && hasBody) stopUnreadRequestBody(stream);
            stream.close();
            streams.remove(stream.streamId());
            if (handled != null) releaseBody(handled);
        }
    }

    /** Whether every header is a token name with a field value of one-byte chars (CHAPPE-008). */
    private static boolean validHeaders(Headers headers) {
        for (var entry : headers) {
            if (!HeaderFields.isValid(entry.name(), entry.value())) return false;
        }
        return true;
    }

    /** Sends RST_STREAM(INTERNAL_ERROR) for a stream whose response failed, unless it was cancelled. */
    private void resetAbortedStream(Http2Stream stream) {
        if (stream.isCancelled()) return; // reset by the peer or connection gone: no frame (§5.4.2)
        resetStreams.add(stream.streamId()); // the request DATA still in flight is ignored (§5.1)
        try {
            writeRstStream(stream, Http2ErrorCode.INTERNAL_ERROR, () -> {});
        } catch (IOException _) {
            // connection lost: nothing more to tell the peer
        }
    }

    /**
     * The response is complete, the request body is not: RFC 9113 §8.1 lets the server ask the client
     * to stop sending it with RST_STREAM(NO_ERROR). The id is remembered first, so the DATA already in
     * flight is ignored instead of failing the whole connection (§5.1) (CHAPPE-023).
     */
    private void stopUnreadRequestBody(Http2Stream stream) {
        if (stream.isCancelled() || remoteEnded(stream)) return;
        resetStreams.add(stream.streamId());
        streams.remove(stream.streamId());
        try {
            // the cancel fails a reader still waiting for the body instead of letting it wait forever
            writeRstStream(stream, Http2ErrorCode.NO_ERROR, stream::cancel);
        } catch (IOException _) {
            // connection lost: nothing more to tell the peer
        }
    }

    /**
     * Resets {@code stream} with {@code error} (a stream error, RFC 9113 §5.4.2): remembers its id so
     * the frames the peer already sent on it are ignored, cancels it (its body reader fails, the body it
     * was sending is released, its sender stops waiting), then sends RST_STREAM.
     */
    private void resetStream(Http2Stream stream, Http2ErrorCode error) throws IOException {
        resetStreams.add(stream.streamId());
        streams.remove(stream.streamId());
        writeRstStream(stream, error, () -> {
            stream.cancel();
            wakeConnectionWindowWaiters();
        });
    }

    /** {@link Body#release() Releases} the body of a response the handler returned; never throws. */
    private static void releaseBody(Response response) {
        try {
            Body body = response.body();
            if (body != null) body.release();
        } catch (RuntimeException _) {
            // best-effort: a release failure must not hide the exchange's outcome
        }
    }

    private void sendResponse(Http2Stream stream, Response response) throws IOException {
        int streamId = stream.streamId();

        Body body = response.body();
        boolean hasBody = body != null && body.contentLength() != 0;

        // A reset (RST_STREAM) or the end of the connection releases the body while it is sent.
        if (body != null) stream.attachResponseBody(body);
        if (stream.isCancelled()) {
            // RFC 9113 §5.4.2: no frame for a stream the peer reset. Checked before HPACK encoding,
            // which would otherwise update the dynamic table for a header block never sent.
            throw new IOException("stream " + streamId + " reset before its response");
        }

        // Encode response headers via HPACK
        byte[] encodedHeaders = hpackEncoder.encode(response.status().code(), response.headers());

        if (hasBody) {
            // HEADERS (without END_STREAM, body follows)
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
                throw e; // propagated to dispatchStream
            }

            // Trailers computed AFTER body consumption (lets streaming impls
            // determine trailers at the end — e.g. grpc-status).
            Headers trailers = response.trailers();
            boolean hasTrailers = trailers != null && !trailers.isEmpty();

            if (hasTrailers) {
                byte[] encodedTrailers = hpackEncoder.encodeTrailers(trailers);
                // RFC 9113 §8.1: trailer HEADERS frame with END_STREAM=1
                frameWriter.writeHeaders(streamId, encodedTrailers, true);
            } else {
                // Final empty DATA frame with END_STREAM
                frameWriter.writeData(streamId, new byte[0], 0, 0, true);
            }

            stream.halfCloseLocal();
        } else {
            // No body: trailers known immediately (default impl does not depend on body)
            Headers trailers = response.trailers();
            boolean hasTrailers = trailers != null && !trailers.isEmpty();

            if (hasTrailers) {
                // Initial HEADERS without END_STREAM, then trailer HEADERS with END_STREAM
                frameWriter.writeHeaders(streamId, encodedHeaders, false);
                byte[] encodedTrailers = hpackEncoder.encodeTrailers(trailers);
                frameWriter.writeHeaders(streamId, encodedTrailers, true);
                stream.halfCloseLocal();
            } else {
                // Usual case: a single HEADERS with END_STREAM
                frameWriter.writeHeaders(streamId, encodedHeaders, true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public SPI for embedded transports (e.g. gRPC in grpc sub-package)
    // -------------------------------------------------------------------------

    /** Shared frame writer — direct access for built-in transports. */
    public Http2FrameWriter frameWriter() {
        return frameWriter;
    }

    /** Shared HPACK encoder — direct access for built-in transports. */
    public HpackEncoder hpackEncoder() {
        return hpackEncoder;
    }

    /**
     * Sends a payload via one or more DATA frames, respecting H2 flow control.
     * <p>
     * Used by {@code sendResponse} and by {@code io.vidocq.chappe.http.grpc.GrpcCallImpl}
     * to stream individual messages.
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
     * respecting both windows and the remote max frame size. A stream reset by the peer, or the
     * end of the connection, ends the wait with an {@link IOException}.
     */
    private int waitForSendWindow(Http2Stream stream) throws InterruptedException, IOException {
        while (true) {
            if (stream.isCancelled()) throw new IOException("stream " + stream.streamId() + " reset");
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
                    while (connectionSendWindow.get() <= 0 && !stream.isCancelled()) {
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
                    while (stream.sendWindow() <= 0 && !stream.isCancelled()) {
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
     * Extracts HTTP/2 pseudo-headers (:method, :path, :scheme, :authority)
     * and converts them to request properties, then removes them from the array.
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
                int read;
                try {
                    read = readChannel.read(readBuffer);
                } finally {
                    readBuffer.flip(); // a failed read leaves the buffer in read mode (CHAPPE-013)
                }
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
    // Close
    // -------------------------------------------------------------------------

    private void close() {
        // Cancel all active streams: their pending reads end, the bodies they were sending are
        // released and their senders stop waiting for window.
        for (var stream : streams.values()) {
            stream.cancel();
        }
        streams.clear();
        wakeConnectionWindowWaiters();

        // Close channels — best-effort, ignore close errors
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
