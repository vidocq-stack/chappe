package io.vidocq.chappe.http.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Écriture de frames WebSocket sortantes (côté serveur) — RFC 6455 §5.2.
 * <p>
 * Les frames serveur ne sont jamais masquées (§5.1). Les méthodes sont
 * <em>non thread-safe</em> : la sérialisation est assurée par {@code WebSocketConnection}
 * via un {@code ReentrantLock} pour éviter l'entrelacement des frames (§5.4).
 */
public final class WebSocketFrameWriter {

    /** Frame complète : FIN=1, opcode donné, payload non masqué. */
    public static void writeFrame(WritableByteChannel channel, int opcode, ByteBuffer payload) throws IOException {
        writeHeader(channel, true, opcode, payload.remaining());
        flush(channel, payload);
    }

    /** Frame complète avec FIN explicite (pour fragmentation). */
    public static void writeFrame(WritableByteChannel channel, boolean fin, int opcode, ByteBuffer payload)
            throws IOException {
        writeHeader(channel, fin, opcode, payload.remaining());
        flush(channel, payload);
    }

    private static void writeHeader(WritableByteChannel channel, boolean fin, int opcode, long payloadLen)
            throws IOException {
        // Header max = 2 (base) + 8 (ext payload) = 10 octets, pas de masking côté serveur.
        var hdr = ByteBuffer.allocate(10);
        int b0 = (fin ? 0x80 : 0) | (opcode & 0x0F);
        hdr.put((byte) b0);

        if (payloadLen < 126) {
            hdr.put((byte) (payloadLen & 0x7F));
        } else if (payloadLen <= 0xFFFF) {
            hdr.put((byte) 126);
            hdr.putShort((short) payloadLen);
        } else {
            hdr.put((byte) 127);
            hdr.putLong(payloadLen);
        }
        hdr.flip();
        flush(channel, hdr);
    }

    private static void flush(WritableByteChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private WebSocketFrameWriter() {}
}
