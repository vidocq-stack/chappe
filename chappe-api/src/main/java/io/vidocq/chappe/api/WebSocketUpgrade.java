package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Réponse marqueur signalant au transport HTTP qu'une connexion doit être upgradée
 * en WebSocket (RFC 6455 §1.3) après l'envoi de la réponse {@code 101 Switching Protocols}.
 * <p>
 * Construite par {@link Router.Builder#webSocket(String, WebSocketHandler)} et reconnue
 * par {@code chappe-http} via {@code instanceof}. Une application normale n'a pas besoin
 * de l'instancier directement.
 */
public final class WebSocketUpgrade implements Response {

    private final WebSocketHandler handler;
    private final String subprotocol;

    public WebSocketUpgrade(WebSocketHandler handler, String subprotocol) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.subprotocol = subprotocol;
    }

    public WebSocketUpgrade(WebSocketHandler handler) {
        this(handler, null);
    }

    /** Handler à invoquer une fois le handshake confirmé. */
    public WebSocketHandler handler() {
        return handler;
    }

    /** Sous-protocole accepté (envoyé dans {@code Sec-WebSocket-Protocol}), ou {@code null}. */
    public String subprotocol() {
        return subprotocol;
    }

    @Override
    public StatusCode status() {
        return StatusCode.of(101);
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
