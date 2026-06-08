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
package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/2 Connection Preface conformance tests (RFC 9113, Section 3.4).
 * <p>
 * The client connection preface starts with "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
 * followed by a SETTINGS frame. The server must respond with its own SETTINGS
 * frame and a SETTINGS ACK for the client's SETTINGS.
 */
class Http2PrefaceTest {

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** SETTINGS frame: type=0x04, flags=0x00, streamId=0, length=0 */
    private static final byte[] EMPTY_SETTINGS_FRAME = {
        0x00,
        0x00,
        0x00, // length = 0
        0x04, // type = SETTINGS
        0x00, // flags = 0
        0x00,
        0x00,
        0x00,
        0x00 // stream ID = 0
    };

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

    /**
     * RFC 9113 Section 3.4: A valid client preface + empty SETTINGS frame
     * must be answered with the server's SETTINGS frame and a SETTINGS ACK.
     */
    @Test
    void validPreface() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Send client connection preface
            out.write(CLIENT_PREFACE);
            // Send empty SETTINGS frame
            out.write(EMPTY_SETTINGS_FRAME);
            out.flush();

            // Read frames from server — expect SETTINGS and SETTINGS ACK
            boolean receivedSettings = false;
            boolean receivedSettingsAck = false;

            // Read up to 10 frames to find what we need
            for (int i = 0; i < 10 && !(receivedSettings && receivedSettingsAck); i++) {
                var frame = readFrame(in);
                if (frame == null) break;

                if (frame.type() == 0x04) { // SETTINGS
                    if ((frame.flags() & 0x01) != 0) {
                        // SETTINGS ACK
                        receivedSettingsAck = true;
                        assertEquals(0, frame.payloadLength(), "SETTINGS ACK must have empty payload");
                    } else {
                        // Server SETTINGS
                        receivedSettings = true;
                        assertEquals(0, frame.streamId(), "SETTINGS must be on stream 0");
                    }
                }
            }

            assertTrue(receivedSettings, "Server should send its SETTINGS frame");
            assertTrue(receivedSettingsAck, "Server should send SETTINGS ACK for client SETTINGS");
        }
    }

    /**
     * If a client sends an HTTP/1.1 request instead of the HTTP/2 preface,
     * the server should handle it as HTTP/1.1 (not crash).
     */
    @Test
    void invalidPreface() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Send an HTTP/1.1 request (not an HTTP/2 preface)
            var http11Request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            out.write(http11Request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Should get an HTTP/1.1 response (not a crash)
            var response = readAll(in);
            assertNotNull(response);
            assertTrue(response.contains("HTTP/1.1"), "Server should respond with HTTP/1.1: " + response);
            assertTrue(
                    response.contains("200") || response.contains("404"),
                    "Server should respond with a valid status: " + response);
        }
    }

    // --- Frame reading utilities ---

    private record Frame(int payloadLength, int type, int flags, int streamId, byte[] payload) {}

    private Frame readFrame(InputStream in) throws IOException {
        // Read 9-byte frame header
        var header = in.readNBytes(9);
        if (header.length < 9) return null;

        var buf = ByteBuffer.wrap(header);
        int payloadLength = ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        int type = buf.get() & 0xFF;
        int flags = buf.get() & 0xFF;
        int streamId = buf.getInt() & 0x7FFFFFFF; // clear reserved bit

        byte[] payload = new byte[0];
        if (payloadLength > 0) {
            payload = in.readNBytes(payloadLength);
            if (payload.length < payloadLength) return null;
        }

        return new Frame(payloadLength, type, flags, streamId, payload);
    }

    private String readAll(InputStream in) throws IOException {
        var sb = new StringBuilder();
        var buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }
}
