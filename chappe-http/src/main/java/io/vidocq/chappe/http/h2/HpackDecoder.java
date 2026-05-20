package io.vidocq.chappe.http.h2;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.http.HttpRequestImpl;

/**
 * Décodeur HPACK (RFC 7541) — décode un header block compressé
 * en paires nom/valeur dans un {@link HttpRequestImpl}.
 */
public final class HpackDecoder {

    private final HpackDynamicTable dynamicTable;
    private final int maxHeaderListSize;

    public HpackDecoder(int dynamicTableMaxSize, int maxHeaderListSize) {
        this.dynamicTable = new HpackDynamicTable(dynamicTableMaxSize);
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Décode un header block complet dans la requête cible.
     */
    public void decode(ByteBuffer headerBlock, HttpRequestImpl target) throws Http2ConnectionException {
        int totalSize = 0;

        while (headerBlock.hasRemaining()) {
            int b = headerBlock.get(headerBlock.position()) & 0xFF;

            String name, value;

            if ((b & 0x80) != 0) {
                // Indexed Header Field (RFC 7541 §6.1) — 1xxxxxxx
                int index = decodeInteger(headerBlock, 7);
                name = lookupName(index);
                value = lookupValue(index);

            } else if ((b & 0xC0) == 0x40) {
                // Literal with Incremental Indexing (RFC 7541 §6.2.1) — 01xxxxxx
                int index = decodeInteger(headerBlock, 6);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);
                dynamicTable.add(name, value);

            } else if ((b & 0xF0) == 0x00) {
                // Literal without Indexing (RFC 7541 §6.2.2) — 0000xxxx
                int index = decodeInteger(headerBlock, 4);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);

            } else if ((b & 0xF0) == 0x10) {
                // Literal Never Indexed (RFC 7541 §6.2.3) — 0001xxxx
                int index = decodeInteger(headerBlock, 4);
                name = index > 0 ? lookupName(index) : decodeString(headerBlock);
                value = decodeString(headerBlock);

            } else {
                // Dynamic Table Size Update (RFC 7541 §6.3) — 001xxxxx
                int newSize = decodeInteger(headerBlock, 5);
                dynamicTable.setMaxSize(newSize);
                continue;
            }

            totalSize += name.length() + value.length() + 32;
            if (totalSize > maxHeaderListSize) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.ENHANCE_YOUR_CALM, "Header list exceeds max size " + maxHeaderListSize);
            }

            target.addHeader(name, value);
        }
    }

    /** Met à jour la taille max de la table dynamique (depuis SETTINGS). */
    public void updateMaxTableSize(int newMax) {
        dynamicTable.setMaxSize(newMax);
    }

    // --- Helpers ---

    /**
     * Décode un entier HPACK (RFC 7541 §5.1).
     */
    static int decodeInteger(ByteBuffer buf, int prefix) {
        int mask = (1 << prefix) - 1;
        int value = buf.get() & mask;

        if (value < mask) return value;

        int shift = 0;
        int b;
        do {
            b = buf.get() & 0xFF;
            value += (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return value;
    }

    /**
     * Décode une chaîne HPACK (RFC 7541 §5.2).
     */
    private String decodeString(ByteBuffer buf) {
        int b = buf.get(buf.position()) & 0xFF;
        boolean huffman = (b & 0x80) != 0;
        int length = decodeInteger(buf, 7);

        if (huffman) {
            return HpackHuffman.decode(buf, length);
        } else {
            byte[] bytes = new byte[length];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private String lookupName(int index) throws Http2ConnectionException {
        if (index <= HpackStaticTable.size()) {
            return HpackStaticTable.name(index);
        }
        int dynIndex = index - HpackStaticTable.size();
        if (dynIndex > dynamicTable.count()) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
        return dynamicTable.name(dynIndex);
    }

    private String lookupValue(int index) throws Http2ConnectionException {
        if (index <= HpackStaticTable.size()) {
            return HpackStaticTable.value(index);
        }
        int dynIndex = index - HpackStaticTable.size();
        if (dynIndex > dynamicTable.count()) {
            throw new Http2ConnectionException(Http2ErrorCode.COMPRESSION_ERROR, "Invalid HPACK index: " + index);
        }
        return dynamicTable.value(dynIndex);
    }
}
