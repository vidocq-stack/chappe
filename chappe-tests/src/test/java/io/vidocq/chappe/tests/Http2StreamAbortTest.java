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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/2 streams whose exchange is aborted on either side: a response body failing mid-stream
 * resets the stream, a request body cut by a reset or by the end of the connection fails the read,
 * and a flow-control overflow reset releases the response body being sent.
 */
class Http2StreamAbortTest {

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;
    private static final int INTERNAL_ERROR = 0x2;
    private static final int FLOW_CONTROL_ERROR = 0x3;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** A response body that yields a few bytes, then fails: a live body the application aborted. */
    private static final class FailingBody extends InputStream {
        private boolean served;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (served) throw new IOException("response aborted");
            served = true;
            b[off] = 'x';
            return 1;
        }
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
                if (closed.await(20, TimeUnit.SECONDS)) throw new IOException("body closed");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void aResponseBodyFailingMidStreamResetsTheStream() throws Exception {
        start(_ -> Response.builder().body(Body.of(new FailingBody(), -1)).build());
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, encodeRequestHeaders("GET", "/"));
            out.flush();
            Frame f;
            do {
                f = readFrame(in); // a hang here (SocketTimeoutException) is the bug
                assertFalse(
                        f.streamId == 1 && (f.flags & FLAG_END_STREAM) != 0 && f.type != TYPE_RST_STREAM,
                        "an aborted body must not end the stream cleanly");
            } while (!(f.type == TYPE_RST_STREAM && f.streamId == 1));
            assertEquals(INTERNAL_ERROR, ByteBuffer.wrap(f.payload).getInt());
        }
    }

    @Test
    void aRequestBodyCutByAStreamResetFailsTheRead() throws Exception {
        var outcome = new CompletableFuture<String>();
        start(req -> readOutcome(req.body().asInputStream(), outcome));
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, encodeRequestHeaders("POST", "/"));
            writeFrame(out, TYPE_DATA, 0, 1, "abc".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, TYPE_RST_STREAM, 0, 1, new byte[] {0, 0, 0, 0x8}); // CANCEL
            out.flush();
            assertEquals("IOException", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aRequestBodyCutByTheEndOfTheConnectionFailsTheRead() throws Exception {
        var outcome = new CompletableFuture<String>();
        var reading = new CountDownLatch(1);
        start(req -> {
            reading.countDown();
            return readOutcome(req.body().asInputStream(), outcome);
        });
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, encodeRequestHeaders("POST", "/"));
            writeFrame(out, TYPE_DATA, 0, 1, "abc".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertTrue(reading.await(5, TimeUnit.SECONDS));
        }
        assertEquals("IOException", outcome.get(5, TimeUnit.SECONDS));
    }

    @Test
    void aRequestBodyEndedByEndStreamReadsToTheEnd() throws Exception {
        var outcome = new CompletableFuture<String>();
        start(req -> readOutcome(req.body().asInputStream(), outcome));
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, encodeRequestHeaders("POST", "/"));
            writeFrame(out, TYPE_DATA, FLAG_END_STREAM, 1, "abc".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals("body:abc", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aWindowUpdateOverflowReleasesTheResponseBody() throws Exception {
        var body = new ObservedBody();
        start(_ -> Response.builder().body(Body.of(body, -1)).build());
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, encodeRequestHeaders("GET", "/"));
            out.flush();
            awaitFrame(in, TYPE_HEADERS, 1);
            // The stream window (65535) plus 2^31-1 overflows: RST_STREAM FLOW_CONTROL_ERROR (RFC 9113 §6.9.1).
            writeFrame(out, TYPE_WINDOW_UPDATE, 0, 1, new byte[] {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            out.flush();
            assertEquals(
                    FLOW_CONTROL_ERROR,
                    ByteBuffer.wrap(awaitFrame(in, TYPE_RST_STREAM, 1).payload).getInt());
            assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the body of the reset stream must be released");
        }
    }

    @Test
    void aSettingsWindowOverflowReleasesTheResponseBody() throws Exception {
        var body = new ObservedBody();
        start(_ -> Response.builder().body(Body.of(body, -1)).build());
        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            sendPrefaceAndSettings(in, out);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, encodeRequestHeaders("GET", "/"));
            out.flush();
            awaitFrame(in, TYPE_HEADERS, 1);
            // SETTINGS_INITIAL_WINDOW_SIZE (0x4) = 2^31-1: the open stream's window overflows.
            writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[] {0, 0x4, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            out.flush();
            assertEquals(
                    FLOW_CONTROL_ERROR,
                    ByteBuffer.wrap(awaitFrame(in, TYPE_RST_STREAM, 1).payload).getInt());
            assertTrue(body.closed.await(5, TimeUnit.SECONDS), "the body of the reset stream must be released");
        }
    }

    // ---- helpers ----

    private static Response readOutcome(InputStream body, CompletableFuture<String> outcome) {
        try {
            outcome.complete("body:" + new String(body.readAllBytes(), StandardCharsets.US_ASCII));
        } catch (IOException _) {
            outcome.complete("IOException");
        }
        return Response.ok();
    }

    private void start(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
    }

    private Socket connect() throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static void sendPrefaceAndSettings(DataInputStream in, DataOutputStream out) throws IOException {
        out.write(H2_PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();
        for (int i = 0; i < 2; i++) readFrame(in); // server SETTINGS + ACK of ours
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

    private record Frame(int type, int flags, int streamId, byte[] payload) {}

    private static Frame readFrame(DataInputStream in) throws IOException {
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int streamId = in.readInt() & 0x7FFFFFFF;
        return new Frame(type, flags, streamId, in.readNBytes(len));
    }

    private static Frame awaitFrame(DataInputStream in, int type, int streamId) throws IOException {
        Frame f;
        do {
            f = readFrame(in);
        } while (f.type != type || f.streamId != streamId);
        return f;
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
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
