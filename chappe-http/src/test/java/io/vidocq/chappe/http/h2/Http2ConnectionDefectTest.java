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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.http.ConnectionInfo;

import org.junit.jupiter.api.Test;

/**
 * A defect on the HTTP/2 frame loop (an unexpected {@link RuntimeException}) ends the connection with
 * {@code GOAWAY(INTERNAL_ERROR)} instead of a bare close (CHAPPE-033); {@code run()} also logs it at
 * WARNING with its cause. The log is not asserted here: this test runs inside the
 * {@code io.vidocq.chappe.http} module, which does not read {@code java.logging}.
 */
class Http2ConnectionDefectTest {

    private static final int TYPE_GOAWAY = 0x7;

    @Test
    void aRuntimeExceptionOnTheFrameLoopEndsTheConnectionWithInternalError() {
        var defect = new IllegalStateException("simulated defect");
        ReadableByteChannel failing = new ReadableByteChannel() {
            @Override
            public int read(ByteBuffer dst) {
                throw defect;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {}
        };
        var wire = new ByteArrayOutputStream();
        var connection = new Http2Connection(
                failing,
                (WritableByteChannel) Channels.newChannel(wire),
                new ConnectionInfo(null, null, null),
                _ -> Response.ok("unused"),
                ServerConfig.DEFAULT,
                ByteBuffer.allocate(1024).flip()); // empty, in read mode

        connection.run();

        assertEquals(Http2ErrorCode.INTERNAL_ERROR.code(), goawayErrorCode(wire.toByteArray()));
    }

    /** The error code of the GOAWAY frame written, or -1. */
    private static int goawayErrorCode(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= 9) {
            int length = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
            int type = buffer.get() & 0xFF;
            buffer.get(); // flags
            buffer.getInt(); // stream id
            if (type == TYPE_GOAWAY) {
                buffer.getInt(); // last stream id
                return buffer.getInt();
            }
            buffer.position(buffer.position() + length);
        }
        return -1;
    }
}
