package fr.vidocq.chappe.http.h2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes HTTP/2 frames to a {@link WritableByteChannel}.
 *
 * <p>Uses a {@link ReentrantLock} instead of {@code synchronized} to avoid
 * pinning virtual threads (Project Loom compatibility).
 *
 * <p>Thread-safe: multiple virtual threads may call write methods concurrently.
 * The lock ensures frames are written atomically and not interleaved.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9113">RFC 9113 – HTTP/2</a>
 */
public final class Http2FrameWriter {

    /** HTTP/2 frame header size in bytes (RFC 9113 §4.1). */
    private static final int FRAME_HEADER_SIZE = 9;

    /** Default maximum frame size (RFC 9113 §6.5.2). */
    private static final int DEFAULT_MAX_FRAME_SIZE = 16_384;

    private final ByteBuffer writeBuffer;
    private final WritableByteChannel channel;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final int maxFrameSize;

    /**
     * Creates a new {@code Http2FrameWriter}.
     *
     * @param writeBuffer the write-side buffer (caller owns its capacity)
     * @param channel     the channel to write frames to
     */
    public Http2FrameWriter(ByteBuffer writeBuffer, WritableByteChannel channel) {
        this.writeBuffer = writeBuffer;
        this.channel = channel;
        this.maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    }

    /**
     * Creates a new {@code Http2FrameWriter} with a configurable max frame size.
     *
     * @param writeBuffer  the write-side buffer
     * @param channel      the channel to write frames to
     * @param maxFrameSize the negotiated SETTINGS_MAX_FRAME_SIZE
     */
    public Http2FrameWriter(ByteBuffer writeBuffer, WritableByteChannel channel, int maxFrameSize) {
        this.writeBuffer = writeBuffer;
        this.channel = channel;
        this.maxFrameSize = maxFrameSize;
    }

    // -------------------------------------------------------------------------
    // Public write methods
    // -------------------------------------------------------------------------

