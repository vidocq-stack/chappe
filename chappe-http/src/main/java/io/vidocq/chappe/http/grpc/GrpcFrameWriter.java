package io.vidocq.chappe.http.grpc;

/**
 * Encode un message gRPC (préfixe 5 octets + payload) en un seul {@code byte[]} prêt à
 * être passé à {@code Http2FrameWriter.writeData}.
 */
public final class GrpcFrameWriter {

    private GrpcFrameWriter() {}

    /** Encode un message non compressé. */
    public static byte[] encode(byte[] payload) {
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = 0; // compressed=false
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }
}
