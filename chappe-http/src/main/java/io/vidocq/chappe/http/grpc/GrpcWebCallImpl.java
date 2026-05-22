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
 * Variante {@link GrpcCall} pour gRPC-Web (PROTOCOL-WEB.md) adossée à un stream HTTP/2.
 * <p>
 * Différences par rapport à {@link GrpcCallImpl} :
 * <ul>
 *   <li>content-type négocié : {@code application/grpc-web} ou {@code application/grpc-web-text}</li>
 *   <li>les "trailers" sont émis comme une frame DATA spéciale (préfixe {@code 0x80} via
 *       {@link GrpcWebFraming#encodeTrailerFrame(Headers)}), pas comme un HEADERS frame
 *       séparé — les navigateurs ne lisent pas les trailers HTTP/2</li>
 *   <li>mode {@link GrpcWebDispatch.Mode#TEXT} : tout le corps request/response est
 *       Base64-encodé (chunk par chunk)</li>
 * </ul>
 * <p>
 * Le parser de {@code grpc-timeout} et la négociation {@code grpc-encoding} sont
 * réutilisés depuis {@link GrpcCallImpl} (méthodes statiques publiques).
 */
public final class GrpcWebCallImpl implements GrpcCall {

    /** Codecs annoncés par le serveur dans {@code grpc-accept-encoding}. */
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

        // Négociation grpc-encoding entrant (même logique que GrpcCallImpl).
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

        // Body en mode TEXT : on lit tout puis Base64-decode. V1 suppose une seule
        // unary call (ou client-streaming court) — le streaming chunk-par-chunk est
        // possible pour BINARY, en TEXT le client navigateur n'envoie qu'un seul
        // chunk en pratique (XHR POST complet).
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

            // gRPC-Web : pas de trailers-only HEADERS frame. Même en cas d'erreur
            // immédiate, on émet :status 200 + content-type, puis une frame DATA
            // qui contient SEULEMENT le trailer frame (préfixe 0x80).
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

    /** Headers initiaux : :status 200 + content-type gRPC-Web + grpc-accept-encoding (+ grpc-encoding si configuré). */
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

    /** Identique à {@code GrpcCallImpl.encodePercent} — duplication mineure plutôt qu'un coupling cross-class. */
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
     * Boucle d'exécution du handler avec gestion d'erreur (parallèle à
     * {@link GrpcCallImpl#run}). Gère codec request inconnu (→ UNIMPLEMENTED
     * immédiat) et la deadline ({@code grpc-timeout}) via un watchdog virtual thread.
     */
    public static void run(GrpcWebCallImpl call, io.vidocq.chappe.api.GrpcHandler handler) {
        if (call.requestEncodingUnsupported) {
            String clientEnc = call.request.headers().firstOrNull("grpc-encoding");
            try {
                call.complete(
                        GrpcStatus.UNIMPLEMENTED,
                        "grpc-encoding '" + clientEnc + "' not supported (accepted: " + SERVER_ACCEPT_ENCODING + ")");
            } catch (IOException _) {
                // connexion perdue
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
                                // connexion perdue
                            }
                        } catch (InterruptedException _) {
                            // watchdog arrêté car handler a fini avant deadline
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
                    // connexion perdue
                }
            }
        } finally {
            if (watchdog != null) watchdog.interrupt();
        }
    }
}
