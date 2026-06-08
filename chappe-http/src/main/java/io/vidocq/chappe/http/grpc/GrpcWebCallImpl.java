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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.GrpcWebDispatch;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.HttpRequestImpl;
import io.vidocq.chappe.http.h2.Http2Connection;
import io.vidocq.chappe.http.h2.Http2Stream;

/**
 * {@link GrpcCall} variant for gRPC-Web (PROTOCOL-WEB.md) backed by an HTTP/2 stream.
 * <p>
 * Differences compared to {@link GrpcCallImpl}:
 * <ul>
 *   <li>negotiated content type: {@code application/grpc-web} or {@code application/grpc-web-text}</li>
 *   <li>"trailers" are emitted as a special DATA frame ({@code 0x80} prefix via
 *       {@link GrpcWebFraming#encodeTrailerFrame(Headers)}), not as a separate
 *       HEADERS frame — browsers do not read HTTP/2 trailers</li>
 *   <li>{@link GrpcWebDispatch.Mode#TEXT} mode: the entire request/response body is
 *       Base64-encoded (chunk by chunk)</li>
 * </ul>
 * <p>
 * The {@code grpc-timeout} parser and {@code grpc-encoding} negotiation are
 * reused from {@link GrpcCallImpl} (public static methods).
 */
public final class GrpcWebCallImpl implements GrpcCall {

    /** Codecs advertised by the server in {@code grpc-accept-encoding}. */
    private static final String SERVER_ACCEPT_ENCODING = "identity, gzip";

    private final Http2Stream stream;
    private final HttpRequestImpl request;
    private final Http2Connection connection;
    private final GrpcWebDispatch.Mode mode;
    private final GrpcFrameReader frameReader;
    private final InputStream bodyStream;
    private final String contentType;
    private final long deadlineNanoTime;
    private final boolean requestEncodingUnsupported;

    private final Headers.Builder responseHeadersBuilder = Headers.builder();
    private final Headers.Builder responseTrailersBuilder = Headers.builder();
    private final Object writeLock = new Object();
    final AtomicBoolean completed = new AtomicBoolean(false);
    private boolean initialHeadersSent;
    private String responseEncoding;

    public GrpcWebCallImpl(
            Http2Stream stream, HttpRequestImpl request, Http2Connection connection, GrpcWebDispatch.Mode mode)
            throws IOException {
        this.stream = stream;
        this.request = request;
        this.connection = connection;
        this.mode = mode;
        var ct = request.headers().firstOrNull("content-type");
        this.contentType = (ct != null)
                ? ct
                : (mode == GrpcWebDispatch.Mode.TEXT ? "application/grpc-web-text" : "application/grpc-web");

        long deadlineNs = -1L;
        var timeoutHeader = request.headers().firstOrNull("grpc-timeout");
        if (timeoutHeader != null) {
            long nanos = GrpcCallImpl.parseTimeoutNanos(timeoutHeader);
            if (nanos > 0) deadlineNs = System.nanoTime() + nanos;
        }
        this.deadlineNanoTime = deadlineNs;

        // Inbound grpc-encoding negotiation (same logic as GrpcCallImpl).
        var clientEncoding = request.headers().firstOrNull("grpc-encoding");
        String reqEnc = null;
        boolean unsupported = false;
        if (clientEncoding != null && !clientEncoding.isEmpty() && !"identity".equalsIgnoreCase(clientEncoding)) {
            if ("gzip".equalsIgnoreCase(clientEncoding)) {
                reqEnc = "gzip";
            } else {
                unsupported = true;
            }
        }
        this.requestEncodingUnsupported = unsupported;
        GrpcFrameReader.Decompressor decompressor = ("gzip".equals(reqEnc)) ? GrpcCallImpl::gunzip : null;
        this.frameReader = new GrpcFrameReader(GrpcFrameReader.DEFAULT_MAX_MESSAGE_SIZE, decompressor);

        // TEXT mode body: read all then Base64-decode. V1 assumes a single
        // unary call (or short client-streaming). Chunk-by-chunk streaming is
        // possible for BINARY; in TEXT mode browser clients usually send a single
        // full chunk (full XHR POST).
        if (mode == GrpcWebDispatch.Mode.TEXT) {
            byte[] raw = stream.createBody().asInputStream().readAllBytes();
            byte[] decoded = raw.length == 0 ? raw : GrpcWebFraming.base64Decode(raw);
            this.bodyStream = new ByteArrayInputStream(decoded);
        } else {
            this.bodyStream = stream.createBody().asInputStream();
        }
    }

    @Override
    public byte[] receive() throws IOException {
        return frameReader.readMessage(bodyStream);
    }

    @Override
    public void send(byte[] message) throws IOException {
        synchronized (writeLock) {
            if (completed.get()) throw new IllegalStateException("call already completed");
            ensureInitialHeadersSent();
            boolean compressed = false;
            byte[] payload = message;
            if ("gzip".equals(responseEncoding)) {
                payload = GrpcCallImpl.gzip(message);
                compressed = true;
            }
            byte[] framed = GrpcFrameWriter.encode(payload, compressed);
            byte[] toWire = (mode == GrpcWebDispatch.Mode.TEXT) ? GrpcWebFraming.base64Encode(framed) : framed;
            connection.sendDataChunked(stream, toWire, 0, toWire.length, false);
        }
    }

