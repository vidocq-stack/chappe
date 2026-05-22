package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Réponse marqueur signalant au transport HTTP/2 qu'un appel <b>gRPC-Web</b> doit être
 * dispatché vers un {@link GrpcHandler}.
 * <p>
 * gRPC-Web (PROTOCOL-WEB.md) est une variante de gRPC compatible navigateurs : les
 * trailers HTTP/2 ne sont pas exposés à JavaScript, donc les "trailers" sont
 * sérialisés inline comme une frame DATA spéciale (préfixe {@code 0x80}). Deux
 * content-types :
 * <ul>
 *   <li>{@code application/grpc-web} — binaire (le payload protobuf est transporté brut)</li>
 *   <li>{@code application/grpc-web-text} — Base64 (chaque chunk request/response est
 *       Base64-encodé, pour les transports texte uniquement comme XHR.responseText)</li>
 * </ul>
 *
 * <p>Construit par {@link Router.Builder#grpcWeb(String, GrpcHandler)} et reconnu par
 * {@code chappe-http} via {@code instanceof}. Une application n'a pas besoin de
 * l'instancier directement.
 */
public final class GrpcWebDispatch implements Response {

    /** Mode d'encodage du corps : binaire ou Base64. */
    public enum Mode {
        /** {@code application/grpc-web} : payload binaire brut. */
        BINARY,
        /** {@code application/grpc-web-text} : payload Base64 (chaque chunk indépendamment). */
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
