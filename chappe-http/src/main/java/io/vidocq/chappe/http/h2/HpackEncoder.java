package io.vidocq.chappe.http.h2;

import io.vidocq.chappe.api.Headers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodeur HPACK optimisé — RFC 7541.
 *
 * <p>Stratégie d'encodage par ordre de priorité :
 * <ol>
 *   <li>Indexed Header Field (§6.1, prefix 0x80) — correspondance exacte nom+valeur dans la
 *       table statique ou dynamique.</li>
 *   <li>Literal with Incremental Indexing (§6.2.1, prefix 0x40) — nom indexé ou nouveau nom,
 *       la paire est ajoutée à la table dynamique.</li>
 * </ol>
 * Les valeurs de chaînes sont encodées en Huffman si cela produit un résultat plus court.
 */
public final class HpackEncoder {

    /** Default max dynamic table size per RFC 7541 §6.5.2 (4096 bytes). */
    private static final int DEFAULT_MAX_TABLE_SIZE = 4096;

    private final HpackDynamicTable dynamicTable;

    public HpackEncoder() {
        this.dynamicTable = new HpackDynamicTable(DEFAULT_MAX_TABLE_SIZE);
    }

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
        String statusValue = String.valueOf(statusCode);
        encodeHeader(out, ":status", statusValue);

        // Regular headers
        for (var entry : headers) {
            encodeHeader(out, entry.name().toLowerCase(), entry.value());
        }

        return out.toByteArray();
    }

    /**
     * Encode a single name/value pair using the optimal representation:
     * indexed > literal with incremental indexing (indexed name) > literal with incremental
     * indexing (new name).
     */
    private void encodeHeader(ByteArrayOutputStream out, String name, String value) {
        // 1. Exact match in static table → Indexed (0x80)
        int staticExact = HpackStaticTable.findExact(name, value);
        if (staticExact > 0) {
            encodeInteger(out, staticExact, 7, 0x80);
            return;
        }

        // 2. Exact match in dynamic table → Indexed (0x80)
        int dynExact = findDynamicExact(name, value);
        if (dynExact > 0) {
            // Dynamic table indexes follow static table: offset by static table size
            encodeInteger(out, HpackStaticTable.size() + dynExact, 7, 0x80);
            return;
        }

        // 3. Name-only match in static table → Literal with incremental indexing (0x40), indexed name
        int staticName = HpackStaticTable.findByName(name);
        if (staticName > 0) {
            encodeInteger(out, staticName, 6, 0x40);
            encodeStringHuffman(out, value);
            dynamicTable.add(name, value);
            return;
        }

        // 4. Name-only match in dynamic table → Literal with incremental indexing (0x40), indexed name
        int dynName = findDynamicByName(name);
        if (dynName > 0) {
            encodeInteger(out, HpackStaticTable.size() + dynName, 6, 0x40);
            encodeStringHuffman(out, value);
            dynamicTable.add(name, value);
            return;
        }

        // 5. New name → Literal with incremental indexing (0x40), new name
        out.write(0x40);
        encodeStringHuffman(out, name);
        encodeStringHuffman(out, value);
        dynamicTable.add(name, value);
    }

    /** Searches the dynamic table for an exact name+value match. Returns 1-based index or 0. */
    private int findDynamicExact(String name, String value) {
        int count = dynamicTable.count();
        for (int i = 1; i <= count; i++) {
            if (dynamicTable.name(i).equals(name) && dynamicTable.value(i).equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /** Searches the dynamic table for a name-only match. Returns 1-based index or 0. */
    private int findDynamicByName(String name) {
        int count = dynamicTable.count();
        for (int i = 1; i <= count; i++) {
            if (dynamicTable.name(i).equals(name)) {
                return i;
            }
        }
        return 0;
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

    /**
     * Encode une chaîne HPACK avec Huffman si plus court, sinon en littéral (RFC 7541 §5.2).
     */
    static void encodeStringHuffman(ByteArrayOutputStream out, String s) {
        byte[] huffman = HpackHuffman.encode(s);
        byte[] raw     = s.getBytes(StandardCharsets.ISO_8859_1);
        if (huffman.length < raw.length) {
            encodeInteger(out, huffman.length, 7, 0x80); // H=1
            out.write(huffman, 0, huffman.length);
        } else {
            encodeInteger(out, raw.length, 7, 0x00); // H=0
            out.write(raw, 0, raw.length);
        }
    }

    /** Encode une chaîne HPACK sans Huffman (RFC 7541 §5.2). Conservé pour compatibilité. */
    static void encodeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        encodeInteger(out, bytes.length, 7, 0x00); // H=0 (no Huffman)
        out.write(bytes, 0, bytes.length);
    }
}
