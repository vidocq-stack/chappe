package io.vidocq.chappe.http.ws;

import java.nio.ByteBuffer;

/**
 * Frame WebSocket décodée — RFC 6455 §5.2.
 * <p>
 * Le buffer {@code payload} est en mode lecture (position=0, limit=taille effective).
 * Les frames de contrôle ({@link #isControl()}) ne sont jamais fragmentées et leur
 * payload est ≤ 125 octets.
 */
public record WebSocketFrame(boolean fin, int opcode, ByteBuffer payload) {

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** Vrai si {@code opcode} appartient à la plage des frames de contrôle (0x8–0xF). */
    public boolean isControl() {
        return (opcode & 0x8) != 0;
    }
}
