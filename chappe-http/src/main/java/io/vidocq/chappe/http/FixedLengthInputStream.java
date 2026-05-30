package io.vidocq.chappe.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * InputStream bounded by Content-Length.
 * <p>
 * First reads the remaining bytes from the shared {@link ByteBuffer},
 * then directly from the channel.
 */
final class FixedLengthInputStream extends InputStream {

    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;
    private long remaining;

    FixedLengthInputStream(ByteBuffer buffer, ReadableByteChannel channel, long contentLength) {
        this.buffer = buffer;
        this.channel = channel;
        this.remaining = contentLength;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;

        if (!buffer.hasRemaining()) {
            if (!refill()) return -1;
        }

        remaining--;
        return buffer.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) return -1;

        int toRead = (int) Math.min(len, remaining);

        if (!buffer.hasRemaining()) {
            if (!refill()) return -1;
        }

        int available = Math.min(toRead, buffer.remaining());
        buffer.get(b, off, available);
        remaining -= available;
        return available;
    }

    @Override
    public int available() {
        return (int) Math.min(buffer.remaining(), remaining);
    }

    /** Number of bytes remaining to read. */
    long remaining() {
        return remaining;
    }

    private boolean refill() throws IOException {
        buffer.compact();
        int read = channel.read(buffer);
        buffer.flip();
        return read > 0;
    }
}
