/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.RequestBodyTooLargeException;

/**
 * InputStream decoding chunked transfer encoding (RFC 9112, Section 7.1).
 * <p>
 * Format: {@code chunk-size(hex) CRLF chunk-data CRLF}, terminated by a chunk of size 0.
 * Trailer fields after the terminal chunk (RFC 9112 §7.1.2) are parsed and
 * delivered to the optional consumer, mirroring the HTTP/2 trailers path
 * ({@code Request.trailers()}).
 * <p>
 * The body is counted as it is on the wire against {@code maxBodySize} (CHAPPE-020): chunk data,
 * size lines with their extensions, CRLFs and the trailer section. A chunk's data is counted when
 * its size line is read, so a chunk that would take the body past the cap fails before any of its
 * data is read. One chunk-size line and one trailer line are each bounded by
 * {@link #MAX_CHUNK_LINE_LENGTH}, whatever the cap. A failed read is final: every later read
 * rethrows the same exception.
 */
final class ChunkedInputStream extends RequestBodyInputStream {

    /**
     * Longest chunk-size line (size, extensions, CRLF) or trailer line accepted, in bytes. It matches
     * the default {@code maxHeaderSize}: no legitimate line comes close, and the trailer parser must
     * not buffer a line without bound under a large {@code maxRequestSize}.
     */
    static final int MAX_CHUNK_LINE_LENGTH = 8192;

    private enum ChunkState {
        READ_SIZE,
        READ_DATA,
        READ_DATA_CRLF,
        DONE
    }

    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;
    private final Consumer<Headers> trailersConsumer;
    private final long maxBodySize;
    private ChunkState chunkState = ChunkState.READ_SIZE;
    private long chunkRemaining;
    /** Body bytes counted so far: framing bytes read and chunk sizes announced (CHAPPE-020). */
    private long wireBytes;
    /** The failure of an earlier read, rethrown by every later read. */
    private IOException failure;

    ChunkedInputStream(ByteBuffer buffer, ReadableByteChannel channel) {
        this(buffer, channel, null, Long.MAX_VALUE);
    }

    ChunkedInputStream(
            ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer, long maxBodySize) {
        this.buffer = buffer;
        this.channel = channel;
        this.trailersConsumer = trailersConsumer;
        this.maxBodySize = maxBodySize;
    }

