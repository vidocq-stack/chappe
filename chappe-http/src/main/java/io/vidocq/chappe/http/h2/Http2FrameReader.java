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
package io.vidocq.chappe.http.h2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * Reads HTTP/2 frames from a {@link ReadableByteChannel} using a shared {@link ByteBuffer}.
 *
 * <p>Frames are parsed according to RFC 9113, Section 4.1:
 * <pre>
 *   +-----------------------------------------------+
 *   |                 Length (24)                    |
 *   +---------------+---------------+---------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+-------------------------------+
 *   |R|                 Stream Identifier (31)                      |
 *   +=+=============================================================+
 *   |                   Frame Payload (0...)                      ...
 *   +---------------------------------------------------------------+
 * </pre>
 */
public final class Http2FrameReader {

    /** Fixed size of the HTTP/2 frame header in bytes. */
    public static final int FRAME_HEADER_SIZE = 9;

    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;

    /**
     * Creates a new reader backed by the given buffer and channel.
     *
     * @param readBuffer the shared read buffer (must be in write mode initially, or empty)
     * @param channel    the channel to read from
     */
    public Http2FrameReader(ByteBuffer readBuffer, ReadableByteChannel channel) {
        this.buffer = readBuffer;
        this.channel = channel;
    }

    /**
     * Reads the next HTTP/2 frame from the channel.
     *
     * @param maxFrameSize the maximum allowed frame payload size (from peer SETTINGS)
     * @return the parsed frame, or {@code null} for PRIORITY frames (deprecated, RFC 9113 §5.3.2)
     * @throws Http2ConnectionException if a frame size or protocol error is detected
     * @throws EOFException             if the channel is closed before a full frame is read
     * @throws IOException              on I/O error
     */
    public Http2Frame readFrame(int maxFrameSize) throws IOException {
        ensureReadable(FRAME_HEADER_SIZE);

        // Length: 24-bit big-endian unsigned integer
        int length = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);

        int type = buffer.get() & 0xFF;
        int flags = buffer.get() & 0xFF;
        int streamId = buffer.getInt() & 0x7FFFFFFF;

