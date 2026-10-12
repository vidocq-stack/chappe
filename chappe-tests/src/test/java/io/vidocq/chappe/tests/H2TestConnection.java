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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.vidocq.chappe.http.h2.HpackDecoder;

/**
 * A raw HTTP/2 client (h2c, prior knowledge) for tests that send what a well-behaved client never
 * sends: frames on reset streams, overrun windows, padding. The HEADERS frames the server sends are
 * HPACK-decoded in order, so {@link Frame#headers()} holds their fields.
 */
final class H2TestConnection implements AutoCloseable {

    static final int TYPE_DATA = 0x0;
    static final int TYPE_HEADERS = 0x1;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_WINDOW_UPDATE = 0x8;
    static final int TYPE_CONTINUATION = 0x9;
    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_ACK = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int NO_ERROR = 0x0;
    static final int PROTOCOL_ERROR = 0x1;
    static final int FLOW_CONTROL_ERROR = 0x3;
    static final int STREAM_CLOSED = 0x5;
    static final int REFUSED_STREAM = 0x7;
    static final int CANCEL = 0x8;
    static final int COMPRESSION_ERROR = 0x9;
    static final int ENHANCE_YOUR_CALM = 0xb;

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** A frame from the server; {@code headers} is empty unless it is a HEADERS frame. */
    record Frame(int type, int flags, int streamId, byte[] payload, Map<String, String> headers) {
        boolean endStream() {
            return (flags & FLAG_END_STREAM) != 0;
        }

        /** The error code of a RST_STREAM frame. */
        int errorCode() {
            return ByteBuffer.wrap(payload).getInt();
        }

        /** The error code of a GOAWAY frame. */
        int goawayErrorCode() {
            return ByteBuffer.wrap(payload, 4, 4).getInt();
        }

        int windowIncrement() {
            return ByteBuffer.wrap(payload).getInt() & 0x7FFFFFFF;
        }
    }

    /** A whole response: its {@code :status} and its body. */
    record Reply(String status, String body) {}

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final HpackDecoder decoder = new HpackDecoder(4096, 64 * 1024);

    private H2TestConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Connects, sends the preface and an empty SETTINGS, and completes the SETTINGS exchange. */
    static H2TestConnection open(int port) throws IOException {
        return open(port, new byte[0]);
    }