    @Override
    int readByte() throws IOException {
        if (failure != null) throw failure;
        try {
            return decodeByte();
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    @Override
    int readBytes(byte[] b, int off, int len) throws IOException {
        if (failure != null) throw failure;
        try {
            return decodeBytes(b, off, len);
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    private int decodeByte() throws IOException {
        if (chunkState == ChunkState.DONE) return -1;

        while (true) {
            switch (chunkState) {
                case READ_SIZE -> {
                    readChunkSize();
                    if (chunkState == ChunkState.DONE) return -1;
                }
                case READ_DATA -> {
                    if (chunkRemaining > 0) {
                        if (!ensureData()) throw truncated();
                        chunkRemaining--;
                        return buffer.get() & 0xFF;
                    }
                    chunkState = ChunkState.READ_DATA_CRLF;
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

    private int decodeBytes(byte[] b, int off, int len) throws IOException {
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
                        // Bytes already read are returned rather than waiting on the socket for more.
                        if (totalRead > 0 && !buffer.hasRemaining()) return totalRead;
                        if (!ensureData()) throw truncated();
                        int toRead =
                                (int) Math.min(Math.min((long) len - totalRead, chunkRemaining), buffer.remaining());
                        buffer.get(b, off + totalRead, toRead);
                        chunkRemaining -= toRead;
                        totalRead += toRead;
                    }
                    if (chunkRemaining == 0) {
                        chunkState = ChunkState.READ_DATA_CRLF;
                        // A chunk ends here: the next chunk may not have arrived yet, and a
                        // streaming reader must see this one now (CHAPPE-010).
                        return totalRead;
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
        int lineLength = 0;

        while (true) {
            if (!ensureData()) {
                throw new IOException("Unexpected end of chunked stream");
            }
            int b = nextFramingByte();
            lineLength = countLineByte(lineLength);

            if (b == '\r') {
                continue;
            }
            if (b == '\n' || b == ';') {
                if (b == ';') {
                    // Chunk extension — ignore until LF, still counted and bounded
                    skipUntilLf(lineLength);
                } else if (!started) {
                    throw new IOException("Empty chunk size");
                }
                chunkRemaining = size;
                if (size == 0) {
                    // Terminal chunk — consume the trailer section and its final CRLF
                    consumeTrailers();
                    chunkState = ChunkState.DONE;
                } else {
                    countChunk(size);
                    chunkState = ChunkState.READ_DATA;
                }
                return;
            }

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

    /**
     * Counts a chunk's data against {@code maxBodySize} as soon as its size line is read: a chunk that
     * would take the body past the cap fails before any of its data is read (CHAPPE-020). The stream is
     * then {@link #failed()}, so the connection closes instead of draining it.
     */
    private void countChunk(long size) throws RequestBodyTooLargeException {
        if (size > maxBodySize - wireBytes) throw new RequestBodyTooLargeException(maxBodySize);
        wireBytes += size;
    }

    /**
     * Takes one framing byte (size line, extension, CRLF, trailer section) from the buffer, which the
     * caller made non-empty, and counts it against {@code maxBodySize} (CHAPPE-020).
     */
    private int nextFramingByte() throws RequestBodyTooLargeException {
        int b = buffer.get() & 0xFF;
        if (++wireBytes > maxBodySize) throw new RequestBodyTooLargeException(maxBodySize);
        return b;
    }

    /** Counts one more byte of the current line against {@link #MAX_CHUNK_LINE_LENGTH}. */
    private static int countLineByte(int lineLength) throws IOException {
        if (lineLength >= MAX_CHUNK_LINE_LENGTH) {
            throw new IOException("Chunked body line exceeds " + MAX_CHUNK_LINE_LENGTH + " bytes");
        }
        return lineLength + 1;
    }

    private void consumeCrlf() throws IOException {
        // Consume CRLF after chunk data
        while (true) {
            if (!ensureData()) return;
            int b = nextFramingByte();
            if (b == '\r') continue;
            if (b == '\n') return;
            // Unexpected byte — tolerate it (some servers only send LF)
            return;
        }
    }

    private void consumeTrailers() throws IOException {
        // Trailers are "name: value" lines terminated by an empty line.
        // Parsed (not just skipped) so Request.trailers() can expose them.
        StringBuilder line = new StringBuilder();
        int lineLength = 0;
        Headers.Builder trailers = null;
        while (true) {
            if (!ensureData()) break; // EOF — deliver what was parsed so far
            int b = nextFramingByte();
            lineLength = countLineByte(lineLength);
            if (b == '\r') continue;
            if (b == '\n') {
                if (line.isEmpty()) break; // empty line = end of trailers
                int colon = line.indexOf(":");
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (trailers == null) trailers = Headers.builder();
                    trailers.add(name, value);
                }
                line.setLength(0);
                lineLength = 0;
            } else {
                line.append((char) b); // header field bytes, latin-1
            }
        }
        if (trailersConsumer != null && trailers != null) {
            trailersConsumer.accept(trailers.build());
        }
    }

    /** Skips a chunk extension to the end of its size line, {@code lineLength} bytes into the line. */
    private void skipUntilLf(int lineLength) throws IOException {
        while (true) {
            if (!ensureData()) return;
            int b = nextFramingByte();
            lineLength = countLineByte(lineLength);
            if (b == '\n') return;
        }
    }

    /** The connection ended inside a chunk's data: the body is incomplete (CHAPPE-011). */
    private static java.io.EOFException truncated() {
        return new java.io.EOFException("Unexpected end of chunked stream inside a chunk");
    }

    private boolean ensureData() throws IOException {
        if (buffer.hasRemaining()) return true;
        return ReadBuffers.compactAndRead(buffer, channel) > 0;
    }

    private static int hexDigit(int b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
