package io.vidocq.chappe.api;

import java.util.Objects;

/**
 * Marker response telling the HTTP transport that a connection must be upgraded
 * to WebSocket (RFC 6455 §1.3) after sending the {@code 101 Switching Protocols} response.
 * <p>
 * Built by {@link Router.Builder#webSocket(String, WebSocketHandler)} and recognized
 * by {@code chappe-http} via {@code instanceof}. A normal application does not need
 * to instantiate it directly.
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

    /** Handler to invoke once the handshake is confirmed. */
    public WebSocketHandler handler() {
        return handler;
    }

    /** Accepted subprotocol (sent in {@code Sec-WebSocket-Protocol}), or {@code null}. */
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