    /** Connects, sends the preface and a SETTINGS frame with {@code settings} as its payload, and completes the exchange. */
    static H2TestConnection open(int port, byte[] settings) throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        var h2 = over(socket);
        h2.preface();
        h2.frame(TYPE_SETTINGS, 0, 0, settings);
        h2.flush();
        for (int i = 0; i < 2; i++) h2.read(); // the server's SETTINGS and its ACK of ours
        h2.frame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        h2.flush();
        return h2;
    }

    /**
     * Wraps a socket the caller has already connected (plain or TLS), with nothing sent yet: the
     * caller writes the preface and drives the SETTINGS exchange itself.
     */
    static H2TestConnection over(Socket socket) throws IOException {
        return new H2TestConnection(socket);
    }

    /** Writes the client connection preface (not flushed). */
    void preface() throws IOException {
        out.write(PREFACE);
    }

    void headers(int streamId, boolean endStream, byte[] block) throws IOException {
        frame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block);
    }

    void data(int streamId, boolean endStream, byte[] bytes) throws IOException {
        frame(TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, bytes);
    }

    /** A PADDED DATA frame: the pad-length byte, the data, then {@code padLength} zero bytes. */
    void paddedData(int streamId, boolean endStream, byte[] bytes, int padLength) throws IOException {
        byte[] payload = new byte[1 + bytes.length + padLength];
        payload[0] = (byte) padLength;
        System.arraycopy(bytes, 0, payload, 1, bytes.length);
        frame(TYPE_DATA, FLAG_PADDED | (endStream ? FLAG_END_STREAM : 0), streamId, payload);
    }

    void frame(int type, int flags, int streamId, byte[] payload) throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    void flush() throws IOException {
        out.flush();
    }

    /** The next frame; a HEADERS block is decoded (the server sends small blocks in one frame). */
    Frame read() throws IOException {
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int streamId = in.readInt() & 0x7FFFFFFF;
        byte[] payload = in.readNBytes(len);
        Map<String, String> headers = new HashMap<>();
        if (type == TYPE_HEADERS) decoder.decode(ByteBuffer.wrap(payload), headers::put);
        return new Frame(type, flags, streamId, payload, headers);
    }

    /** Reads up to the first frame of {@code type} on {@code streamId}; a GOAWAY on the way fails. */
    Frame await(int type, int streamId) throws IOException {
        while (true) {
            Frame f = read();
            if (f.type() == type && f.streamId() == streamId) return f;
            if (f.type() == TYPE_GOAWAY) {
                throw new AssertionError("GOAWAY (error " + f.goawayErrorCode() + ") while waiting for frame type "
                        + type + " on stream " + streamId);
            }
        }
    }

    /** The next frame within {@code millis}, or {@code null} when none came. */
    Frame poll(int millis) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(millis);
        try {
            return read();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    /** Reads the response of {@code streamId} up to its END_STREAM; a GOAWAY or a reset fails. */
    Reply reply(int streamId) throws IOException {
        String status = null;
        var body = new ByteArrayOutputStream();
        while (true) {
            Frame f = read();
            if (f.type() == TYPE_GOAWAY) {
                throw new AssertionError(
                        "GOAWAY (error " + f.goawayErrorCode() + ") before the end of stream " + streamId);
            }
            if (f.streamId() != streamId) continue;
            if (f.type() == TYPE_RST_STREAM) {
                throw new AssertionError("stream " + streamId + " reset (error " + f.errorCode() + ") before its end");
            }
            if (f.type() == TYPE_HEADERS && status == null) status = f.headers().get(":status");
            if (f.type() == TYPE_DATA) body.write(f.payload(), 0, f.payload().length);
            if (f.endStream()) return new Reply(status, body.toString(StandardCharsets.US_ASCII));
        }
    }

    /** Request pseudo-headers, then {@code extra} name/value pairs, as literals without indexing. */
    static byte[] requestHeaders(String method, String path, String... extra) {
        var block = new ByteArrayOutputStream();
        literal(block, 0x00, ":method", method);
        literal(block, 0x00, ":scheme", "http");
        literal(block, 0x00, ":authority", "127.0.0.1");
        literal(block, 0x00, ":path", path);
        for (int i = 0; i < extra.length; i += 2) literal(block, 0x00, extra[i], extra[i + 1]);
        return block.toByteArray();
    }

    /** A literal field with incremental indexing and a new name (RFC 7541 §6.2.1): it enters the dynamic table. */
    static byte[] literalWithIndexing(String name, String value) {
        var block = new ByteArrayOutputStream();
        literal(block, 0x40, name, value);
        return block.toByteArray();
    }

    /** A literal field without indexing and a new name (RFC 7541 §6.2.2): the dynamic table is untouched. */
    static byte[] literalWithoutIndexing(String name, String value) {
        var block = new ByteArrayOutputStream();
        literal(block, 0x00, name, value);
        return block.toByteArray();
    }

    /** An indexed field (RFC 7541 §6.1); 62 is the newest dynamic table entry. */
    static byte[] indexed(int index) {
        return new byte[] {(byte) (0x80 | index)};
    }

    /** A SETTINGS payload entry: a 16-bit identifier and a 32-bit value (RFC 9113 §6.5.1). */
    static byte[] setting(int id, int value) {
        return ByteBuffer.allocate(6).putShort((short) id).putInt(value).array();
    }

    /** A dynamic table size update (RFC 7541 §6.3): pattern 001, a 5-bit prefix integer. */
    static byte[] sizeUpdate(int size) {
        var block = new ByteArrayOutputStream();
        if (size < 31) {
            block.write(0x20 | size);
        } else {
            block.write(0x3F);
            int rest = size - 31;
            while (rest >= 128) {
                block.write((rest & 0x7F) | 0x80);
                rest >>>= 7;
            }
            block.write(rest);
        }
        return block.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var all = new ByteArrayOutputStream();
        for (byte[] part : parts) all.write(part, 0, part.length);
        return all.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, int pattern, String name, String value) {
        out.write(pattern);
        rawString(out, name);
        rawString(out, value);
    }

    /** A string literal without Huffman coding, its length an HPACK integer with a 7-bit prefix (RFC 7541 §5.1, §5.2). */
    private static void rawString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        int length = bytes.length;
        if (length < 127) {
            out.write(length);
        } else {
            out.write(127);
            length -= 127;
            while (length >= 128) {
                out.write((length & 0x7F) | 0x80);
                length >>>= 7;
            }
            out.write(length);
        }
        out.write(bytes, 0, bytes.length);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
