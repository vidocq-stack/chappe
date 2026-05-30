package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Marker response telling the HTTP/2 transport that a <b>gRPC-Web</b> call must be
 * dispatched to a {@link GrpcHandler}.
 * <p>
 * gRPC-Web (PROTOCOL-WEB.md) is a browser-compatible variant of gRPC: HTTP/2
 * trailers are not exposed to JavaScript, so the "trailers" are serialized
 * inline as a special DATA frame ({@code 0x80} prefix). Two content types:
 * <ul>
 *   <li>{@code application/grpc-web} — binary (the protobuf payload is transported as-is)</li>
 *   <li>{@code application/grpc-web-text} — Base64 (each request/response chunk is
 *       Base64-encoded, for text-only transports such as XHR.responseText)</li>
 * </ul>
 *
 * <p>Built by {@link Router.Builder#grpcWeb(String, GrpcHandler)} and recognized by
 * {@code chappe-http} via {@code instanceof}. An application does not need to
 * instantiate it directly.
 */
public final class GrpcWebDispatch implements Response {

    /** Body encoding mode: binary or Base64. */
    public enum Mode {
        /** {@code application/grpc-web} : payload binaire brut. */
        BINARY,
        /** {@code application/grpc-web-text}: Base64 payload (each chunk independently). */
        TEXT
    }

    private final GrpcHandler handler;
    private final Mode mode;

    public GrpcWebDispatch(GrpcHandler handler, Mode mode) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public GrpcHandler handler() {
        return handler;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public StatusCode status() {
        return StatusCode.OK;
    }

    @Override
    public Headers headers() {
        return Headers.empty();
    }

    @Override
    public Body body() {
        return Body.empty();
    }
}
