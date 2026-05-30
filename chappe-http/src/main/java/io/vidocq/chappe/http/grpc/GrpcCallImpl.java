package io.vidocq.chappe.http.grpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.HttpRequestImpl;
import io.vidocq.chappe.http.h2.Http2Connection;
import io.vidocq.chappe.http.h2.Http2Stream;

/**
 * {@link GrpcCall} implementation backed by an HTTP/2 stream.
 * <p>
 * Blocking synchronous model: {@code receive()} blocks on the next DATA frame,
 * {@code send()} blocks on H2 flow control.
 */
public final class GrpcCallImpl implements GrpcCall {

    /** Codecs advertised by the server in {@code grpc-accept-encoding}. */
    private static final String SERVER_ACCEPT_ENCODING = "identity, gzip";

    private final Http2Stream stream;
    private final HttpRequestImpl request;
    private final Http2Connection connection;
    private final GrpcFrameReader frameReader;
    private final InputStream bodyStream;
    private final String contentType;
    private final long deadlineNanoTime;
    /** Codec requested by the client for inbound messages, non-null if valid; null if "identity" or absent. */
    private final String requestEncoding;
    /** {@code true} if the client sent an unknown codec — run() must complete(UNIMPLEMENTED). */
    private final boolean requestEncodingUnsupported;

    private final Headers.Builder responseHeadersBuilder = Headers.builder();
    private final Headers.Builder responseTrailersBuilder = Headers.builder();
    private final Object writeLock = new Object();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private boolean initialHeadersSent;
    /** Active outbound codec; null = identity (default). */
    private String responseEncoding;

    public GrpcCallImpl(Http2Stream stream, HttpRequestImpl request, Http2Connection connection) {
        this.stream = stream;
        this.request = request;
        this.connection = connection;
        var ct = request.headers().firstOrNull("content-type");
        this.contentType = (ct != null) ? ct : "application/grpc";

        long deadlineNs = -1L;
        var timeoutHeader = request.headers().firstOrNull("grpc-timeout");
        if (timeoutHeader != null) {
            long nanos = parseTimeoutNanos(timeoutHeader);
            if (nanos > 0) deadlineNs = System.nanoTime() + nanos;
        }
        this.deadlineNanoTime = deadlineNs;

        // Inbound grpc-encoding negotiation: if codec is unknown, defer
        // complete(UNIMPLEMENTED) to run() so error output goes through
        // the normal pipeline (trailers-only response).
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
        this.requestEncoding = reqEnc;
        this.requestEncodingUnsupported = unsupported;

        GrpcFrameReader.Decompressor decompressor = ("gzip".equals(reqEnc)) ? GrpcCallImpl::gunzip : null;
        this.frameReader = new GrpcFrameReader(GrpcFrameReader.DEFAULT_MAX_MESSAGE_SIZE, decompressor);
        this.bodyStream = stream.createBody().asInputStream();
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
                payload = gzip(message);
                compressed = true;
            }
            byte[] framed = GrpcFrameWriter.encode(payload, compressed);
            connection.sendDataChunked(stream, framed, 0, framed.length, false);
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

            if (!initialHeadersSent) {
                // Trailers-only response (gRPC RFC "Responses"):
                // a single HEADERS frame containing :status, content-type and grpc-status, END_STREAM=1.
                var combined = Headers.builder()
                        .add("content-type", "application/grpc")
                        .add("grpc-status", Integer.toString(grpcStatus));
                if (message != null && !message.isEmpty()) {
                    combined.add("grpc-message", encodePercent(message));
                }
                // Application headers declared via addHeader before complete
                for (var entry : responseHeadersBuilder.build()) {
                    combined.add(entry.name(), entry.value());
                }
                byte[] encoded = connection.hpackEncoder().encode(200, combined.build());
                connection.frameWriter().writeHeaders(stream.streamId(), encoded, true);
                initialHeadersSent = true;
                stream.halfCloseLocal();
                return;
            }

            // Streaming finished: emit separate trailer HEADERS with END_STREAM=1.
            byte[] encodedTrailers = connection.hpackEncoder().encodeTrailers(responseTrailersBuilder.build());
            connection.frameWriter().writeHeaders(stream.streamId(), encodedTrailers, true);
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

