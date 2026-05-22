package io.vidocq.chappe.http.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.HttpRequestImpl;
import io.vidocq.chappe.http.h2.Http2Connection;
import io.vidocq.chappe.http.h2.Http2Stream;

/**
 * Implémentation de {@link GrpcCall} adossée à un stream HTTP/2.
 * <p>
 * Synchrone bloquante : {@code receive()} bloque sur la prochaine DATA frame,
 * {@code send()} bloque sur le flow control H2.
 */
public final class GrpcCallImpl implements GrpcCall {

    private final Http2Stream stream;
    private final HttpRequestImpl request;
    private final Http2Connection connection;
    private final GrpcFrameReader frameReader;
    private final InputStream bodyStream;
    private final String contentType;
    private final long deadlineNanoTime;

    private final Headers.Builder responseHeadersBuilder = Headers.builder();
    private final Headers.Builder responseTrailersBuilder = Headers.builder();
    private final Object writeLock = new Object();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private boolean initialHeadersSent;

    public GrpcCallImpl(Http2Stream stream, HttpRequestImpl request, Http2Connection connection) {
        this.stream = stream;
        this.request = request;
        this.connection = connection;
        this.frameReader = new GrpcFrameReader();
        this.bodyStream = stream.createBody().asInputStream();
        var ct = request.headers().firstOrNull("content-type");
        this.contentType = (ct != null) ? ct : "application/grpc";

        long deadlineNs = -1L;
        var timeoutHeader = request.headers().firstOrNull("grpc-timeout");
        if (timeoutHeader != null) {
            long nanos = parseTimeoutNanos(timeoutHeader);
            if (nanos > 0) deadlineNs = System.nanoTime() + nanos;
        }
        this.deadlineNanoTime = deadlineNs;
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
            byte[] framed = GrpcFrameWriter.encode(message);
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
                // Trailers-only response (RFC gRPC §"Responses") :
                // un seul HEADERS frame contenant :status, content-type et grpc-status, END_STREAM=1.
                var combined = Headers.builder()
                        .add("content-type", "application/grpc")
                        .add("grpc-status", Integer.toString(grpcStatus));
                if (message != null && !message.isEmpty()) {
                    combined.add("grpc-message", encodePercent(message));
                }
                // Headers applicatifs déclarés via addHeader avant complete
                for (var entry : responseHeadersBuilder.build()) {
                    combined.add(entry.name(), entry.value());
                }
                byte[] encoded = connection.hpackEncoder().encode(200, combined.build());
                connection.frameWriter().writeHeaders(stream.streamId(), encoded, true);
                initialHeadersSent = true;
                stream.halfCloseLocal();
                return;
            }

            // Streaming terminé : émettre les trailers HEADERS séparés avec END_STREAM=1.
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

    /**
     * Garantit l'émission des headers initiaux serveur (avant la 1re DATA frame).
     * RFC gRPC : {@code :status 200} + {@code content-type: application/grpc} obligatoires.
     */
    private void ensureInitialHeadersSent() throws IOException {
        if (initialHeadersSent) return;
        var builder = Headers.builder().add("content-type", "application/grpc").add("grpc-accept-encoding", "identity");
        for (var entry : responseHeadersBuilder.build()) {
            builder.add(entry.name(), entry.value());
        }
        byte[] encoded = connection.hpackEncoder().encode(200, builder.build());
        connection.frameWriter().writeHeaders(stream.streamId(), encoded, false);
        initialHeadersSent = true;
    }

    /**
     * Percent-encoding RFC 3986 minimal pour {@code grpc-message} (RFC gRPC §"Status codes").
     * Préserve les caractères imprimables, encode les autres en UTF-8.
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
     * Parser du header {@code grpc-timeout} (RFC gRPC §"Requests" — Timeout grammar) :
     * <pre>{@code Timeout -> TimeoutValue TimeoutUnit
     * TimeoutValue -> { positive decimal up to 8 digits }
     * TimeoutUnit -> Hour | Minute | Second | Millisecond | Microsecond | Nanosecond
     *             -> "H" | "M" | "S" | "m" | "u" | "n"}</pre>
     *
     * @return nombre de nanosecondes du timeout, ou {@code -1} si la valeur est absente,
     *         malformée, négative ou nulle.
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

    /** Multiplication avec saturation à {@link Long#MAX_VALUE} en cas d'overflow. */
    private static long safeMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Boucle d'exécution du handler avec gestion d'erreur : si le handler sort sans
     * avoir appelé complete, on envoie INTERNAL avec le message d'exception.
     * <p>
     * Si la requête portait un {@code grpc-timeout}, un watchdog virtual thread est
     * démarré en parallèle : à expiration il annule le stream (déblocage de
     * {@link GrpcCall#receive()}) puis tente {@link GrpcCall#complete} avec
     * {@link GrpcStatus#DEADLINE_EXCEEDED}. Le CAS sur {@code completed} garantit
     * qu'au plus un statut final est émis.
     */
    public static void run(GrpcCallImpl call, io.vidocq.chappe.api.GrpcHandler handler) {
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
                            // Annule d'abord le stream : débloque un handler bloqué dans receive()
                            // et signale isCancelled() pour les boucles qui poll.
                            call.stream.cancel();
                            try {
                                call.complete(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
                            } catch (IOException _) {
                                // connexion perdue ; rien à faire
                            }
                        } catch (InterruptedException _) {
                            // watchdog arrêté car le handler a fini avant la deadline
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
