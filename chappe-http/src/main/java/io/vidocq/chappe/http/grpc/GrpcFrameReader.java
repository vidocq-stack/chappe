package io.vidocq.chappe.http.grpc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lit un message gRPC (préfixe 5 octets + payload) depuis un InputStream.
 * <p>
 * Format (gRPC Core protocol) :
 * <pre>
 * +--------+----------------+------------------------+
 * | 1 byte | 4 bytes (BE)   | N bytes                |
 * | compr  | length         | payload                |
 * +--------+----------------+------------------------+
 * </pre>
 * <ul>
 *   <li>{@code compr} : flag de compression (0 = identity, 1 = compressed avec {@code grpc-encoding})</li>
 *   <li>{@code length} : longueur du payload, big-endian, non signée</li>
 * </ul>
 */
public final class GrpcFrameReader {

    /** Taille maximale d'un message reçu (4 MiB par défaut, alignée sur grpc-java). */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    /**
     * Decompresseur d'un payload déjà extrait. Reçoit les bytes compressés,
     * doit retourner les bytes décompressés. Lance {@link IOException} si le
     * format est corrompu.
     */
    @FunctionalInterface
    public interface Decompressor {
        byte[] decompress(byte[] compressed) throws IOException;
    }

    private final int maxMessageSize;
    private final Decompressor decompressor;

    public GrpcFrameReader() {
        this(DEFAULT_MAX_MESSAGE_SIZE, null);
    }

    public GrpcFrameReader(int maxMessageSize) {
        this(maxMessageSize, null);
    }

    /**
     * @param decompressor décompresseur appliqué aux messages dont le préfixe a
     *                     {@code compressed=1}. Si {@code null}, un message
     *                     compressé entrant fait lever {@link GrpcFrameException}.
     */
    public GrpcFrameReader(int maxMessageSize, Decompressor decompressor) {
        this.maxMessageSize = maxMessageSize;
        this.decompressor = decompressor;
    }

    /**
     * Lit le prochain message.
     *
     * @return les bytes du payload (décompressés si nécessaire), ou {@code null}
     *         si l'InputStream est fermé (EOF propre)
     * @throws IOException                  si l'I/O échoue ou si le format est invalide
     * @throws GrpcFrameException           si la longueur dépasse {@code maxMessageSize}
     *                                      ou si un message compressé arrive sans
     *                                      décompresseur configuré
     */
    public byte[] readMessage(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null; // EOF propre entre messages

        int compressed = b0 & 0xFF;
        if (compressed != 0 && compressed != 1) {
            throw new GrpcFrameException("Invalid gRPC compression flag: " + compressed);
        }

        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new EOFException("Truncated gRPC frame header");
        }

        long len = ((long) (b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        if (len > maxMessageSize) {
            throw new GrpcFrameException("gRPC message exceeds max size: " + len + " > " + maxMessageSize);
        }

        byte[] payload = in.readNBytes((int) len);
        if (payload.length != len) {
            throw new EOFException("Truncated gRPC payload: got " + payload.length + " / " + len);
        }

        if (compressed == 1) {
            if (decompressor == null) {
                throw new GrpcFrameException("gRPC compressed message received but no decompressor configured "
                        + "(client should send grpc-encoding header or use identity)");
            }
            return decompressor.decompress(payload);
        }
        return payload;
    }
}
