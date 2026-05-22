package io.vidocq.chappe.http.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.vidocq.chappe.api.Headers;

/**
 * Framing spécifique à gRPC-Web (PROTOCOL-WEB.md).
 * <p>
 * Contrairement à gRPC sur HTTP/2 où les trailers sont transportés via un HEADERS
 * frame en fin de stream, les navigateurs n'exposent pas les trailers HTTP/2 à
 * JavaScript. gRPC-Web sérialise donc les "trailers" comme une frame DATA spéciale
 * intégrée au corps de la réponse :
 *
 * <pre>
 * +--------+----------------+--------------------------------+
 * | 1 byte | 4 bytes (BE)   | N bytes                         |
 * | 0x80   | length         | "grpc-status:0\r\ngrpc-msg:..." |
 * +--------+----------------+--------------------------------+
 * </pre>
 *
 * Le MSB du premier octet (0x80) distingue le trailer frame d'un message normal
 * (où ce bit indique simplement {@code compressed=0} ou {@code 1}).
 *
 * <p>Le mode {@code grpc-web-text} (content-type {@code application/grpc-web-text})
 * réencode l'intégralité du corps en Base64 — chaque chunk dans la requête et
 * la réponse est Base64-encodé indépendamment.
 */
public final class GrpcWebFraming {

    public static final byte TRAILER_FLAG = (byte) 0x80;

    private GrpcWebFraming() {}

    /**
     * Encode un trailer frame gRPC-Web : préfixe 5 octets ({@code 0x80} + length BE)
     * suivi du payload texte ("key:value\r\n...").
     * <p>
     * Les noms de headers sont émis en lowercase (alignement avec HTTP/2). La spec
     * gRPC-Web autorise n'importe quelle casse — on choisit lowercase pour la
     * cohérence cross-protocol.
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

    /** {@code true} si l'octet de tête est un trailer frame gRPC-Web (MSB set). */
    public static boolean isTrailerFlag(byte b0) {
        return (b0 & 0x80) != 0;
    }

    /** Encode {@code bytes} en Base64 standard (sans padding URL-safe). */
    public static byte[] base64Encode(byte[] bytes) {
        return Base64.getEncoder().encode(bytes);
    }

    /** Décode {@code base64Bytes} en bytes bruts. */
    public static byte[] base64Decode(byte[] base64Bytes) {
        return Base64.getDecoder().decode(base64Bytes);
    }

    /**
     * Parse un payload de trailer frame en {@code Map<lowercase-name, value>}.
     * Format attendu : "key:value\r\nkey:value\r\n...".
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

    /** Pratique pour les tests : encode plusieurs bytes intermédiaires (helper pour ByteArrayOutputStream). */
    public static byte[] concat(byte[]... chunks) {
        var baos = new ByteArrayOutputStream();
        try {
            for (byte[] c : chunks) baos.write(c);
        } catch (java.io.IOException _) {
            // ByteArrayOutputStream ne lève pas
        }
        return baos.toByteArray();
    }
}
