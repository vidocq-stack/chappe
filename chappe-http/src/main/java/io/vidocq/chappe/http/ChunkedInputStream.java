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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * InputStream decoding chunked transfer encoding (RFC 9112, Section 7.1).
 * <p>
 * Format: {@code chunk-size(hex) CRLF chunk-data CRLF}, terminated by a chunk of size 0.
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
                        int toRead =
                                (int) Math.min(Math.min((long) len - totalRead, chunkRemaining), buffer.remaining());
                        buffer.get(b, off + totalRead, toRead);
                        chunkRemaining -= toRead;
                        totalRead += toRead;
                    }
                    if (chunkRemaining == 0) {
                        chunkState = ChunkState.READ_DATA_CRLF;
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

        while (true) {
            if (!ensureData()) {
                throw new IOException("Unexpected end of chunked stream");
            }
            int b = buffer.get() & 0xFF;

            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                if (!started) {
                    throw new IOException("Empty chunk size");
                }
                chunkRemaining = size;
                if (size == 0) {
                    // Terminal chunk — consume final trailer CRLF
                    consumeTrailers();
                    chunkState = ChunkState.DONE;
                } else {
                    chunkState = ChunkState.READ_DATA;
                }
                return;
            }
            if (b == ';') {
                // Chunk extension — ignore until CRLF
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
        // Consume CRLF after chunk data
        while (true) {
            if (!ensureData()) return;
            int b = buffer.get() & 0xFF;
            if (b == '\r') continue;
            if (b == '\n') return;
            // Unexpected byte — tolerate it (some servers only send LF)
            return;
        }
    }

    private void consumeTrailers() throws IOException {
        // Trailers are header lines terminated by an empty line
        boolean lineStart = true;
        while (true) {
            if (!ensureData()) return;
            int b = buffer.get() & 0xFF;
            if (b == '\r') continue;
            if (b == '\n') {
                if (lineStart) return; // Empty line = end of trailers
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
