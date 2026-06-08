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
package io.vidocq.chappe.http.ws;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import io.vidocq.chappe.api.CloseCodes;

/**
 * Reads incoming WebSocket frames (server side) — RFC 6455 §5.2.
 * <p>
 * The server requires all client frames to be masked (§5.1):
 * an unmasked frame causes a {@link WebSocketProtocolException} with
 * {@link CloseCodes#PROTOCOL_ERROR}.
 * <p>
 * The source buffer ({@code src}) is in read mode (after {@code flip()}) and is
 * consumed progressively. When it is empty, data is read from the channel.
 */
public final class WebSocketFrameReader {

    private final long maxPayloadSize;

    public WebSocketFrameReader(long maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    /**
     * Reads a complete frame from {@code src}, filling via {@code channel} if necessary.
     *
     * @throws WebSocketProtocolException RFC violation (missing mask, non-zero RSV, payload too long, invalid opcode)
     * @throws EOFException               connection closed by the peer without a prior Close frame
     */
    public WebSocketFrame readFrame(ByteBuffer src, ReadableByteChannel channel) throws IOException {
        ensure(src, channel, 2);
        int b0 = src.get() & 0xFF;
        int b1 = src.get() & 0xFF;

        boolean fin = (b0 & 0x80) != 0;
        int rsv = b0 & 0x70;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        int len7 = b1 & 0x7F;

        if (rsv != 0) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "RSV bits set without negotiated extension");
        }
        if (!masked) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "Client frame must be masked (RFC 6455 §5.1)");
        }
        if (!isKnownOpcode(opcode)) {
            throw new WebSocketProtocolException(
                    CloseCodes.PROTOCOL_ERROR, "Unknown opcode: 0x" + Integer.toHexString(opcode));
        }
        boolean isControl = (opcode & 0x8) != 0;
        if (isControl && !fin) {
            throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Control frame must not be fragmented");
        }
        if (isControl && len7 > 125) {
            throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Control frame payload > 125");
        }

        long payloadLen;
        if (len7 < 126) {
            payloadLen = len7;
        } else if (len7 == 126) {
            ensure(src, channel, 2);
            payloadLen = ((src.get() & 0xFFL) << 8) | (src.get() & 0xFFL);
        } else {
            ensure(src, channel, 8);
            payloadLen = src.getLong();
            if (payloadLen < 0) {
                throw new WebSocketProtocolException(CloseCodes.PROTOCOL_ERROR, "Negative 64-bit payload length");
            }
        }
        if (payloadLen > maxPayloadSize) {
            throw new WebSocketProtocolException(
                    CloseCodes.MESSAGE_TOO_BIG, "Payload " + payloadLen + " > max " + maxPayloadSize);
        }

        ensure(src, channel, 4);
        int m0 = src.get() & 0xFF;
        int m1 = src.get() & 0xFF;
        int m2 = src.get() & 0xFF;
        int m3 = src.get() & 0xFF;

        // Read the full payload
        var payload = new byte[(int) payloadLen];
        int read = 0;
        while (read < payload.length) {
            if (!src.hasRemaining()) {
                fill(src, channel);
            }
            int take = Math.min(src.remaining(), payload.length - read);
            src.get(payload, read, take);
            read += take;
        }

        // Unmasking (XOR with repeated key)
        for (int i = 0; i < payload.length; i++) {
            int mask =
                    switch (i & 3) {
                        case 0 -> m0;
                        case 1 -> m1;
                        case 2 -> m2;
                        default -> m3;
                    };
            payload[i] = (byte) (payload[i] ^ mask);
        }

        return new WebSocketFrame(fin, opcode, ByteBuffer.wrap(payload));
    }

    private static boolean isKnownOpcode(int opcode) {
        return switch (opcode) {
            case WebSocketFrame.OP_CONTINUATION,
                    WebSocketFrame.OP_TEXT,
                    WebSocketFrame.OP_BINARY,
                    WebSocketFrame.OP_CLOSE,
                    WebSocketFrame.OP_PING,
                    WebSocketFrame.OP_PONG -> true;
            default -> false;
        };
    }

    /** Ensures that at least {@code n} bytes are available in {@code src}, reading from the channel if needed. */
    private static void ensure(ByteBuffer src, ReadableByteChannel channel, int n) throws IOException {
        while (src.remaining() < n) {
            fill(src, channel);
        }
    }

    private static void fill(ByteBuffer src, ReadableByteChannel channel) throws IOException {
        // Compact remaining bytes at buffer start, then read.
        src.compact();
        int n = channel.read(src);
        src.flip();
        if (n < 0) {
            throw new EOFException("Connection closed mid-frame");
        }
    }
}
