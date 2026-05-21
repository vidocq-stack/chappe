package io.vidocq.chappe.http.ws;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import io.vidocq.chappe.api.CloseCodes;

/**
 * Lecture de frames WebSocket entrantes (côté serveur) — RFC 6455 §5.2.
 * <p>
 * Le serveur impose que toutes les frames client soient masquées (§5.1) :
 * une frame non masquée fait lever une {@link WebSocketProtocolException} avec
 * {@link CloseCodes#PROTOCOL_ERROR}.
 * <p>
 * Le buffer source ({@code src}) est en mode lecture (after {@code flip()}) et est
 * progressivement consommé. Quand il est vide, on lit dans le channel.
 */
public final class WebSocketFrameReader {

    private final long maxPayloadSize;

    public WebSocketFrameReader(long maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    /**
     * Lit une frame complète depuis {@code src}, remplissant via {@code channel} si nécessaire.
     *
     * @throws WebSocketProtocolException violation RFC (mask absent, RSV non nul, payload trop long, opcode invalide)
     * @throws EOFException                connexion fermée par le pair sans frame Close préalable
     */
    public WebSocketFrame readFrame(ByteBuffer src, ReadableByteChannel channel) throws IOException {
        ensure(src, channel, 2);
        int b0 = src.get() & 0xFF;
        int b1 = src.get() & 0xFF;

        boolean fin = (b0 & 0x80) != 0;
        int rsv = b0 & 0x70;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        int len7 = b1 & 0x7F;

        if (rsv != 0) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "RSV bits set without negotiated extension");
        }
        if (!masked) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "Client frame must be masked (RFC 6455 §5.1)");
        }
        if (!isKnownOpcode(opcode)) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "Unknown opcode: 0x" + Integer.toHexString(opcode));
        }
        boolean isControl = (opcode & 0x8) != 0;
        if (isControl && !fin) {
            throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Control frame must not be fragmented");
        }
        if (isControl && len7 > 125) {
            throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Control frame payload > 125");
        }

        long payloadLen;
        if (len7 < 126) {
            payloadLen = len7;
        } else if (len7 == 126) {
            ensure(src, channel, 2);
            payloadLen = ((src.get() & 0xFFL) << 8) | (src.get() & 0xFFL);
        } else {
            ensure(src, channel, 8);
            payloadLen = src.getLong();
            if (payloadLen < 0) {
                throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Negative 64-bit payload length");
            }
        }
        if (payloadLen > maxPayloadSize) {
            throw new WebSocketProtocolException(
                    CloseCodes.MESSAGE_TOO_BIG, "Payload " + payloadLen + " > max " + maxPayloadSize);
        }

        ensure(src, channel, 4);
        int m0 = src.get() & 0xFF;
        int m1 = src.get() & 0xFF;
        int m2 = src.get() & 0xFF;
        int m3 = src.get() & 0xFF;

        // Lit le payload complet
        var payload = new byte[(int) payloadLen];
        int read = 0;
        while (read < payload.length) {
            if (!src.hasRemaining()) {
                fill(src, channel);
            }
            int take = Math.min(src.remaining(), payload.length - read);
            src.get(payload, read, take);
            read += take;
        }

        // Démasquage (XOR avec la clé répétée)
        for (int i = 0; i < payload.length; i++) {
            int mask =
                    switch (i & 3) {
                        case 0 -> m0;
                        case 1 -> m1;
                        case 2 -> m2;
                        default -> m3;
                    };
            payload[i] = (byte) (payload[i] ^ mask);
        }

        return new WebSocketFrame(fin, opcode, ByteBuffer.wrap(payload));
    }

    private static boolean isKnownOpcode(int opcode) {
        return switch (opcode) {
            case WebSocketFrame.OP_CONTINUATION,
                    WebSocketFrame.OP_TEXT,
                    WebSocketFrame.OP_BINARY,
                    WebSocketFrame.OP_CLOSE,
                    WebSocketFrame.OP_PING,
                    WebSocketFrame.OP_PONG -> true;
            default -> false;
        };
    }

    /** Garantit qu'au moins {@code n} octets sont disponibles dans {@code src}, lit dans le channel si besoin. */
    private static void ensure(ByteBuffer src, ReadableByteChannel channel, int n) throws IOException {
        while (src.remaining() < n) {
            fill(src, channel);
        }
    }

    private static void fill(ByteBuffer src, ReadableByteChannel channel) throws IOException {
        // Compacte les données restantes en début de buffer, puis lit.
        src.compact();
        int n = channel.read(src);
        src.flip();
        if (n < 0) {
            throw new EOFException("Connection closed mid-frame");
        }
    }
}