    /**
     * Writes a generic HTTP/2 frame.
     *
     * <p>Frame format (RFC 9113 §4.1):
     * <pre>
     *  +-----------------------------------------------+
     *  |                 Length (24)                   |
     *  +---------------+---------------+---------------+
     *  |   Type (8)    |   Flags (8)   |
     *  +-+-------------+---------------+-------------------------------+
     *  |R|                 Stream Identifier (31)                      |
     *  +=+=============================================================+
     *  |                   Frame Payload (0...)                        |
     *  +---------------------------------------------------------------+
     * </pre>
     *
     * @param type     frame type byte
     * @param flags    frame flags byte
     * @param streamId stream identifier (R bit is masked out)
     * @param payload  frame payload bytes
     * @param off      offset into payload
     * @param len      number of payload bytes to write
     */
    public void writeFrame(int type, int flags, int streamId, byte[] payload, int off, int len) {
        writeLock.lock();
        try {
            writeFrameHeader(len, type, flags, streamId);
            writeBytes(payload, off, len);
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a SETTINGS frame (type=0x4) carrying the given settings.
     *
     * @param settings the settings to send
     */
    public void writeSettings(Http2Settings settings) {
        writeLock.lock();
        try {
            int payloadLen = settings.wireSize();
            writeFrameHeader(payloadLen, Http2Frame.TYPE_SETTINGS, 0, 0);
            ensureCapacity(payloadLen);
            settings.writeTo(writeBuffer);
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a SETTINGS ACK frame (type=0x4, flags=ACK, empty payload).
     */
    public void writeSettingsAck() {
        writeLock.lock();
        try {
            writeFrameHeader(0, Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0);
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a PING ACK frame (type=0x6, flags=ACK) with the original opaque data.
     *
     * @param opaqueData the 8-byte opaque data from the received PING
     */
    public void writePingAck(long opaqueData) {
        writeLock.lock();
        try {
            writeFrameHeader(8, Http2Frame.TYPE_PING, Http2Frame.FLAG_ACK, 0);
            ensureCapacity(8);
            writeBuffer.putLong(opaqueData);
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a GOAWAY frame (type=0x7).
     *
     * <p>Payload: Last-Stream-ID (31 bits) + Error Code (32 bits).
     *
     * @param lastStreamId the highest-numbered stream the sender processed
     * @param error        the error code
     */
    public void writeGoaway(int lastStreamId, Http2ErrorCode error) {
        writeLock.lock();
        try {
            writeFrameHeader(8, Http2Frame.TYPE_GOAWAY, 0, 0);
            ensureCapacity(8);
            writeBuffer.putInt(lastStreamId & 0x7FFFFFFF);
            writeBuffer.putInt(error.code());
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a RST_STREAM frame (type=0x3).
     *
     * <p>Payload: Error Code (32 bits).
     *
     * @param streamId the stream to reset
     * @param error    the error code
     */
    public void writeRstStream(int streamId, Http2ErrorCode error) {
        writeLock.lock();
        try {
            writeFrameHeader(4, Http2Frame.TYPE_RST_STREAM, 0, streamId);
            ensureCapacity(4);
            writeBuffer.putInt(error.code());
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a WINDOW_UPDATE frame (type=0x8).
     *
     * <p>Payload: Window Size Increment (31 bits).
     *
     * @param streamId  the stream (0 for connection-level)
     * @param increment the window size increment
     */
    public void writeWindowUpdate(int streamId, int increment) {
        writeLock.lock();
        try {
            writeFrameHeader(4, Http2Frame.TYPE_WINDOW_UPDATE, 0, streamId);
            ensureCapacity(4);
            writeBuffer.putInt(increment & 0x7FFFFFFF);
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes HEADERS (and optional CONTINUATION) frames for the given encoded header block.
     *
     * <p>HEADERS and CONTINUATION frames for the same header block MUST be contiguous
     * (RFC 9113 §6.2), so the entire sequence is written under a single lock acquisition.
     *
     * @param streamId       the stream identifier
     * @param encodedHeaders the HPACK-encoded header block
     * @param endStream      whether to set the END_STREAM flag on the HEADERS frame
     */
    public void writeHeaders(int streamId, byte[] encodedHeaders, boolean endStream) {
        writeLock.lock();
        try {
            int totalLen = encodedHeaders.length;
            int offset = 0;

            while (offset < totalLen || totalLen == 0) {
                int chunkLen = Math.min(maxFrameSize, totalLen - offset);
                boolean isFirst = (offset == 0);
                boolean isLast = (offset + chunkLen >= totalLen);

                if (isFirst) {
                    // HEADERS frame
                    int flags = 0;
                    if (isLast) flags |= Http2Frame.FLAG_END_HEADERS;
                    if (endStream) flags |= Http2Frame.FLAG_END_STREAM;
                    writeFrameHeader(chunkLen, Http2Frame.TYPE_HEADERS, flags, streamId);
                } else {
                    // CONTINUATION frame
                    int flags = isLast ? Http2Frame.FLAG_END_HEADERS : 0;
                    writeFrameHeader(chunkLen, Http2Frame.TYPE_CONTINUATION, flags, streamId);
                }

                writeBytes(encodedHeaders, offset, chunkLen);
                offset += chunkLen;

                if (totalLen == 0) break; // empty header block: single HEADERS frame
            }

            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes DATA frame(s) for the given data range.
     *
     * <p>Large payloads are split into multiple DATA frames of at most
     * {@code maxFrameSize} bytes each.
     *
     * @param streamId  the stream identifier
     * @param data      the data bytes
     * @param off       offset into data
     * @param len       number of bytes to send
     * @param endStream whether to set END_STREAM on the last DATA frame
     */
    public void writeData(int streamId, byte[] data, int off, int len, boolean endStream) {
        writeLock.lock();
        try {
            int remaining = len;
            int offset = off;

            if (remaining == 0) {
                // Empty DATA frame (e.g. trailers sentinel)
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a 9-byte frame header into the write buffer, flushing if necessary.
     *
     * @param payloadLen payload length (24-bit value)
     * @param type       frame type (8 bits)
     * @param flags      frame flags (8 bits)
     * @param streamId   stream identifier (R bit masked to 0)
     */
    private void writeFrameHeader(int payloadLen, int type, int flags, int streamId)
            throws IOException {
        ensureCapacity(FRAME_HEADER_SIZE);
        // Length: 3 bytes big-endian
        writeBuffer.put((byte) ((payloadLen >>> 16) & 0xFF));
        writeBuffer.put((byte) ((payloadLen >>> 8) & 0xFF));
        writeBuffer.put((byte) (payloadLen & 0xFF));
        // Type: 1 byte
        writeBuffer.put((byte) (type & 0xFF));
        // Flags: 1 byte
        writeBuffer.put((byte) (flags & 0xFF));
        // Stream identifier: 4 bytes (R bit = 0, RFC 9113 §4.1)
        writeBuffer.putInt(streamId & 0x7FFFFFFF);
    }

    /**
     * Writes bytes from an array into the channel, splitting across buffer flushes as needed.
     *
     * @param src source byte array
     * @param off offset
     * @param len number of bytes
     */
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

    /**
     * Ensures the write buffer has at least {@code needed} bytes of remaining capacity.
     * Flushes the buffer to make room if necessary.
     *
     * @param needed minimum number of bytes required
     */
    private void ensureCapacity(int needed) throws IOException {
        if (writeBuffer.remaining() < needed) {
            flushBuffer();
        }
    }

    /**
     * Flips the buffer, drains it fully to the channel, then clears it for reuse.
     */
    private void flush() throws IOException {
        flushBuffer();
    }

    /**
     * Internal flush: flip → write loop → clear.
     */
    private void flushBuffer() throws IOException {
        writeBuffer.flip();
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }
        writeBuffer.clear();
    }
}
