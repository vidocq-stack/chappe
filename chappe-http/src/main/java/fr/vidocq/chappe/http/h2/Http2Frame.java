package fr.vidocq.chappe.http.h2;

import java.nio.ByteBuffer;

/**
 * Frames HTTP/2 (RFC 9113, Section 4).
 * <p>
 * Sealed interface avec un record par type de frame.
 */
public sealed interface Http2Frame {

    // Types de frame (RFC 9113, Section 6)
    int TYPE_DATA          = 0x0;
    int TYPE_HEADERS       = 0x1;
    int TYPE_PRIORITY      = 0x2;
    int TYPE_RST_STREAM    = 0x3;
    int TYPE_SETTINGS      = 0x4;
    int TYPE_PUSH_PROMISE  = 0x5;
    int TYPE_PING          = 0x6;
    int TYPE_GOAWAY        = 0x7;
    int TYPE_WINDOW_UPDATE = 0x8;
    int TYPE_CONTINUATION  = 0x9;

    // Flags communs
    int FLAG_END_STREAM  = 0x1;
    int FLAG_END_HEADERS = 0x4;
    int FLAG_PADDED      = 0x8;
    int FLAG_PRIORITY    = 0x20;
    int FLAG_ACK         = 0x1;

    int streamId();
    int flags();

    record DataFrame(int streamId, int flags, ByteBuffer data, int padding)
            implements Http2Frame {
        public boolean endStream() { return (flags & FLAG_END_STREAM) != 0; }
    }

    record HeadersFrame(int streamId, int flags, ByteBuffer headerBlock,
                         boolean priority, int streamDependency, int weight)
            implements Http2Frame {
        public boolean endStream() { return (flags & FLAG_END_STREAM) != 0; }
        public boolean endHeaders() { return (flags & FLAG_END_HEADERS) != 0; }
    }

    record RstStreamFrame(int streamId, int flags, int errorCode)
            implements Http2Frame {}

    record SettingsFrame(int streamId, int flags, ByteBuffer payload)
            implements Http2Frame {
        public boolean ack() { return (flags & FLAG_ACK) != 0; }
    }

    record PingFrame(int streamId, int flags, long opaqueData)
            implements Http2Frame {
        public boolean ack() { return (flags & FLAG_ACK) != 0; }
    }

    record GoawayFrame(int streamId, int flags, int lastStreamId,
                        int errorCode, ByteBuffer debugData)
            implements Http2Frame {}

    record WindowUpdateFrame(int streamId, int flags, int windowIncrement)
            implements Http2Frame {}

    record ContinuationFrame(int streamId, int flags, ByteBuffer headerBlock)
            implements Http2Frame {
        public boolean endHeaders() { return (flags & FLAG_END_HEADERS) != 0; }
    }

    record UnknownFrame(int type, int streamId, int flags, ByteBuffer payload)
            implements Http2Frame {}
}
