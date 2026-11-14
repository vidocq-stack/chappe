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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.vidocq.chappe.api.CloseCodes;

/**
 * A WebSocket client on a plain socket, for the tests that need what a real client hides: a peer
 * that stops reading, a handshake with chosen headers, a refused handshake's status, raw frames.
 *
 * <p>{@link #open} sends the handshake and reads the response head, whatever its status: a refused
 * handshake (400, 403, 426) is read like an accepted one. The default request headers are {@code
 * Host}, {@code Upgrade: websocket}, {@code Connection: Upgrade}, a random {@code Sec-WebSocket-Key}
 * and {@code Sec-WebSocket-Version: 13}; an entry of {@code extraHeaders} replaces the default
 * header of the same name (ignoring case), a {@code null} value leaves it out, and any other entry
 * ({@code Origin} for one) is added after them, in the map's order.
 *
 * <p>Nothing is read unless a method asks: a client that never calls {@link #readFrame} keeps its
 * receive buffer full, and the server's writes block.
 */
final class RawWebSocket implements Closeable {

    /** How long a read waits before it fails, unless {@link #awaitEof} sets its own bound. */
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** One server frame: its opcode and its unmasked payload. */
    record Frame(int opcode, byte[] payload) {

        /** The status code of a Close frame, {@link CloseCodes#NO_STATUS_RCVD} when its payload has none. */
        int closeCode() {
            if (payload.length < 2) return CloseCodes.NO_STATUS_RCVD;
            return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Frame(int op, byte[] bytes) && op == opcode && Arrays.equals(bytes, payload);
        }

        @Override
        public int hashCode() {
            return 31 * opcode + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Frame[opcode=0x" + Integer.toHexString(opcode) + ", " + payload.length + " bytes]";
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final int status;
    private final Map<String, String> headers;

    private RawWebSocket(Socket socket, DataInputStream in, int status, Map<String, String> headers) {
        this.socket = socket;
        this.in = in;
        this.status = status;
        this.headers = headers;
    }

    /**
     * Connects to {@code 127.0.0.1:port}, sends the handshake for {@code path} and reads the response
     * head.
     *
     * @param extraHeaders headers that replace the defaults of the same name, or are added to them
     * @param receiveBufferSize the socket's receive buffer, set before it connects; {@code 0} or less
     *        keeps the system default
     */
    static RawWebSocket open(int port, String path, Map<String, String> extraHeaders, int receiveBufferSize)
            throws IOException {
        var socket = new Socket();
        try {
            if (receiveBufferSize > 0) socket.setReceiveBufferSize(receiveBufferSize);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.getOutputStream().write(handshake(port, path, extraHeaders).getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            var in = new DataInputStream(socket.getInputStream());
            String[] head = readHead(in).split("\r\n");
            int status = Integer.parseInt(head[0].split(" ", 3)[1]);
            var headers = new LinkedHashMap<String, String>();
            for (int i = 1; i < head.length; i++) {
                int colon = head[i].indexOf(':');
                if (colon > 0) {
                    headers.putIfAbsent(
                            head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            head[i].substring(colon + 1).trim());
                }
            }
            return new RawWebSocket(socket, in, status, headers);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static String handshake(int port, String path, Map<String, String> extraHeaders) {
        var key = new byte[16];
        RANDOM.nextBytes(key);
        var headers = new LinkedHashMap<String, String>();
        headers.put("Host", "127.0.0.1:" + port);
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", Base64.getEncoder().encodeToString(key));
        headers.put("Sec-WebSocket-Version", "13");
        extraHeaders.forEach((name, value) -> {
            String existing = headers.keySet().stream()
                    .filter(name::equalsIgnoreCase)
                    .findFirst()
                    .orElse(name);
            headers.put(existing, value);
        });
        var request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n");
        headers.forEach((name, value) -> {
            if (value != null) request.append(name).append(": ").append(value).append("\r\n");
        });
        return request.append("\r\n").toString();
    }

    /** Reads the response head up to its blank line, without reading past it. */
    private static String readHead(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b == -1) throw new IOException("Connection closed in the response head: " + head);
            head.write(b);
            boolean cr = (matched & 1) == 0;
            if (b == (cr ? '\r' : '\n')) matched++;
            else matched = b == '\r' ? 1 : 0;
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    /** The handshake response's status code. */
    int status() {
        return status;
    }

    /** The first value of a handshake response header (name ignoring case), {@code null} when absent. */
    String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Sends one frame, FIN set, masked as a client must (RFC 6455 §5.3). */
    void sendMasked(int opcode, byte[] payload) throws IOException {
        var frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((long) payload.length >>> shift));
        }
        var mask = new byte[4];
        RANDOM.nextBytes(mask);
        frame.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    /** Reads the next server frame; fails on EOF or when none arrives within the read timeout. */
    Frame readFrame() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int len7 = b1 & 0x7F;
        long length;
        if (len7 < 126) length = len7;
        else if (len7 == 126) length = in.readUnsignedShort();
        else length = in.readLong();
        if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("Frame too large for a test: " + length);
        var payload = new byte[(int) length];
        in.readFully(payload);
        return new Frame(b0 & 0x0F, payload);
    }

    /**
     * Discards what the server sends until it closes the connection.
     *
     * @return {@code true} when the connection ended (EOF or reset) within {@code timeout}
     */
    boolean awaitEof(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        var discard = new byte[8192];
        try {
            while (true) {
                long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                if (in.read(discard) == -1) return true;
            }
        } catch (SocketTimeoutException _) {
            return false;
        } catch (SocketException _) {
            return true; // reset: the connection is gone all the same
        } finally {
            if (!socket.isClosed()) socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
