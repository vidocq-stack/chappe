package io.vidocq.chappe.http.h2;

import java.nio.ByteBuffer;

/**
 * Paramètres HTTP/2 (RFC 9113, Section 6.5).
 */
public record Http2Settings(
        int headerTableSize,
        boolean enablePush,
        int maxConcurrentStreams,
        int initialWindowSize,
        int maxFrameSize,
        int maxHeaderListSize) {

    // Identifiants des paramètres (RFC 9113, Section 6.5.1)
    public static final int HEADER_TABLE_SIZE = 0x1;
    public static final int ENABLE_PUSH = 0x2;
    public static final int MAX_CONCURRENT_STREAMS = 0x3;
    public static final int INITIAL_WINDOW_SIZE = 0x4;
    public static final int MAX_FRAME_SIZE = 0x5;
    public static final int MAX_HEADER_LIST_SIZE = 0x6;

    public static final Http2Settings DEFAULT = new Http2Settings(4096, false, 100, 65535, 16384, 8192);

    /** Parse un payload SETTINGS et retourne les settings mis à jour. */
    public Http2Settings applyFrom(ByteBuffer payload, int length) {
        int htSize = headerTableSize;
        boolean push = enablePush;
        int maxStreams = maxConcurrentStreams;
        int winSize = initialWindowSize;
        int maxFrame = maxFrameSize;
        int maxHeader = maxHeaderListSize;

        for (int i = 0; i < length; i += 6) {
            int id = payload.getShort() & 0xFFFF;
            int value = payload.getInt();
            switch (id) {
                case HEADER_TABLE_SIZE -> htSize = value;
                case ENABLE_PUSH -> push = (value != 0);
                case MAX_CONCURRENT_STREAMS -> maxStreams = value;
                case INITIAL_WINDOW_SIZE -> winSize = value;
                case MAX_FRAME_SIZE -> maxFrame = value;
                case MAX_HEADER_LIST_SIZE -> maxHeader = value;
                default -> {} // ignorer les settings inconnus (RFC 9113 §6.5.2)
            }
        }
        return new Http2Settings(htSize, push, maxStreams, winSize, maxFrame, maxHeader);
    }

    /** Encode les settings serveur dans un buffer. */
    public void writeTo(ByteBuffer buf) {
        putSetting(buf, MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
        putSetting(buf, INITIAL_WINDOW_SIZE, initialWindowSize);
        putSetting(buf, MAX_FRAME_SIZE, maxFrameSize);
        putSetting(buf, MAX_HEADER_LIST_SIZE, maxHeaderListSize);
    }

    /** Nombre d'octets écrits par {@link #writeTo}. */
    public int wireSize() {
        return 4 * 6; // 4 settings × 6 bytes chacun
    }

    private static void putSetting(ByteBuffer buf, int id, int value) {
        buf.putShort((short) id);
        buf.putInt(value);
    }
}
