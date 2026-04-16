package fr.vidocq.chappe.http.h2;

import fr.vidocq.chappe.api.Headers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodeur HPACK MVP — literal header fields without indexing, sans Huffman.
 * <p>
 * Suffisant pour un serveur fonctionnel. L'optimisation (indexation dynamique,
 * Huffman) pourra être ajoutée plus tard.
 */
public final class HpackEncoder {

    /**
     * Encode les headers de réponse en un header block HPACK.
     *
     * @param statusCode le code de statut HTTP
     * @param headers    les headers de la réponse
     * @return le header block encodé
     */
    public byte[] encode(int statusCode, Headers headers) {
        var out = new ByteArrayOutputStream(256);

        // :status pseudo-header
        int staticIndex = findStatusIndex(statusCode);
        if (staticIndex > 0) {
            // Indexed Header Field Representation (RFC 7541 §6.1)
            encodeInteger(out, staticIndex, 7, 0x80);
        } else {
            // Literal without indexing, indexed name (:status = entry 8)
            encodeInteger(out, 8, 4, 0x00);
            encodeString(out, String.valueOf(statusCode));
        }

        // Regular headers
        for (var entry : headers) {
            String name = entry.name().toLowerCase();
            String value = entry.value();

            int nameIndex = HpackStaticTable.findByName(name);
            if (nameIndex > 0) {
                // Literal without indexing, indexed name
                encodeInteger(out, nameIndex, 4, 0x00);
            } else {
                // Literal without indexing, new name
                out.write(0x00);
                encodeString(out, name);
            }
            encodeString(out, value);
        }

        return out.toByteArray();
    }

    private static int findStatusIndex(int code) {
        return switch (code) {
            case 200 -> 8;
            case 204 -> 9;
            case 206 -> 10;
            case 304 -> 11;
            case 400 -> 12;
            case 404 -> 13;
            case 500 -> 14;
            default -> 0;
        };
    }

    /** Encode un entier HPACK (RFC 7541 §5.1). */
    static void encodeInteger(ByteArrayOutputStream out, int value, int prefix, int pattern) {
        int mask = (1 << prefix) - 1;
        if (value < mask) {
            out.write(pattern | value);
        } else {
            out.write(pattern | mask);
            value -= mask;
            while (value >= 128) {
                out.write((value & 0x7F) | 0x80);
                value >>= 7;
            }
            out.write(value);
        }
    }

    /** Encode une chaîne HPACK sans Huffman (RFC 7541 §5.2). */
    static void encodeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        encodeInteger(out, bytes.length, 7, 0x00); // H=0 (no Huffman)
        out.write(bytes, 0, bytes.length);
    }
}
