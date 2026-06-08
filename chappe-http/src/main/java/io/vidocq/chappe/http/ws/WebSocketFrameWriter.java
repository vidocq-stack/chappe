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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Writes outgoing WebSocket frames (server side) — RFC 6455 §5.2.
 * <p>
 * Server frames are never masked (§5.1). The methods are
 * <em>not thread-safe</em>: serialization is ensured by {@code WebSocketConnection}
 * via a {@code ReentrantLock} to avoid frame interleaving (§5.4).
 */
public final class WebSocketFrameWriter {

    /** Complete frame: FIN=1, given opcode, unmasked payload. */
    public static void writeFrame(WritableByteChannel channel, int opcode, ByteBuffer payload) throws IOException {
        writeHeader(channel, true, opcode, payload.remaining());
        flush(channel, payload);
    }

    /** Complete frame with explicit FIN (for fragmentation). */
    public static void writeFrame(WritableByteChannel channel, boolean fin, int opcode, ByteBuffer payload)
            throws IOException {
        writeHeader(channel, fin, opcode, payload.remaining());
        flush(channel, payload);
    }

    private static void writeHeader(WritableByteChannel channel, boolean fin, int opcode, long payloadLen)
            throws IOException {
        // Max header = 2 (base) + 8 (extended payload) = 10 bytes, no masking on server side.
        var hdr = ByteBuffer.allocate(10);
        int b0 = (fin ? 0x80 : 0) | (opcode & 0x0F);
        hdr.put((byte) b0);

        if (payloadLen < 126) {
            hdr.put((byte) (payloadLen & 0x7F));
        } else if (payloadLen <= 0xFFFF) {
            hdr.put((byte) 126);
            hdr.putShort((short) payloadLen);
        } else {
            hdr.put((byte) 127);
            hdr.putLong(payloadLen);
        }
        hdr.flip();
        flush(channel, hdr);
    }

    private static void flush(WritableByteChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private WebSocketFrameWriter() {}
}
