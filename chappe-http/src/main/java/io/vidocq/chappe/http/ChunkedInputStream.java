package io.vidocq.chappe.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * InputStream décodant le chunked transfer encoding (RFC 9112, Section 7.1).
 * <p>
 * Format : {@code chunk-size(hex) CRLF chunk-data CRLF}, terminé par un chunk de taille 0.
 */
final class ChunkedInputStream extends InputStream {

    private enum ChunkState {
        READ_SIZE,
        READ_DATA,
        READ_DATA_CRLF,
        DONE
    }

    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;
    private ChunkState chunkState = ChunkState.READ_SIZE;
    private long chunkRemaining;
    private boolean crSeen;

    ChunkedInputStream(ByteBuffer buffer, ReadableByteChannel channel) {
        this.buffer = buffer;
        this.channel = channel;
    }

    @Override
    public int read() throws IOException {
        if (chunkState == ChunkState.DONE) return -1;

        while (true) {
            switch (chunkState) {
                case READ_SIZE -> {
                    readChunkSize();
                    if (chunkState == ChunkState.DONE) return -1;
                }
                case READ_DATA -> {
                    if (chunkRemaining > 0) {
                        if (!ensureData()) return -1;
                        chunkRemaining--;
                        return buffer.get() & 0xFF;
                    }
                    chunkState = ChunkState.READ_DATA_CRLF;
                    crSeen = false;
                }
                case READ_DATA_CRLF -> {
                    consumeCrlf();
                    chunkState = ChunkState.READ_SIZE;
                }
                case DONE -> {
                    return -1;
                }
            }
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (chunkState == ChunkState.DONE) return -1;

        int totalRead = 0;
        while (totalRead < len) {
            switch (chunkState) {
                case READ_SIZE -> {
                    readChunkSize();
                    if (chunkState == ChunkState.DONE) {
                        return totalRead > 0 ? totalRead : -1;
                    }
                }
                case READ_DATA -> {
                    if (chunkRemaining > 0) {
                        if (!ensureData()) {
                            return totalRead > 0 ? totalRead : -1;
                        }
                        int toRead = (int) Math.min(Math.min(len - totalRead, chunkRemaining), buffer.remaining());
                        buffer.get(b, off + totalRead, toRead);
                        chunkRemaining -= toRead;
                        totalRead += toRead;
                    }
                    if (chunkRemaining == 0) {
                        chunkState = ChunkState.READ_DATA_CRLF;
                        crSeen = false;
                    }
                }
                case READ_DATA_CRLF -> {
                    consumeCrlf();
                    chunkState = ChunkState.READ_SIZE;
                }
                case DONE -> {
                    return totalRead > 0 ? totalRead : -1;
                }
            }
        }
        return totalRead;
    }

    @Override
    public int available() {
        if (chunkState == ChunkState.READ_DATA && chunkRemaining > 0) {
            return (int) Math.min(buffer.remaining(), chunkRemaining);
        }
        return 0;
    }

    private void readChunkSize() throws IOException {
        long size = 0;
        boolean started = false;
        crSeen = false;

        while (true) {
            if (!ensureData()) {
                throw new IOException("Unexpected end of chunked stream");
            }
            int b = buffer.get() & 0xFF;

            if (b == '\r') {
                crSeen = true;
                continue;
            }
            if (b == '\n') {
                if (!started) {
                    throw new IOException("Empty chunk size");
                }
                chunkRemaining = size;
                if (size == 0) {
                    // Terminal chunk — consommer le CRLF final des trailers
                    consumeTrailers();
                    chunkState = ChunkState.DONE;
                } else {
                    chunkState = ChunkState.READ_DATA;
                }
                return;
            }
            if (b == ';') {
                // Chunk extension — ignorer jusqu'au CRLF
                skipUntilLf();
                chunkRemaining = size;
                if (size == 0) {
                    consumeTrailers();
                    chunkState = ChunkState.DONE;
                } else {
                    chunkState = ChunkState.READ_DATA;
                }
                return;
            }

            crSeen = false;
            started = true;
            int digit = hexDigit(b);
            if (digit < 0) {
                throw new IOException("Invalid hex digit in chunk size: " + (char) b);
            }
            if (size > (Long.MAX_VALUE >> 4)) {
                throw new IOException("Chunk size overflow");
            }
            size = (size << 4) | digit;
        }
    }

    private void consumeCrlf() throws IOException {
        // Consomme le CRLF après les données du chunk
        while (true) {
            if (!ensureData()) return;
            int b = buffer.get() & 0xFF;
            if (b == '\r') continue;
            if (b == '\n') return;
            // Octet inattendu — tolérer (certains serveurs n'envoient que LF)
            return;
        }
    }

    private void consumeTrailers() throws IOException {
        // Les trailers sont des lignes de headers terminées par une ligne vide
        boolean lineStart = true;
        while (true) {
            if (!ensureData()) return;
            int b = buffer.get() & 0xFF;
            if (b == '\r') continue;
            if (b == '\n') {
                if (lineStart) return; // Ligne vide = fin des trailers
                lineStart = true;
            } else {
                lineStart = false;
            }
        }
    }

    private void skipUntilLf() throws IOException {
        while (true) {
            if (!ensureData()) return;
            int b = buffer.get() & 0xFF;
            if (b == '\n') return;
        }
    }

    private boolean ensureData() throws IOException {
        if (buffer.hasRemaining()) return true;
        buffer.compact();
        int read = channel.read(buffer);
        buffer.flip();
        return read > 0;
    }

    private static int hexDigit(int b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