    @Override
    public void complete(int grpcStatus, String message) throws IOException {
        synchronized (writeLock) {
            if (!completed.compareAndSet(false, true)) return;

            responseTrailersBuilder.add("grpc-status", Integer.toString(grpcStatus));
            if (message != null && !message.isEmpty()) {
                responseTrailersBuilder.add("grpc-message", encodePercent(message));
            }

            // gRPC-Web: no trailers-only HEADERS frame. Even on immediate error,
            // emit :status 200 + content-type, then a DATA frame
            // containing ONLY the trailer frame (0x80 prefix).
            ensureInitialHeadersSent();
            byte[] trailerFrame = GrpcWebFraming.encodeTrailerFrame(responseTrailersBuilder.build());
            byte[] toWire =
                    (mode == GrpcWebDispatch.Mode.TEXT) ? GrpcWebFraming.base64Encode(trailerFrame) : trailerFrame;
            connection.sendDataChunked(stream, toWire, 0, toWire.length, true);
            stream.halfCloseLocal();
        }
    }

    @Override
    public Headers metadata() {
        return request.headers();
    }

    @Override
    public void addHeader(String name, String value) {
        if (initialHeadersSent) {
            throw new IllegalStateException("initial headers already sent");
        }
        responseHeadersBuilder.add(name, value);
    }

    @Override
    public void addTrailer(String name, String value) {
        if (completed.get()) {
            throw new IllegalStateException("call already completed");
        }
        responseTrailersBuilder.add(name, value);
    }

    @Override
    public boolean isCancelled() {
        return stream.isCancelled();
    }

    @Override
    public String contentType() {
        return contentType;
    }

    @Override
    public Optional<Duration> deadline() {
        if (deadlineNanoTime < 0) return Optional.empty();
        long remaining = deadlineNanoTime - System.nanoTime();
        return Optional.of(remaining > 0 ? Duration.ofNanos(remaining) : Duration.ZERO);
    }

    @Override
    public void useResponseEncoding(String encoding) {
        if (initialHeadersSent) {
            throw new IllegalStateException("initial headers already sent");
        }
        if (encoding == null || "identity".equalsIgnoreCase(encoding)) {
            this.responseEncoding = null;
            return;
        }
        if ("gzip".equalsIgnoreCase(encoding)) {
            this.responseEncoding = "gzip";
            return;
        }
        throw new UnsupportedOperationException("unsupported gRPC encoding: " + encoding);
    }

    /** Initial headers: :status 200 + gRPC-Web content-type + grpc-accept-encoding (+ grpc-encoding if configured). */
    private void ensureInitialHeadersSent() throws IOException {
        if (initialHeadersSent) return;
        String responseContentType =
                (mode == GrpcWebDispatch.Mode.TEXT) ? "application/grpc-web-text" : "application/grpc-web";
        var builder = Headers.builder()
                .add("content-type", responseContentType)
                .add("grpc-accept-encoding", SERVER_ACCEPT_ENCODING);
        if (responseEncoding != null) {
            builder.add("grpc-encoding", responseEncoding);
        }
        for (var entry : responseHeadersBuilder.build()) {
            builder.add(entry.name(), entry.value());
        }
        byte[] encoded = connection.hpackEncoder().encode(200, builder.build());
        connection.frameWriter().writeHeaders(stream.streamId(), encoded, false);
        initialHeadersSent = true;
    }

    /** Same as {@code GrpcCallImpl.encodePercent} — minor duplication rather than cross-class coupling. */
    private static String encodePercent(String s) {
        var bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int u = b & 0xFF;
            if (u >= 0x20 && u <= 0x7E && u != '%') {
                sb.append((char) u);
            } else {
                sb.append('%');
                sb.append(Character.forDigit((u >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(u & 0xF, 16));
            }
        }
        return sb.toString();
    }

    /**
     * Handler execution loop with error handling (parallel to
     * {@link GrpcCallImpl#run}). Handles unknown request codec (→ immediate
     * UNIMPLEMENTED) and deadline ({@code grpc-timeout}) through a watchdog virtual thread.
     */
    public static void run(GrpcWebCallImpl call, io.vidocq.chappe.api.GrpcHandler handler) {
        if (call.requestEncodingUnsupported) {
            String clientEnc = call.request.headers().firstOrNull("grpc-encoding");
            try {
                call.complete(
                        GrpcStatus.UNIMPLEMENTED,
                        "grpc-encoding '" + clientEnc + "' not supported (accepted: " + SERVER_ACCEPT_ENCODING + ")");
            } catch (IOException _) {
                // lost connection
            }
            return;
        }

        Thread watchdog = null;
        if (call.deadlineNanoTime >= 0L) {
            final long deadline = call.deadlineNanoTime;
            watchdog = Thread.ofVirtual()
                    .name("chappe-grpcweb-deadline-" + call.stream.streamId())
                    .start(() -> {
                        try {
                            long sleepNanos = deadline - System.nanoTime();
                            if (sleepNanos > 0L) Thread.sleep(Duration.ofNanos(sleepNanos));
                            if (call.completed.get()) return;
                            call.stream.cancel();
                            try {
                                call.complete(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
                            } catch (IOException _) {
                                // lost connection
                            }
                        } catch (InterruptedException _) {
                            // watchdog stopped because handler finished before deadline
                        }
                    });
        }
        try {
            handler.handle(call);
            if (!call.completed.get()) {
                call.complete(GrpcStatus.OK, "");
            }
        } catch (Exception e) {
            if (!call.completed.get()) {
                try {
                    call.complete(GrpcStatus.INTERNAL, String.valueOf(e.getMessage()));
                } catch (IOException _) {
                    // lost connection
                }
            }
        } finally {
            if (watchdog != null) watchdog.interrupt();
        }
    }
}
