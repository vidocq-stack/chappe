package io.vidocq.chappe.http.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import io.vidocq.chappe.api.BuildInfo;

/**
 * WebSocket handshake — RFC 6455 §4.2.2.
 * <p>
 * Computes {@code Sec-WebSocket-Accept} and writes the {@code 101 Switching Protocols} response.
 * Validation of incoming headers (Upgrade, Connection, Version, Key) is performed upstream
 * in {@code DefaultRouterBuilder#webSocket}.
 */
public final class WebSocketHandshake {

    /** RFC 6455 §1.3 "Magic GUID" — concatenated to {@code Sec-WebSocket-Key} before SHA-1. */
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final byte[] PROLOGUE = ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Server: " + BuildInfo.serverHeader() + "\r\n"
                    + "Sec-WebSocket-Accept: ")
            .getBytes(StandardCharsets.US_ASCII);

    /** Calcule {@code Sec-WebSocket-Accept = base64(sha1(key + GUID))}. */
    public static String computeAccept(String secWebSocketKey) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((secWebSocketKey + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 missing from JDK", e);
        }
    }

    /** Writes the 101 response with the computed accept value and an optional subprotocol. */
    public static void writeResponse(WritableByteChannel channel, String secWebSocketKey, String subprotocol)
            throws IOException {
        var accept = computeAccept(secWebSocketKey);
        var sb = new StringBuilder(accept.length() + 64);
        sb.append(accept).append("\r\n");
        if (subprotocol != null && !subprotocol.isEmpty()) {
            sb.append("Sec-WebSocket-Protocol: ").append(subprotocol).append("\r\n");
        }
        sb.append("\r\n");
        var tail = sb.toString().getBytes(StandardCharsets.US_ASCII);

        var buf = ByteBuffer.allocate(PROLOGUE.length + tail.length);
        buf.put(PROLOGUE).put(tail).flip();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private WebSocketHandshake() {}
}
