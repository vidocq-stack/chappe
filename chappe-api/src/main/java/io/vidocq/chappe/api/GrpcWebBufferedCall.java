package io.vidocq.chappe.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Implémentation {@link GrpcCall} adossée à un body HTTP/1.1 standard (buffered en
 * lecture, streaming en écriture via {@link OutputStream}).
 * <p>
 * Utilisé par {@link DefaultRouterBuilder#grpcWeb} quand la requête arrive en HTTP/1.1
 * (le client navigateur typique sans HTTP/2 ou {@code HttpClient} JDK en cleartext).
 * En HTTP/2, on bascule plutôt sur {@code GrpcWebCallImpl} (streaming par DATA frame)
 * via le marker {@link GrpcWebDispatch}.
 *
 * <p>Différences pratiques vs HTTP/2 :
 * <ul>
 *   <li><b>Lecture</b> : tout le body request est lu d'avance au constructeur, donc
 *       {@code receive()} parse depuis un buffer en mémoire — pas de blocage I/O</li>
 *   <li><b>Écriture</b> : chaque {@code send}/{@code complete} écrit immédiatement
 *       dans l'{@link OutputStream} chunked-encoding du response HTTP/1.1</li>
 * </ul>
 *
 * <p>Package-private : usage interne à {@link DefaultRouterBuilder} uniquement.
 */
final class GrpcWebBufferedCall implements GrpcCall {

    private static final byte TRAILER_FLAG = (byte) 0x80;
    private static final String SERVER_ACCEPT_ENCODING = "identity, gzip";

    private final Request request;
    private final OutputStream out;
    private final GrpcWebDispatch.Mode mode;
    private final InputStream bodyStream;
    private final String contentType;
    private final long deadlineNanoTime;

    private final Headers.Builder responseTrailersBuilder = Headers.builder();
    private boolean completed;
    private boolean initialEmissionDone;

    GrpcWebBufferedCall(Request request, OutputStream out, GrpcWebDispatch.Mode mode) throws IOException {
        this.request = request;
        this.out = out;
        this.mode = mode;

        var ct = request.headers().firstOrNull("content-type");
        this.contentType = ct != null
                ? ct
                : (mode == GrpcWebDispatch.Mode.TEXT ? "application/grpc-web-text" : "application/grpc-web");

        long deadlineNs = -1L;
        var timeoutHeader = request.headers().firstOrNull("grpc-timeout");
        if (timeoutHeader != null) {
            long nanos = parseTimeoutNanos(timeoutHeader);
            if (nanos > 0) deadlineNs = System.nanoTime() + nanos;
        }
        this.deadlineNanoTime = deadlineNs;

        // Lit tout le body (POST navigateur : tout arrive en un coup).
        byte[] raw = request.body() != null ? request.body().asInputStream().readAllBytes() : new byte[0];
        byte[] decoded = (mode == GrpcWebDispatch.Mode.TEXT && raw.length > 0)
                ? Base64.getDecoder().decode(raw)
                : raw;
        this.bodyStream = new ByteArrayInputStream(decoded);
    }

    @Override
    public byte[] receive() throws IOException {
        int b0 = bodyStream.read();
        if (b0 == -1) return null;
        int compressed = b0 & 0xFF;
        if (compressed != 0) {
            // V1 buffered : compression entrante non implémentée (le H1 ne sert que
            // de fallback pour navigateurs qui n'envoient quasi jamais en gzip).
            throw new IOException("compressed inbound message not supported in HTTP/1.1 gRPC-Web fallback");
        }
        int b1 = bodyStream.read();
        int b2 = bodyStream.read();
        int b3 = bodyStream.read();
        int b4 = bodyStream.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new IOException("truncated gRPC frame header");
        int len = ((b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        byte[] payload = bodyStream.readNBytes(len);
        if (payload.length != len) throw new IOException("truncated gRPC payload");
        return payload;
    }

    @Override
    public void send(byte[] message) throws IOException {
        if (completed) throw new IllegalStateException("call already completed");
        byte[] framed = encodeMessageFrame(message);
        writeChunk(framed);
    }

    @Override
    public void complete(int grpcStatus, String message) throws IOException {
        if (completed) return;
        completed = true;

        responseTrailersBuilder.add("grpc-status", Integer.toString(grpcStatus));
        if (message != null && !message.isEmpty()) {
            responseTrailersBuilder.add("grpc-message", encodePercent(message));
        }
        byte[] trailerFrame = encodeTrailerFrame(responseTrailersBuilder.build());
        writeChunk(trailerFrame);
        out.flush();
    }

    private void writeChunk(byte[] frame) throws IOException {
        if (mode == GrpcWebDispatch.Mode.TEXT) {
            out.write(Base64.getEncoder().encode(frame));
        } else {
            out.write(frame);
        }
        initialEmissionDone = true;
    }

    @Override
    public Headers metadata() {
        return request.headers();
    }

    @Override
    public void addHeader(String name, String value) {
        // Les headers initiaux sont émis par le router via la Response builder ;
        // en H1 buffered on ne supporte pas addHeader après ouverture.
        if (initialEmissionDone) {
            throw new IllegalStateException("initial response already emitted");
        }
        // En H1 buffered v1, addHeader est un no-op silencieux (les headers de
        // response sont fixés par Router.grpcWeb avant que le handler tourne).
    }

    @Override
    public void addTrailer(String name, String value) {
        if (completed) {
            throw new IllegalStateException("call already completed");
        }
        responseTrailersBuilder.add(name, value);
    }

    @Override
    public boolean isCancelled() {
        return false; // H1 buffered : pas de signal de cancellation côté serveur
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
        if (encoding == null || "identity".equalsIgnoreCase(encoding)) return;
        // V1 H1 buffered : encoding sortant pas supporté (les frames sont émises
        // identity ; le handler peut compresser lui-même son payload s'il le veut).
        throw new UnsupportedOperationException(
                "response encoding not supported in HTTP/1.1 gRPC-Web fallback (use HTTP/2 endpoint)");
    }

    boolean isCompleted() {
        return completed;
    }

    String responseContentType() {
        return mode == GrpcWebDispatch.Mode.TEXT ? "application/grpc-web-text" : "application/grpc-web";
    }

    static String acceptEncodingHeader() {
        return SERVER_ACCEPT_ENCODING;
    }

    // ----------------------------------------------------------------------
    // Framing helpers (copies locales pour éviter une dépendance chappe-http)
    // ----------------------------------------------------------------------

    private static byte[] encodeMessageFrame(byte[] payload) {
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = 0;
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }

    private static byte[] encodeTrailerFrame(Headers trailers) {
        var sb = new StringBuilder();
        for (var entry : trailers) {
            sb.append(entry.name().toLowerCase())
                    .append(':')
                    .append(entry.value())
                    .append("\r\n");
        }
        byte[] payload = sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = TRAILER_FLAG;
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }

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
     * Parse {@code grpc-timeout} (RFC gRPC). Copie locale de la logique disponible
     * dans {@code chappe-http} — évite une dépendance inversée pour le fallback H1.
     */
    private static long parseTimeoutNanos(String value) {
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

    private static long safeMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }
}
