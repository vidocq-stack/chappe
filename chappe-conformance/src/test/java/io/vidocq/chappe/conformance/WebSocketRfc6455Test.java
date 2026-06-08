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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.http.ws.WebSocketHandshake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RFC 6455 conformance — handshake and framing at the raw socket level.
 */
class WebSocketRfc6455Test {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .webSocket("/ws", new WebSocketHandler() {
                    @Override
                    public void onText(io.vidocq.chappe.api.WebSocket ws, String message) throws Exception {
                        ws.sendText(message);
                    }
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void handshakeSecWebSocketAcceptIsRfc6455Compliant() {
        // Canonical RFC 6455 §1.3 case
        var key = "dGhlIHNhbXBsZSBub25jZQ==";
        var expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        assertEquals(expected, WebSocketHandshake.computeAccept(key));
    }

    @Test
    void handshakeReturns101WithCorrectAcceptHeader() throws Exception {
        var key = randomKey();
        var request = "GET /ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            RawHttp.write(socket.getOutputStream(), request);
            var headers = readHandshakeResponse(socket.getInputStream());

            assertTrue(headers.startsWith("HTTP/1.1 101 "), () -> "Expected 101, got: " + firstLine(headers));
            assertEquals("websocket", RawHttp.extractHeader(headers, "Upgrade"));
            assertEquals("Upgrade", RawHttp.extractHeader(headers, "Connection"));
            assertEquals(WebSocketHandshake.computeAccept(key), RawHttp.extractHeader(headers, "Sec-WebSocket-Accept"));

            // Cleanly close the connection.
            sendClose(socket.getOutputStream(), 1000);
        }
    }

    @Test
    void handshakeMissingVersionReturns426() {
        var request = "GET /ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
        var resp = RawHttp.sendAndReceive(port, request);
        assertEquals(426, RawHttp.extractStatusCode(resp));
        assertEquals("13", RawHttp.extractHeader(resp, "Sec-WebSocket-Version"));
    }

    @Test
    void handshakeMissingKeyReturns400() {
        var request = "GET /ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        var resp = RawHttp.sendAndReceive(port, request);
        assertEquals(400, RawHttp.extractStatusCode(resp));
    }

    @Test
    void serverDoesNotAcceptUnmaskedClientFrame() throws Exception {
        try (var socket = openHandshake()) {
            // Unmasked text frame — RFC §5.1 violation
            var out = socket.getOutputStream();
            out.write(new byte[] {
                (byte) 0x81, // FIN + TEXT
                (byte) 0x05, // MASK=0, len=5
                'h',
                'e',
                'l',
                'l',
                'o'
            });
            out.flush();

            // Server must send Close with PROTOCOL_ERROR (1002) and close.
            var frame = readFrame(socket.getInputStream());
            assertEquals(0x8, frame.opcode, "Expected Close frame");
            assertTrue(frame.payload.length >= 2);
            int code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
            assertEquals(1002, code, "Expected PROTOCOL_ERROR code");
        }
    }

    @Test
    void serverEchoesMaskedTextFrame() throws Exception {
        try (var socket = openHandshake()) {
            sendMaskedText(socket.getOutputStream(), "ping");
            var frame = readFrame(socket.getInputStream());
            assertEquals(0x1, frame.opcode);
            assertEquals("ping", new String(frame.payload, StandardCharsets.UTF_8));
            sendClose(socket.getOutputStream(), 1000);
        }
    }

    @Test
    void invalidUtf8InTextFrameTriggersClose1007() throws Exception {
        try (var socket = openHandshake()) {
            // 0xC0 0xAF is an invalid UTF-8 sequence (overlong encoding of '/')
            sendMaskedBytes(socket.getOutputStream(), 0x1, new byte[] {(byte) 0xC0, (byte) 0xAF});
            var frame = readFrame(socket.getInputStream());
            assertEquals(0x8, frame.opcode);
            int code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
            assertEquals(1007, code, "Expected INVALID_PAYLOAD_DATA");
        }
    }

    @Test
    void clientCloseIsEchoedWithSameCode() throws Exception {
        try (var socket = openHandshake()) {
            // Send Close 1000
            byte[] closePayload = new byte[] {0x03, (byte) 0xE8}; // 1000
            sendMaskedBytes(socket.getOutputStream(), 0x8, closePayload);
            var frame = readFrame(socket.getInputStream());
            assertEquals(0x8, frame.opcode);
            int code = ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
            assertEquals(1000, code);
        }
    }

    // -- Helpers --

    private Socket openHandshake() throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        var key = randomKey();
        var request = "GET /ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var headers = readHandshakeResponse(socket.getInputStream());
        if (!headers.startsWith("HTTP/1.1 101 ")) {
            socket.close();
            throw new IOException("Handshake failed: " + firstLine(headers));
        }
        return socket;
    }

    private static String randomKey() {
        var bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Reads until \r\n\r\n. */
    private static String readHandshakeResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int b;
        int crlfCount = 0;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (b == '\r' || b == '\n') {
                crlfCount++;
                if (crlfCount == 4) return sb.toString();
            } else {
                crlfCount = 0;
            }
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\r');
        return nl > 0 ? s.substring(0, nl) : s;
    }

    private static void sendMaskedText(OutputStream out, String text) throws IOException {
        sendMaskedBytes(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendMaskedBytes(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode); // FIN + opcode
        if (payload.length < 126) {
            out.write(0x80 | payload.length); // MASK=1
        } else if (payload.length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            throw new IllegalArgumentException("Test payload too large");
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        out.write(mask);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(masked);
        out.flush();
    }

    private static void sendClose(OutputStream out, int code) throws IOException {
        byte[] payload = new byte[] {(byte) ((code >>> 8) & 0xFF), (byte) (code & 0xFF)};
        sendMaskedBytes(out, 0x8, payload);
    }

    private record DecodedFrame(int opcode, byte[] payload) {}

    private static DecodedFrame readFrame(InputStream raw) throws IOException {
        var in = new DataInputStream(raw);
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int opcode = b0 & 0x0F;
        int len7 = b1 & 0x7F;
        long len;
        if (len7 < 126) len = len7;
        else if (len7 == 126) len = in.readUnsignedShort();
        else len = in.readLong();
        // No MASK on the server side.
        var payload = new byte[(int) len];
        in.readFully(payload);
        return new DecodedFrame(opcode, payload);
    }
}
