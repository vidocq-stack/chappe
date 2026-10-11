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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.ServerConfig;

import org.junit.jupiter.api.Test;

/**
 * A stream gets at most one RST_STREAM from this endpoint (RFC 9113 §5.1), whichever reset paths race
 * on it. The race on the wire (the handler thread ends an early response with RST_STREAM(NO_ERROR)
 * while the connection thread, holding the same stream, sees its body pass maxRequestSize and resets
 * it with CANCEL) depends on the connection thread pausing between its stream lookup and its reset:
 * no client can order that. This test plays that interleaving directly (CHAPPE-025).
 */
class Http2StreamResetOnceTest {

    private static final int TYPE_RST_STREAM = 0x3;

    private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    private final Http2Connection connection = new Http2Connection(
            Channels.newChannel(new ByteArrayInputStream(new byte[0])),
            (WritableByteChannel) Channels.newChannel(wire),
            _ -> Response.ok("unused"),
            ServerConfig.DEFAULT,
            ByteBuffer.allocate(0));

    private Http2Stream openStream(int streamId) {
        var stream = new Http2Stream(streamId, 65_535, 65_535);
        stream.open();
        return stream;
    }

    /** The {stream id, error code} of every RST_STREAM frame written so far. */
    private List<int[]> rstStreams() {
        var frames = new ArrayList<int[]>();
        var bytes = ByteBuffer.wrap(wire.toByteArray());
        while (bytes.remaining() >= 9) {
            int length = ((bytes.get() & 0xFF) << 16) | ((bytes.get() & 0xFF) << 8) | (bytes.get() & 0xFF);
            int type = bytes.get() & 0xFF;
            bytes.get(); // flags
            int streamId = bytes.getInt() & 0x7FFF_FFFF;
            if (type == TYPE_RST_STREAM) {
                frames.add(new int[] {streamId, bytes.getInt()});
            } else {
                bytes.position(bytes.position() + length);
            }
        }
        return frames;
    }

    @Test
    void aCapResetAfterAnEarlyResponseResetSendsNoSecondRstStream() throws Exception {
        var stream = openStream(1);
        connection.stopUnreadRequestBody(stream); // handler thread: complete response, body still coming
        connection.resetStream(stream, Http2ErrorCode.CANCEL); // connection thread: the cap, same stream
        var frames = rstStreams();
        assertEquals(1, frames.size(), "RST_STREAM frames sent");
        assertArrayEquals(new int[] {1, Http2ErrorCode.NO_ERROR.code()}, frames.get(0));
    }

    @Test
    void everyResetPathIsANoOpOnAStreamAlreadyReset() throws Exception {
        var stream = openStream(3);
        connection.resetStream(stream, Http2ErrorCode.CANCEL);
        connection.resetStream(stream, Http2ErrorCode.STREAM_CLOSED);
        connection.resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
        connection.stopUnreadRequestBody(stream);
        var frames = rstStreams();
        assertEquals(1, frames.size(), "RST_STREAM frames sent");
        assertArrayEquals(new int[] {3, Http2ErrorCode.CANCEL.code()}, frames.get(0));
    }
}
