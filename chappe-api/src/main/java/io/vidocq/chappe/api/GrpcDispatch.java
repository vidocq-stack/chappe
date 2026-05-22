package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Réponse marqueur signalant au transport HTTP/2 qu'un appel gRPC doit être dispatché
 * vers un {@link GrpcHandler}.
 * <p>
 * Construite par {@link Router.Builder#grpc(String, GrpcHandler)} et reconnue par
 * {@code chappe-http} via {@code instanceof}. Une application normale n'a pas besoin
 * de l'instancier directement.
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