    /**
     * Ensures emission of the initial server headers (before the first DATA frame).
     * gRPC RFC: {@code :status 200} + {@code content-type: application/grpc} are mandatory.
     */
    private void ensureInitialHeadersSent() throws IOException {
        if (initialHeadersSent) return;
        var builder = Headers.builder()
                .add("content-type", "application/grpc")
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

    /**
     * Minimal RFC 3986 percent-encoding for {@code grpc-message} (gRPC RFC §"Status codes").
     * Preserves printable characters, encodes the others as UTF-8.
     */
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
     * Parser for the {@code grpc-timeout} header (gRPC RFC §"Requests" — Timeout grammar):
     * <pre>{@code Timeout -> TimeoutValue TimeoutUnit
     * TimeoutValue -> { positive decimal up to 8 digits }
     * TimeoutUnit -> Hour | Minute | Second | Millisecond | Microsecond | Nanosecond
     *             -> "H" | "M" | "S" | "m" | "u" | "n"}</pre>
     *
     * @return timeout length in nanoseconds, or {@code -1} if the value is missing,
     *         malformed, negative, or zero.
     */
    public static long parseTimeoutNanos(String value) {
        if (value == null || value.length() < 2 || value.length() > 9) return -1L;
        char unit = value.charAt(value.length() - 1);
        String digits = value.substring(0, value.length() - 1);
        long n;
        try {
            n = Long.parseLong(digits);
        } catch (NumberFormatException _) {
            return -1L;
        }
        if (n <= 0) return -1L;
        return switch (unit) {
            case 'n' -> n;
            case 'u' -> safeMul(n, 1_000L);
            case 'm' -> safeMul(n, 1_000_000L);
            case 'S' -> safeMul(n, 1_000_000_000L);
            case 'M' -> safeMul(n, 60L * 1_000_000_000L);
            case 'H' -> safeMul(n, 3_600L * 1_000_000_000L);
            default -> -1L;
        };
    }

    /** Multiplication with saturation to {@link Long#MAX_VALUE} on overflow. */
    private static long safeMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }

    /** Gzip-compresses {@code payload}. */
    static byte[] gzip(byte[] payload) throws IOException {
        var baos = new ByteArrayOutputStream(payload.length);
        try (var gz = new GZIPOutputStream(baos)) {
            gz.write(payload);
        }
        return baos.toByteArray();
    }

    /** Gzip-decompresses {@code compressed}. */
    static byte[] gunzip(byte[] compressed) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gz.readAllBytes();
        }
    }

    /**
     * Handler execution loop with error handling: if the handler exits without
     * calling complete, INTERNAL is sent with the exception message.
     * <p>
     * If the request carried a {@code grpc-timeout}, a watchdog virtual thread is
     * started in parallel: on expiry it cancels the stream (unblocking
     * {@link GrpcCall#receive()}) and then attempts {@link GrpcCall#complete} with
     * {@link GrpcStatus#DEADLINE_EXCEEDED}. The CAS on {@code completed} guarantees
     * that at most one final status is emitted.
     */
    public static void run(GrpcCallImpl call, io.vidocq.chappe.api.GrpcHandler handler) {
        // Short-circuit: unknown request codec -> immediate UNIMPLEMENTED,
        // without even calling the handler (gRPC RFC "Compression").
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
                    .name("chappe-grpc-deadline-" + call.stream.streamId())
                    .start(() -> {
                        try {
                            long sleepNanos = deadline - System.nanoTime();
                            if (sleepNanos > 0L) Thread.sleep(Duration.ofNanos(sleepNanos));
                            if (call.completed.get()) return;
                            // Cancel stream first: unblocks a handler stuck in receive()
                            // and toggles isCancelled() for polling loops.
                            call.stream.cancel();
                            try {
                                call.complete(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
                            } catch (IOException _) {
                                // lost connection; nothing to do
                            }
                        } catch (InterruptedException _) {
                            // watchdog stopped because the handler finished before deadline
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
