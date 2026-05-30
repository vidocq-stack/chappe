package io.vidocq.chappe.http.ws;

import java.nio.ByteBuffer;

/**
 * Decoded WebSocket frame — RFC 6455 §5.2.
 * <p>
 * The {@code payload} buffer is in read mode (position=0, limit=effective size).
 * Control frames ({@link #isControl()}) are never fragmented and their
 * payload is ≤ 125 bytes.
 */
public record WebSocketFrame(boolean fin, int opcode, ByteBuffer payload) {

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** True if {@code opcode} belongs to the control frame range (0x8–0xF). */
    public boolean isControl() {
        return (opcode & 0x8) != 0;
    }
}
