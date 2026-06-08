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
package io.vidocq.chappe.http.grpc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a gRPC message (5-byte prefix + payload) from an InputStream.
 * <p>
 * Format (gRPC Core protocol):
 * <pre>
 * +--------+----------------+------------------------+
 * | 1 byte | 4 bytes (BE)   | N bytes                |
 * | compr  | length         | payload                |
 * +--------+----------------+------------------------+
 * </pre>
 * <ul>
 *   <li>{@code compr}: compression flag (0 = identity, 1 = compressed with {@code grpc-encoding})</li>
 *   <li>{@code length}: payload length, big-endian, unsigned</li>
 * </ul>
 */
public final class GrpcFrameReader {

    /** Maximum size of a received message (4 MiB by default, aligned with grpc-java). */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    /**
     * Decompressor for an already extracted payload. Receives the compressed bytes,
     * must return the decompressed bytes. Throws {@link IOException} if the
     * format is corrupted.
     */
    @FunctionalInterface
    public interface Decompressor {
        byte[] decompress(byte[] compressed) throws IOException;
    }

    private final int maxMessageSize;
    private final Decompressor decompressor;

    public GrpcFrameReader() {
        this(DEFAULT_MAX_MESSAGE_SIZE, null);
    }

    public GrpcFrameReader(int maxMessageSize) {
        this(maxMessageSize, null);
    }

    /**
     * @param decompressor decompressor applied to messages whose prefix has
     *                     {@code compressed=1}. If {@code null}, an incoming
     *                     compressed message causes a {@link GrpcFrameException}.
     */
    public GrpcFrameReader(int maxMessageSize, Decompressor decompressor) {
        this.maxMessageSize = maxMessageSize;
        this.decompressor = decompressor;
    }

    /**
     * Reads the next message.
     *
     * @return the payload bytes (decompressed if needed), or {@code null}
     *         if the InputStream is closed (clean EOF)
     * @throws IOException        if I/O fails or the format is invalid
     * @throws GrpcFrameException if the length exceeds {@code maxMessageSize}
     *                            or if a compressed message arrives without a
     *                            configured decompressor
     */
    public byte[] readMessage(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null; // EOF propre entre messages

        int compressed = b0 & 0xFF;
        if (compressed != 0 && compressed != 1) {
            throw new GrpcFrameException("Invalid gRPC compression flag: " + compressed);
        }

        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new EOFException("Truncated gRPC frame header");
        }

        long len = ((long) (b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        if (len > maxMessageSize) {
            throw new GrpcFrameException("gRPC message exceeds max size: " + len + " > " + maxMessageSize);
        }

        byte[] payload = in.readNBytes((int) len);
        if (payload.length != len) {
            throw new EOFException("Truncated gRPC payload: got " + payload.length + " / " + len);
        }

        if (compressed == 1) {
            if (decompressor == null) {
                throw new GrpcFrameException("gRPC compressed message received but no decompressor configured "
                        + "(client should send grpc-encoding header or use identity)");
            }
            return decompressor.decompress(payload);
        }
        return payload;
    }
}
