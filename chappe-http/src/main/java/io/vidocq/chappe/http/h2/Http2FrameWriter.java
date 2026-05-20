package io.vidocq.chappe.http.h2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes HTTP/2 frames to a {@link WritableByteChannel}.
 * <p>
 * Uses a {@link ReentrantLock} instead of {@code synchronized} to avoid
 * pinning virtual threads.
 * <p>
 * Thread-safe : plusieurs virtual threads peuvent appeler les méthodes d'écriture
 * concurremment. Le lock garantit que les frames ne sont pas entrelacées.
 */
public final class Http2FrameWriter {

    private static final int FRAME_HEADER_SIZE = 9;
    private static final int DEFAULT_MAX_FRAME_SIZE = 16_384;

    private final ByteBuffer writeBuffer;
    private final WritableByteChannel channel;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final int maxFrameSize;

    public Http2FrameWriter(ByteBuffer writeBuffer, WritableByteChannel channel) {
        this(writeBuffer, channel, DEFAULT_MAX_FRAME_SIZE);
    }

    public Http2FrameWriter(ByteBuffer writeBuffer, WritableByteChannel channel, int maxFrameSize) {
        this.writeBuffer = writeBuffer;
        this.channel = channel;
        this.maxFrameSize = maxFrameSize;
    }

    // -------------------------------------------------------------------------
    // Méthodes publiques — toutes throws IOException
    // -------------------------------------------------------------------------

    public void writeFrame(int type, int flags, int streamId, byte[] payload, int off, int len) throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(len, type, flags, streamId);
            writeBytes(payload, off, len);
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeSettings(Http2Settings settings) throws IOException {
        writeLock.lock();
        try {
            int payloadLen = settings.wireSize();
            writeFrameHeader(payloadLen, Http2Frame.TYPE_SETTINGS, 0, 0);
            ensureCapacity(payloadLen);
            settings.writeTo(writeBuffer);
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeSettingsAck() throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(0, Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0);
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writePingAck(long opaqueData) throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(8, Http2Frame.TYPE_PING, Http2Frame.FLAG_ACK, 0);
            ensureCapacity(8);
            writeBuffer.putLong(opaqueData);
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeGoaway(int lastStreamId, Http2ErrorCode error) throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(8, Http2Frame.TYPE_GOAWAY, 0, 0);
            ensureCapacity(8);
            writeBuffer.putInt(lastStreamId & 0x7FFFFFFF);
            writeBuffer.putInt(error.code());
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeRstStream(int streamId, Http2ErrorCode error) throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(4, Http2Frame.TYPE_RST_STREAM, 0, streamId);
            ensureCapacity(4);
            writeBuffer.putInt(error.code());
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeWindowUpdate(int streamId, int increment) throws IOException {
        writeLock.lock();
        try {
            writeFrameHeader(4, Http2Frame.TYPE_WINDOW_UPDATE, 0, streamId);
            ensureCapacity(4);
            writeBuffer.putInt(increment & 0x7FFFFFFF);
            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeHeaders(int streamId, byte[] encodedHeaders, boolean endStream) throws IOException {
        writeLock.lock();
        try {
            int totalLen = encodedHeaders.length;
            int offset = 0;

            while (offset < totalLen || totalLen == 0) {
                int chunkLen = Math.min(maxFrameSize, totalLen - offset);
                boolean isFirst = (offset == 0);
                boolean isLast = (offset + chunkLen >= totalLen);

                if (isFirst) {
                    int flags = 0;
                    if (isLast) flags |= Http2Frame.FLAG_END_HEADERS;
                    if (endStream) flags |= Http2Frame.FLAG_END_STREAM;
                    writeFrameHeader(chunkLen, Http2Frame.TYPE_HEADERS, flags, streamId);
                } else {
                    int flags = isLast ? Http2Frame.FLAG_END_HEADERS : 0;
                    writeFrameHeader(chunkLen, Http2Frame.TYPE_CONTINUATION, flags, streamId);
                }

                writeBytes(encodedHeaders, offset, chunkLen);
                offset += chunkLen;

                if (totalLen == 0) break;
            }

            flush();
        } finally {
            writeLock.unlock();
        }
    }

    public void writeData(int streamId, byte[] data, int off, int len, boolean endStream) throws IOException {
        writeLock.lock();
        try {
            int remaining = len;
            int offset = off;

            if (remaining == 0) {
                int flags = endStream ? Http2Frame.FLAG_END_STREAM : 0;
                writeFrameHeader(0, Http2Frame.TYPE_DATA, flags, streamId);
            } else {
                while (remaining > 0) {
                    int chunkLen = Math.min(maxFrameSize, remaining);
                    boolean isLast = (remaining <= maxFrameSize);
                    int flags = (isLast && endStream) ? Http2Frame.FLAG_END_STREAM : 0;
                    writeFrameHeader(chunkLen, Http2Frame.TYPE_DATA, flags, streamId);
                    writeBytes(data, offset, chunkLen);
                    offset += chunkLen;
                    remaining -= chunkLen;
                }
            }

            flush();
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers internes
    // -------------------------------------------------------------------------

    private void writeFrameHeader(int payloadLen, int type, int flags, int streamId) throws IOException {
        ensureCapacity(FRAME_HEADER_SIZE);
        writeBuffer.put((byte) ((payloadLen >>> 16) & 0xFF));
        writeBuffer.put((byte) ((payloadLen >>> 8) & 0xFF));
        writeBuffer.put((byte) (payloadLen & 0xFF));
        writeBuffer.put((byte) (type & 0xFF));
        writeBuffer.put((byte) (flags & 0xFF));
        writeBuffer.putInt(streamId & 0x7FFFFFFF);
    }

    private void writeBytes(byte[] src, int off, int len) throws IOException {
        int remaining = len;
        int offset = off;
        while (remaining > 0) {
            int space = writeBuffer.remaining();
            if (space == 0) {
                flushBuffer();
                space = writeBuffer.remaining();
            }
            int toWrite = Math.min(space, remaining);
            writeBuffer.put(src, offset, toWrite);
            offset += toWrite;
            remaining -= toWrite;
        }
    }

    private void ensureCapacity(int needed) throws IOException {
        if (writeBuffer.remaining() < needed) {
            flushBuffer();
        }
    }

    private void flush() throws IOException {
        flushBuffer();
    }

    private void flushBuffer() throws IOException {
        writeBuffer.flip();
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }
        writeBuffer.clear();
    }
}
