package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Marker response telling the HTTP/2 transport that a gRPC call must be dispatched
 * to a {@link GrpcHandler}.
 * <p>
 * Built by {@link Router.Builder#grpc(String, GrpcHandler)} and recognized by
 * {@code chappe-http} via {@code instanceof}. A normal application does not need
 * to instantiate it directly.
 */
public final class GrpcDispatch implements Response {

    private final GrpcHandler handler;

    public GrpcDispatch(GrpcHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public GrpcHandler handler() {
        return handler;
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
