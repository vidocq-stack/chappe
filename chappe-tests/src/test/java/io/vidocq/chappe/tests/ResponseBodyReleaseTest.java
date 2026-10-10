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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Once a handler returned a response, chappe closes (releases) its body on every exit: after a
 * complete write, when writing the head fails, when the body is never read (HEAD), when an
 * HTTP/2 stream is reset or its connection goes away. A live body whose producer waits for its
 * reader would otherwise block that producer forever.
 */
class ResponseBodyReleaseTest {

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** A live body that has nothing to read until it is closed; its close is observable. */
    private static final class ObservedBody extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                if (closed.await(10, TimeUnit.SECONDS)) throw new IOException("body closed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return -1;
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void http11HeadResponseBodyIsReleased() throws Exception {
        var body = new ObservedBody();
        start(_ -> Response.builder().body(Body.of(body, -1)).build());
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            send(socket.getOutputStream(), "HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 200"));
            assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the unread HEAD body must be closed");
        }
    }

    @Test
    void http11BodyIsReleasedWhenWritingTheHeadFails() throws Exception {
        var body = new ObservedBody();
        var clientGone = new CountDownLatch(1);
        var handling = new CountDownLatch(1);
        start(_ -> {
            handling.countDown();
            clientGone.await(5, TimeUnit.SECONDS);
            var builder = Response.builder().body(Body.of(body, -1));
            // A head far larger than the write buffer: writing it hits the reset connection.
            String value = "v".repeat(4096);
            for (int i = 0; i < 128; i++) builder.header("X-Filler-" + i, value);
            return builder.build();
        });
        var socket = new Socket("127.0.0.1", server.port());
        send(socket.getOutputStream(), "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        // Reset only once the request was read: a reset discards unread request bytes.
        assertTrue(handling.await(5, TimeUnit.SECONDS));
        socket.setSoLinger(true, 0); // close with RST: the server's next write fails
        socket.close();
        clientGone.countDown();
        assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the body must be closed when the head write fails");
    }

    @Test
    void http2BodyIsReleasedWhenTheStreamIsResetBeforeTheHeaders() throws Exception {
        var body = new ObservedBody();
        var resetSent = new CountDownLatch(1);
        start(_ -> {
            resetSent.await(5, TimeUnit.SECONDS);
            return Response.builder().body(Body.of(body, -1)).build();
        });
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, encodeRequestHeaders("GET", "/"));
            writeFrame(out, TYPE_RST_STREAM, 0, 1, new byte[] {0, 0, 0, 0x8}); // CANCEL
            out.flush();
            resetSent.countDown();
            assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the body of a reset stream must be closed");
        }
    }

    @Test
    void http2BodyIsReleasedWhenTheConnectionGoesAway() throws Exception {
        var body = new ObservedBody();
        start(_ -> Response.builder().body(Body.of(body, -1)).build());
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, encodeRequestHeaders("GET", "/"));
            out.flush();
            // Wait for the response HEADERS: the body is then being read.
            int type;
            do {
                type = readFrameType(in);
            } while (type != TYPE_HEADERS);
        }
        assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the body must be closed when the connection ends");
    }

    // ---- helpers ----

    private void start(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
    }

    private static void send(OutputStream out, String request) throws IOException {
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            head.write(b);
            matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static void sendPrefaceAndSettings(DataInputStream in, DataOutputStream out) throws IOException {
        out.write(H2_PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();
        for (int i = 0; i < 2; i++) readFrameType(in); // server SETTINGS + ACK of ours
        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        out.flush();
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    /** Reads one frame and returns its type (the payload is skipped). */
    private static int readFrameType(DataInputStream in) throws IOException {
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int type = in.readUnsignedByte();
        in.readUnsignedByte(); // flags
        in.readInt(); // stream id
        in.readNBytes(len);
        return type;
    }

    /** HPACK literal header fields without indexing, no Huffman (RFC 7541 §6.2.2). */
    private static byte[] encodeRequestHeaders(String method, String path) {
        var out = new ByteArrayOutputStream();
        literal(out, ":method", method);
        literal(out, ":scheme", "http");
        literal(out, ":authority", "127.0.0.1");
        literal(out, ":path", path);
        return out.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        rawString(out, name);
        rawString(out, value);
    }

    private static void rawString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length >= 127) throw new IllegalArgumentException("test helper: short strings only");
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
