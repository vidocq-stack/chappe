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

/**
 * Encodes a gRPC message (5-byte prefix + payload) into a single {@code byte[]} ready to
 * be passed to {@code Http2FrameWriter.writeData}.
 */
public final class GrpcFrameWriter {

    private GrpcFrameWriter() {}

    /** Encodes an uncompressed message (prefix with {@code compressed=0}). */
    public static byte[] encode(byte[] payload) {
        return encode(payload, false);
    }

    /**
     * Encodes a message with an explicit {@code compressed} flag. The provided
     * {@code payload} must already be compressed according to the negotiated
     * {@code grpc-encoding} if {@code compressed=true} — this framing only adds the prefix.
     */
    public static byte[] encode(byte[] payload, boolean compressed) {
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = (byte) (compressed ? 1 : 0);
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }
}
