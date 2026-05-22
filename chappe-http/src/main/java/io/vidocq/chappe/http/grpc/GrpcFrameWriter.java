package io.vidocq.chappe.http.grpc;

/**
 * Encode un message gRPC (préfixe 5 octets + payload) en un seul {@code byte[]} prêt à
 * être passé à {@code Http2FrameWriter.writeData}.
 */
public final class GrpcFrameWriter {

    private GrpcFrameWriter() {}

    /** Encode un message non compressé (préfixe avec {@code compressed=0}). */
    public static byte[] encode(byte[] payload) {
        return encode(payload, false);
    }

    /**
     * Encode un message avec le flag {@code compressed} explicite. Le {@code payload}
     * fourni doit déjà être compressé selon le {@code grpc-encoding} négocié si
     * {@code compressed=true} — ce framing ne fait que poser le préfixe.
     */
    public static byte[] encode(byte[] payload, boolean compressed) {
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = (byte) (compressed ? 1 : 0);
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }
}
