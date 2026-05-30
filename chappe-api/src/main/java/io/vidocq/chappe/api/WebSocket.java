package io.vidocq.chappe.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Active WebSocket connection — RFC 6455.
 * <p>
 * An instance is provided to the {@link WebSocketHandler} after a successful handshake.
 * The {@code send*} methods are thread-safe: outgoing frames are serialised
 * internally to prevent interleaving (forbidden by RFC §5.4).
 * <p>
 * Calls block until the complete frame has been written to the socket —
 * natural on virtual threads. A closed connection raises {@link IOException}.
 */
public interface WebSocket {

    /** Sends a text message (TEXT frame, opcode 0x1) as a single unfragmented frame. */
    void sendText(String message) throws IOException;

    /** Sends a binary message (BINARY frame, opcode 0x2) as a single unfragmented frame. */
    void sendBinary(ByteBuffer payload) throws IOException;

    /** Sends a PING (opcode 0x9) — payload ≤ 125 bytes. */
    void sendPing(ByteBuffer payload) throws IOException;

    /** Sends a PONG (opcode 0xA) — payload ≤ 125 bytes. */
    void sendPong(ByteBuffer payload) throws IOException;

    /**
     * Initiates the close handshake with a status code and a text reason.
     * <p>
     * The reason is UTF-8 encoded and limited to 123 bytes ({@code 125 - 2} for the code).
     * After this call, the connection waits for the peer's Close frame and then closes.
     */
    void close(int code, String reason) throws IOException;

    /** Shortcut: closes with {@link CloseCodes#NORMAL_CLOSURE} and no reason. */
    default void close() throws IOException {
        close(CloseCodes.NORMAL_CLOSURE, "");
    }

    /** {@code true} as long as the connection has neither received nor sent a Close frame. */
    boolean isOpen();

    /** Remote (client) endpoint address. */
    InetSocketAddress remoteAddress();

    /** {@code true} if the underlying connection is TLS-secured (wss://). */
    boolean isSecure();

    /**
     * Negotiated sub-protocol (header {@code Sec-WebSocket-Protocol}), or {@code null}
     * if none was requested or accepted.
     */
    String subprotocol();

    /** Mutable attribute attached to the connection (session state). */
    Object attribute(String key);

    /** Sets an attribute. Returns {@code this} for chaining. */
    WebSocket attribute(String key, Object value);
}
