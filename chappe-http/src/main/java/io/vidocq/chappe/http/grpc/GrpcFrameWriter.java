package io.vidocq.chappe.http.grpc;

/**
 * Encodes a gRPC message (5-byte prefix + payload) into a single {@code byte[]} ready to
 * be passed to {@code Http2FrameWriter.writeData}.
 */
public final class GrpcFrameWriter {

    private GrpcFrameWriter() {}

    /** Encodes an uncompressed message (prefix with {@code compressed=0}). */
    public static byte[] encode(byte[] payload) {
        return encode(payload, false);
    }

    /**
     * Encodes a message with an explicit {@code compressed} flag. The provided
     * {@code payload} must already be compressed according to the negotiated
     * {@code grpc-encoding} if {@code compressed=true} — this framing only adds the prefix.
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