        if (length > maxFrameSize) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR,
                    "Frame length " + length + " exceeds max frame size " + maxFrameSize);
        }

        ensureReadable(length);

        return switch (type) {
            case Http2Frame.TYPE_DATA -> readDataFrame(streamId, flags, length);
            case Http2Frame.TYPE_HEADERS -> readHeadersFrame(streamId, flags, length);
            case Http2Frame.TYPE_PRIORITY -> {
                skipBytes(length);
                yield null;
            }
            case Http2Frame.TYPE_RST_STREAM -> readRstStreamFrame(streamId, flags, length);
            case Http2Frame.TYPE_SETTINGS -> readSettingsFrame(streamId, flags, length);
            case Http2Frame.TYPE_PING -> readPingFrame(streamId, flags, length);
            case Http2Frame.TYPE_GOAWAY -> readGoawayFrame(streamId, flags, length);
            case Http2Frame.TYPE_WINDOW_UPDATE -> readWindowUpdateFrame(streamId, flags, length);
            case Http2Frame.TYPE_CONTINUATION -> readContinuationFrame(streamId, flags, length);
            default -> readUnknownFrame(type, streamId, flags, length);
        };
    }

    // -------------------------------------------------------------------------
    // Frame-specific readers
    // -------------------------------------------------------------------------

    private Http2Frame.DataFrame readDataFrame(int streamId, int flags, int length) throws IOException {
        int consumed = 0;
        int padding = 0;

        if ((flags & Http2Frame.FLAG_PADDED) != 0) {
            padding = buffer.get() & 0xFF;
            consumed += 1;
        }

        int dataLength = length - consumed - padding;
        if (dataLength < 0) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "DATA frame: pad length exceeds payload length");
        }

        byte[] dataBytes = new byte[dataLength];
        buffer.get(dataBytes);

        skipBytes(padding);

        return new Http2Frame.DataFrame(streamId, flags, ByteBuffer.wrap(dataBytes), padding);
    }

    private Http2Frame.HeadersFrame readHeadersFrame(int streamId, int flags, int length) throws IOException {
        int consumed = 0;
        int padding = 0;

        if ((flags & Http2Frame.FLAG_PADDED) != 0) {
            padding = buffer.get() & 0xFF;
            consumed += 1;
        }

        boolean hasPriority = (flags & Http2Frame.FLAG_PRIORITY) != 0;
        int streamDependency = 0;
        int weight = 0;

        if (hasPriority) {
            streamDependency = buffer.getInt() & 0x7FFFFFFF;
            weight = (buffer.get() & 0xFF) + 1; // weight stored as value-1 (RFC 9113 §6.2)
            consumed += 5;
        }

        int headerBlockLength = length - consumed - padding;
        if (headerBlockLength < 0) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.PROTOCOL_ERROR, "HEADERS frame: pad length exceeds payload length");
        }

        byte[] headerBytes = new byte[headerBlockLength];
        buffer.get(headerBytes);

        skipBytes(padding);

        return new Http2Frame.HeadersFrame(
                streamId, flags, ByteBuffer.wrap(headerBytes), hasPriority, streamDependency, weight);
    }

    private Http2Frame.RstStreamFrame readRstStreamFrame(int streamId, int flags, int length) throws IOException {
        if (length != 4) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR, "RST_STREAM frame length must be 4, got " + length);
        }
        int errorCode = buffer.getInt();
        return new Http2Frame.RstStreamFrame(streamId, flags, errorCode);
    }

    private Http2Frame.SettingsFrame readSettingsFrame(int streamId, int flags, int length) throws IOException {
        boolean ack = (flags & Http2Frame.FLAG_ACK) != 0;
        if (!ack && (length % 6 != 0)) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR,
                    "SETTINGS frame payload length must be a multiple of 6, got " + length);
        }
        if (ack && length != 0) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR, "SETTINGS ACK frame must have empty payload, got " + length);
        }

        byte[] payload = new byte[length];
        buffer.get(payload);
        return new Http2Frame.SettingsFrame(streamId, flags, ByteBuffer.wrap(payload));
    }

    private Http2Frame.PingFrame readPingFrame(int streamId, int flags, int length) throws IOException {
        if (length != 8) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR, "PING frame length must be 8, got " + length);
        }
        long opaqueData = buffer.getLong();
        return new Http2Frame.PingFrame(streamId, flags, opaqueData);
    }

    private Http2Frame.GoawayFrame readGoawayFrame(int streamId, int flags, int length) throws IOException {
        if (length < 8) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR, "GOAWAY frame length must be at least 8, got " + length);
        }
        int lastStreamId = buffer.getInt() & 0x7FFFFFFF;
        int errorCode = buffer.getInt();

        int debugLength = length - 8;
        byte[] debugBytes = new byte[debugLength];
        if (debugLength > 0) {
            buffer.get(debugBytes);
        }

        return new Http2Frame.GoawayFrame(streamId, flags, lastStreamId, errorCode, ByteBuffer.wrap(debugBytes));
    }

    private Http2Frame.WindowUpdateFrame readWindowUpdateFrame(int streamId, int flags, int length) throws IOException {
        if (length != 4) {
            throw new Http2ConnectionException(
                    Http2ErrorCode.FRAME_SIZE_ERROR, "WINDOW_UPDATE frame length must be 4, got " + length);
        }
        int increment = buffer.getInt() & 0x7FFFFFFF;
        return new Http2Frame.WindowUpdateFrame(streamId, flags, increment);
    }

    private Http2Frame.ContinuationFrame readContinuationFrame(int streamId, int flags, int length) {
        byte[] headerBytes = new byte[length];
        buffer.get(headerBytes);
        return new Http2Frame.ContinuationFrame(streamId, flags, ByteBuffer.wrap(headerBytes));
    }

    private Http2Frame.UnknownFrame readUnknownFrame(int type, int streamId, int flags, int length) {
        byte[] payload = new byte[length];
        buffer.get(payload);
        return new Http2Frame.UnknownFrame(type, streamId, flags, ByteBuffer.wrap(payload));
    }

    // -------------------------------------------------------------------------
    // Buffer management
    // -------------------------------------------------------------------------

    /**
     * Ensures at least {@code needed} bytes are available in {@link #buffer}.
     *
     * <p>Compacts the buffer, reads from the channel, and flips back to read mode
     * until enough data is present.
     *
     * @param needed number of bytes required
     * @throws EOFException if the channel signals end-of-stream before data is available
     * @throws IOException  on I/O error
     */
    private void ensureReadable(int needed) throws IOException {
        while (buffer.remaining() < needed) {
            buffer.compact();
            int read = channel.read(buffer);
            buffer.flip();
            if (read == -1) {
                throw new EOFException("Channel closed while waiting for " + needed + " bytes");
            }
        }
    }

    /** Advances the buffer position by {@code n} bytes without reading them. */
    private void skipBytes(int n) {
        if (n > 0) {
            buffer.position(buffer.position() + n);
        }
    }
}
