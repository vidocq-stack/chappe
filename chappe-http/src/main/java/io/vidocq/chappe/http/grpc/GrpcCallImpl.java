package io.vidocq.chappe.http.grpc;

import java.io.IOException;
import java.io.InputStream;

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

    private final Headers.Builder responseHeadersBuilder = Headers.builder();
    private final Headers.Builder responseTrailersBuilder = Headers.builder();
    private boolean initialHeadersSent;
    private boolean completed;

    public GrpcCallImpl(Http2Stream stream, HttpRequestImpl request, Http2Connection connection) {
        this.stream = stream;
        this.request = request;
        this.connection = connection;
        this.frameReader = new GrpcFrameReader();
        this.bodyStream = stream.createBody().asInputStream();
        var ct = request.headers().firstOrNull("content-type");
        this.contentType = (ct != null) ? ct : "application/grpc";
    }

    @Override
    public byte[] receive() throws IOException {
        return frameReader.readMessage(bodyStream);
    }

    @Override
    public void send(byte[] message) throws IOException {
        if (completed) throw new IllegalStateException("call already completed");
        ensureInitialHeadersSent();
        byte[] framed = GrpcFrameWriter.encode(message);
        connection.sendDataChunked(stream, framed, 0, framed.length, false);
    }

    @Override
    public void complete(int grpcStatus, String message) throws IOException {
        if (completed) return;
        completed = true;

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
        if (completed) {
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
     * Boucle d'exécution du handler avec gestion d'erreur : si le handler sort sans
     * avoir appelé complete, on envoie INTERNAL avec le message d'exception.
     */
    public static void run(GrpcCallImpl call, io.vidocq.chappe.api.GrpcHandler handler) {
        try {
            handler.handle(call);
            if (!call.completed) {
                call.complete(GrpcStatus.OK, "");
            }
        } catch (Exception e) {
            if (!call.completed) {
                try {
                    call.complete(GrpcStatus.INTERNAL, String.valueOf(e.getMessage()));
                } catch (IOException _) {
                    // connexion perdue
                }
            }
        }
    }
}
