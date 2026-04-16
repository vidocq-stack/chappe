package fr.vidocq.chappe.conformance;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Router;
import fr.vidocq.chappe.api.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP/2 SETTINGS and PING conformance tests (RFC 9113, Sections 6.5 and 6.7).
 */
class Http2SettingsTest {

    private static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/", _ -> Response.ok("Hello HTTP/2!"))
                .build();

        server = Server.builder()
                .port(0)
                .handler(router)
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * RFC 9113 Section 6.5: After the connection preface, the server sends its
     * SETTINGS and acknowledges the client's SETTINGS with a SETTINGS ACK.
     */
    @Test
    void settingsExchange() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Send client connection preface + empty SETTINGS
            out.write(CLIENT_PREFACE);
            out.write(emptySettingsFrame());
            out.flush();

            boolean receivedServerSettings = false;
            boolean receivedSettingsAck = false;

            for (int i = 0; i < 10 && !(receivedServerSettings && receivedSettingsAck); i++) {
                var frame = readFrame(in);
                if (frame == null) break;

                if (frame.type() == 0x04) { // SETTINGS
                    if ((frame.flags() & 0x01) != 0) {
                        receivedSettingsAck = true;
                        assertEquals(0, frame.payloadLength(),
                                "SETTINGS ACK payload must be empty");
                        assertEquals(0, frame.streamId(),
                                "SETTINGS ACK must be on stream 0");
                    } else {
                        receivedServerSettings = true;
                        assertEquals(0, frame.streamId(),
                                "SETTINGS must be on stream 0");
                        // Payload length must be a multiple of 6 (each setting is 6 bytes)
                        assertEquals(0, frame.payloadLength() % 6,
                                "SETTINGS payload must be a multiple of 6 bytes, got " + frame.payloadLength());
                    }
                }
            }

            assertTrue(receivedServerSettings, "Server must send its SETTINGS frame");
            assertTrue(receivedSettingsAck, "Server must ACK client SETTINGS");
        }
    }

    /**
     * RFC 9113 Section 6.7: PING frames must be echoed back with the ACK flag
     * set and the same 8-byte opaque data.
     */
    @Test
    void pingEcho() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // Complete handshake first
            out.write(CLIENT_PREFACE);
            out.write(emptySettingsFrame());
            out.flush();

            // Wait for server SETTINGS and ACK, then ACK the server's SETTINGS
            boolean serverSettingsAcked = false;
            for (int i = 0; i < 10; i++) {
                var frame = readFrame(in);
                if (frame == null) break;
                if (frame.type() == 0x04 && (frame.flags() & 0x01) == 0) {
                    // Send SETTINGS ACK for server's SETTINGS
                    out.write(settingsAckFrame());
                    out.flush();
                    serverSettingsAcked = true;
                }
                if (frame.type() == 0x04 && (frame.flags() & 0x01) != 0) {
                    // Our SETTINGS was ACKed
                    if (serverSettingsAcked) break;
                }
            }

            // Send PING with specific opaque data
            byte[] pingData = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
            out.write(pingFrame(pingData));
            out.flush();

            // Read frames until we get a PING ACK
            boolean receivedPingAck = false;
            for (int i = 0; i < 10 && !receivedPingAck; i++) {
                var frame = readFrame(in);
                if (frame == null) break;

                if (frame.type() == 0x06 && (frame.flags() & 0x01) != 0) {
                    // PING ACK
                    receivedPingAck = true;
                    assertEquals(8, frame.payloadLength(),
                            "PING ACK payload must be 8 bytes");
                    assertArrayEquals(pingData, frame.payload(),
                            "PING ACK must echo the same opaque data");
                    assertEquals(0, frame.streamId(),
                            "PING must be on stream 0");
                }
            }

            assertTrue(receivedPingAck, "Server must respond with PING ACK");
        }
    }

    // --- Frame construction ---

    private byte[] emptySettingsFrame() {
        return new byte[]{
                0x00, 0x00, 0x00,       // length = 0
                0x04,                   // type = SETTINGS
                0x00,                   // flags = 0
                0x00, 0x00, 0x00, 0x00  // stream ID = 0
        };
    }

    private byte[] settingsAckFrame() {
        return new byte[]{
                0x00, 0x00, 0x00,       // length = 0
                0x04,                   // type = SETTINGS
                0x01,                   // flags = ACK
                0x00, 0x00, 0x00, 0x00  // stream ID = 0
        };
    }

    /**
     * PING frame: type=0x06, 8 bytes opaque data.
     */
    private byte[] pingFrame(byte[] data) {
        if (data.length != 8) throw new IllegalArgumentException("PING data must be 8 bytes");
        var buf = ByteBuffer.allocate(9 + 8);
        buf.put((byte) 0x00).put((byte) 0x00).put((byte) 0x08); // length = 8
        buf.put((byte) 0x06); // type = PING
        buf.put((byte) 0x00); // flags = 0 (not ACK)
        buf.putInt(0);        // stream ID = 0
        buf.put(data);
        return buf.array();
    }

    // --- Frame reading ---

    private record Frame(int payloadLength, int type, int flags, int streamId, byte[] payload) {}

    private Frame readFrame(InputStream in) throws IOException {
        var header = in.readNBytes(9);
        if (header.length < 9) return null;

        var buf = ByteBuffer.wrap(header);
        int payloadLength = ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        int type = buf.get() & 0xFF;
        int flags = buf.get() & 0xFF;
        int streamId = buf.getInt() & 0x7FFFFFFF;

        byte[] payload = new byte[0];
        if (payloadLength > 0) {
            payload = in.readNBytes(payloadLength);
            if (payload.length < payloadLength) return null;
        }

        return new Frame(payloadLength, type, flags, streamId, payload);
    }
}
