package io.vidocq.chappe.http.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.vidocq.chappe.api.Headers;

/**
 * gRPC-Web-specific framing (PROTOCOL-WEB.md).
 * <p>
 * Unlike gRPC over HTTP/2, where trailers are carried in a HEADERS
 * frame at the end of the stream, browsers do not expose HTTP/2 trailers to
 * JavaScript. gRPC-Web therefore serializes the "trailers" as a special DATA frame
 * embedded in the response body:
 *
 * <pre>
 * +--------+----------------+--------------------------------+
 * | 1 byte | 4 bytes (BE)   | N bytes                         |
 * | 0x80   | length         | "grpc-status:0\r\ngrpc-msg:..." |
 * +--------+----------------+--------------------------------+
 * </pre>
 *
 * The MSB of the first byte (0x80) distinguishes the trailer frame from a normal message
 * (where this bit simply indicates {@code compressed=0} or {@code 1}).
 *
 * <p>{@code grpc-web-text} mode (content type {@code application/grpc-web-text})
 * re-encodes the entire body as Base64 — each chunk in the request and
 * response is Base64-encoded independently.
 */
public final class GrpcWebFraming {

    public static final byte TRAILER_FLAG = (byte) 0x80;

    private GrpcWebFraming() {}

    /**
     * Encodes a gRPC-Web trailer frame: 5-byte prefix ({@code 0x80} + BE length)
     * followed by the text payload ("key:value\r\n...").
     * <p>
     * Header names are emitted in lowercase (aligned with HTTP/2). The gRPC-Web spec
     * allows any casing — lowercase is chosen for cross-protocol consistency.
     */
    public static byte[] encodeTrailerFrame(Headers trailers) {
        var sb = new StringBuilder();
        for (var entry : trailers) {
            sb.append(entry.name().toLowerCase())
                    .append(':')
                    .append(entry.value())
                    .append("\r\n");
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.US_ASCII);
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = TRAILER_FLAG;
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }

    /** {@code true} if the leading byte is a gRPC-Web trailer frame (MSB set). */
    public static boolean isTrailerFlag(byte b0) {
        return (b0 & 0x80) != 0;
    }

    /** Encodes {@code bytes} as standard Base64 (without URL-safe padding). */
    public static byte[] base64Encode(byte[] bytes) {
        return Base64.getEncoder().encode(bytes);
    }

    /** Decodes {@code base64Bytes} into raw bytes. */
    public static byte[] base64Decode(byte[] base64Bytes) {
        return Base64.getDecoder().decode(base64Bytes);
    }

    /**
     * Parses a trailer-frame payload into {@code Map<lowercase-name, value>}.
     * Expected format: "key:value\r\nkey:value\r\n...".
     */
    public static java.util.Map<String, String> parseTrailerPayload(byte[] payload) {
        var map = new java.util.LinkedHashMap<String, String>();
        String text = new String(payload, StandardCharsets.US_ASCII);
        for (String line : text.split("\r\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            map.put(name, value);
        }
        return map;
    }

    /** Handy for tests: encodes multiple intermediate bytes (helper for ByteArrayOutputStream). */
    public static byte[] concat(byte[]... chunks) {
        var baos = new ByteArrayOutputStream();
        try {
            for (byte[] c : chunks) baos.write(c);
        } catch (java.io.IOException _) {
            // ByteArrayOutputStream does not throw here
        }
        return baos.toByteArray();
    }
}
