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
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for CHAPPE-004 (bug B): the HTTP/2 cleartext connection preface
 * must be recognised even when TCP delivers it across several segments.
 * <p>
 * The 24-byte preface ({@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}) used to be detected
 * only if the very first {@code channel.read()} returned at least 6 bytes. Under load
 * TCP can split the preface, so a short first read mis-routed the h2c connection to the
 * HTTP/1.1 parser, which then choked on the binary SETTINGS frame and closed
 * mid-handshake (the client observed an {@code EOFException} / {@code 400}).
 * <p>
 * Here we deliberately send the preface in two fragments with a pause in between so the
 * server's first read sees only 3 bytes, then assert the server still negotiates HTTP/2
 * by replying with its own SETTINGS frame.
 */
class Http2PrefaceFragmentationTest {

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TYPE_SETTINGS = 0x4;

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router =
                Router.builder().get("/", _ -> Response.ok("Hello HTTP/2!")).build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void fragmentedPrefaceIsStillDetectedAsHttp2() {
        assertTimeout(Duration.ofSeconds(10), () -> {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(5000);
                var in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();

                // Fragment 1: only the first 3 bytes ("PRI"). Flush and pause so the
                // server's first read() returns fewer than the 6 bytes the old preface
                // check required.
                out.write(H2_PREFACE, 0, 3);
                out.flush();
                Thread.sleep(150);

                // Fragment 2: the remainder of the preface, then an empty client SETTINGS.
                out.write(H2_PREFACE, 3, H2_PREFACE.length - 3);
                writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
                out.flush();

                // The server must have taken the HTTP/2 path: its first frame is SETTINGS
                // on stream 0. (A mis-routed HTTP/1.1 parser would send an ASCII status
                // line — its first byte 'H' (0x48) would not parse as a SETTINGS frame.)
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int b2 = in.readUnsignedByte();
                int length = (b0 << 16) | (b1 << 8) | b2;
                int type = in.readUnsignedByte();
                in.readUnsignedByte(); // flags
                int streamId = in.readInt() & 0x7FFFFFFF;
                in.readNBytes(length);

                assertEquals(TYPE_SETTINGS, type, "server did not negotiate HTTP/2 after a fragmented preface");
                assertEquals(0, streamId, "SETTINGS must be on the connection stream (0)");
            }
        });
    }

    private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.write((streamId >>> 24) & 0x7F);
        out.write((streamId >>> 16) & 0xFF);
        out.write((streamId >>> 8) & 0xFF);
        out.write(streamId & 0xFF);
        out.write(payload);
    }
}
