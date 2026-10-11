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
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Filter;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;
import io.vidocq.chappe.http.h2.HpackDecoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Response header validation (CHAPPE-008). A header name must be an RFC 9110 token and a value a
 * field value whose chars all fit in one byte (no CR, LF, NUL or char above U+00FF):
 * <ul>
 *   <li>the response builder (and the other API entry points) refuses anything else at once,
 *       naming the header but never echoing the value;</li>
 *   <li>a response that bypasses the builder (a custom {@link Response} or {@link Headers}) is
 *       still checked by the transport before anything is written: HTTP/1.1 and HTTP/2 answer
 *       500 instead of a truncated or injected head, and invalid trailers are dropped.</li>
 * </ul>
 */
class ResponseHeaderValidationTest {

    /** U+010D and U+010A: their low bytes are CR and LF. */
    private static final String TRUNCATION_INJECTION = "ačĊSet-Cookie: a=b";

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // --- API: fail fast ---

    @Test
    void builderRejectsAValueAboveLatin1WithoutEchoingIt() {
        var e = assertThrows(
                IllegalArgumentException.class, () -> Response.builder().header("X-Evil", TRUNCATION_INJECTION));
        assertTrue(e.getMessage().contains("X-Evil"), e.getMessage());
        assertFalse(e.getMessage().contains("Set-Cookie"), "the value must not be echoed: " + e.getMessage());
    }

    @Test
    void builderRejectsControlCharsInAValue() {
        for (String value : List.of("a\r\nSet-Cookie: a=b", "a\rb", "a\nb", "a\0b", "a\u0001b", "a\u007Fb")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Response.builder().header("X", value),
                    () -> "accepted " + value.chars().boxed().toList());
        }
        assertThrows(IllegalArgumentException.class, () -> Response.builder().header("X", null));
    }

    @Test
    void builderRejectsANameThatIsNotAToken() {
        for (String name : List.of("", "a b", "a:b", "x:", "a\r\nb", "été", ":status")) {
            assertThrows(
                    IllegalArgumentException.class, () -> Response.builder().header(name, "v"), name);
        }
        assertThrows(IllegalArgumentException.class, () -> Response.builder().header(null, "v"));
    }

    @Test
    void builderAcceptsObsTextHtabAndAllTchars() {
        var response = Response.builder()
                .header("Ok-Name_1.~!#$%&'*+^`|", "café\tvalue ÿ")
                .header("Empty", "")
                .build();
        assertEquals("café\tvalue ÿ", response.headers().firstOrNull("Ok-Name_1.~!#$%&'*+^`|"));
    }

    @Test
    void builderValidatesHeadersAndTrailersGivenAsAWhole() {
        assertThrows(IllegalArgumentException.class, () -> Response.builder().headers(Headers.of("X", "a\r\nb")));
        assertThrows(IllegalArgumentException.class, () -> Response.builder().trailer("t", TRUNCATION_INJECTION));
        assertThrows(IllegalArgumentException.class, () -> Response.builder().trailer("a b", "v"));
        assertThrows(IllegalArgumentException.class, () -> Response.builder().trailers(Headers.of("t", "a\nb")));
    }

    @Test
    void filterAddHeaderRejectsAnInvalidHeaderWhenCreated() {
        assertThrows(IllegalArgumentException.class, () -> Filter.addHeader("X", "a\r\nb"));
        assertThrows(IllegalArgumentException.class, () -> Filter.addHeader("X Y", "v"));
        assertThrows(IllegalArgumentException.class, () -> Filter.addHeaderIf(() -> true, "X", TRUNCATION_INJECTION));
    }

    @Test
    void webSocketUpgradeRejectsASubprotocolThatIsNotAToken() {
        WebSocketHandler handler = new WebSocketHandler() {};
        assertThrows(IllegalArgumentException.class, () -> new WebSocketUpgrade(handler, "chat\r\nX-Evil: 1"));
        assertThrows(IllegalArgumentException.class, () -> new WebSocketUpgrade(handler, "a b"));
        assertEquals("chat.v2", new WebSocketUpgrade(handler, "chat.v2").subprotocol());
        assertNull(new WebSocketUpgrade(handler, null).subprotocol());
    }

    // --- HTTP/1.1 writer: defence in depth ---

    @Test
    void http11AnswersA500ForAHeaderValueAboveLatin1() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of("X-Evil", TRUNCATION_INJECTION), Headers.empty()));

        String raw = http11Exchange();

        assertTrue(raw.startsWith("HTTP/1.1 500"), raw);
        assertFalse(raw.contains("Set-Cookie"), raw);
        assertFalse(raw.contains("X-Evil"), raw);
    }

    @Test
    void http11AnswersA500ForCrLfInAHeaderValue() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of("X-Evil", "a\r\nSet-Cookie: a=b"), Headers.empty()));

        String raw = http11Exchange();

        assertTrue(raw.startsWith("HTTP/1.1 500"), raw);
        assertFalse(raw.contains("Set-Cookie"), raw);
    }

    @Test
    void http11AnswersA500ForAHeaderNameThatIsNotAToken() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of("Set-Cookie: a=b\r\nX", "v"), Headers.empty()));

        String raw = http11Exchange();

        assertTrue(raw.startsWith("HTTP/1.1 500"), raw);
        assertFalse(raw.contains("Set-Cookie"), raw);
    }

    @Test
    void http11AnswersA500ForAnInjectedReasonPhrase() throws IOException {
        start(_ -> rawResponse(StatusCode.of(299, "OK\r\nSet-Cookie: a=b"), Headers.empty(), Headers.empty()));

        String raw = http11Exchange();

        assertTrue(raw.startsWith("HTTP/1.1 500"), raw);
        assertFalse(raw.contains("Set-Cookie"), raw);
    }

    @Test
    void http11WritesObsTextAsIsoLatin1() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of("X-Name", "café"), Headers.empty()));

        String raw = http11Exchange();

        assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
        assertTrue(raw.contains("X-Name: café\r\n"), raw);
    }

    // --- HTTP/2 (HPACK): same rule ---

    @Test
    void http2AnswersA500ForAHeaderValueAboveLatin1() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of("x-evil", "ačĊb"), Headers.empty()));

        var seq = http2Exchange();

        var head = seq.headers.getFirst();
        assertEquals("500", head.get(":status"), head.toString());
        assertFalse(head.containsKey("x-evil"), head.toString());
    }

    @Test
    void http2AnswersA500ForCrLfOrNulInAHeaderValue() throws IOException {
        for (String value : List.of("a\r\nb", "a\0b")) {
            start(_ -> rawResponse(StatusCode.OK, Headers.of("x-evil", value), Headers.empty()));

            var head = http2Exchange().headers.getFirst();

            assertEquals("500", head.get(":status"), head.toString());
            assertFalse(head.containsKey("x-evil"), head.toString());
            server.stop();
        }
        server = null;
    }

    @Test
    void http2AnswersA500ForAPseudoHeaderName() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, Headers.of(":status", "204"), Headers.empty()));

        var head = http2Exchange().headers.getFirst();

        assertEquals("500", head.get(":status"), head.toString());
    }

    @Test
    void http2DropsAnInvalidTrailerAndKeepsTheValidOnes() throws IOException {
        var trailers = Headers.builder()
                .add("x-evil", "ačĊb")
                .add("x-crlf", "a\r\nb")
                .add("bad name", "v")
                .add("x-good", "café")
                .build();
        start(_ -> rawResponse(StatusCode.OK, Headers.empty(), trailers));

        var seq = http2Exchange();

        assertEquals("200", seq.headers.getFirst().get(":status"));
        var last = seq.headers.getLast();
        assertEquals("café", last.get("x-good"), last.toString());
        assertFalse(last.containsKey("x-evil"), last.toString());
        assertFalse(last.containsKey("x-crlf"), last.toString());
        assertFalse(last.containsKey("bad name"), last.toString());
    }

    // --- a Headers that changes between iterations: the checked fields are the written ones ---

    @Test
    void http11WritesTheFieldsItChecked() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, new ShiftingHeaders(1), Headers.empty()));

        String raw = http11Exchange();

        assertFalse(raw.contains("Set-Cookie"), raw);
    }

    @Test
    void http2EncodesTheFieldsItChecked() throws IOException {
        start(_ -> rawResponse(StatusCode.OK, new ShiftingHeaders(2), Headers.empty()));

        Map<String, String> head;
        try {
            head = http2Exchange().headers.getFirst();
        } catch (IOException reset) {
            return; // a reset stream is fine: nothing was injected
        }
        assertFalse(String.valueOf(head.get("x-shift")).contains("\n"), head.toString());
    }

    /**
     * One header whose value is valid for the first {@code validIterations} iterations, then
     * carries CR LF and a forged header line: a transport that checks one iteration and writes
     * another would inject it.
     */
    private static final class ShiftingHeaders implements Headers {
        private final int validIterations;
        private final java.util.concurrent.atomic.AtomicInteger iterations =
                new java.util.concurrent.atomic.AtomicInteger();

        ShiftingHeaders(int validIterations) {
            this.validIterations = validIterations;
        }

        @Override
        public java.util.Iterator<Entry> iterator() {
            String value = iterations.incrementAndGet() <= validIterations ? "ok" : "a\r\nSet-Cookie: a=b";
            return List.of(new Entry("X-Shift", value)).iterator();
        }

        @Override
        public java.util.Optional<String> first(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public List<String> all(String name) {
            return List.of();
        }

        @Override
        public boolean contains(String name) {
            return false;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    // --- helpers ---

    private void start(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
    }

    /** A response that bypasses the builder's checks, as a custom implementation may. */
    private static Response rawResponse(StatusCode status, Headers headers, Headers trailers) {
        return new Response() {
            @Override
            public StatusCode status() {
                return status;
            }

            @Override
            public Headers headers() {
                return headers;
            }

            @Override
            public Body body() {
                return Body.of("hello");
            }

            @Override
            public Headers trailers() {
                return trailers;
            }
        };
    }

    /** Sends one request with {@code Connection: close} and reads the raw response to EOF. */
    private String http11Exchange() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write("GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    // --- minimal HTTP/2 prior-knowledge client ---

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;

    private record Frame(int type, int flags, int streamId, byte[] payload) {}

    private static final class Exchange {
        final List<Map<String, String>> headers = new ArrayList<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
    }

    /** GET / on stream 1; returns every header block (decoded as ISO-8859-1) and the body. */
    private Exchange http2Exchange() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            out.write(H2_PREFACE);
            writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
            var block = new ByteArrayOutputStream();
            literal(block, ":method", "GET");
            literal(block, ":scheme", "http");
            literal(block, ":authority", "127.0.0.1");
            literal(block, ":path", "/");
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, block.toByteArray());
            out.flush();

            var exchange = new Exchange();
            var decoder = new HpackDecoder(4096, 64 * 1024);
            while (true) {
                Frame f = readFrame(in);
                if (f.type == TYPE_SETTINGS && (f.flags & FLAG_ACK) == 0) {
                    writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                    out.flush();
                    continue;
                }
                if (f.type == TYPE_GOAWAY) throw new IOException("GOAWAY from the server");
                if (f.streamId != 1) continue;
                if (f.type == TYPE_RST_STREAM) throw new IOException("RST_STREAM from the server");
                if (f.type == TYPE_HEADERS) {
                    var map = new LinkedHashMap<String, String>();
                    try {
                        decoder.decode(ByteBuffer.wrap(f.payload), map::put);
                    } catch (Exception e) {
                        throw new IOException("HPACK decode failed", e);
                    }
                    exchange.headers.add(map);
                } else if (f.type == TYPE_DATA) {
                    exchange.body.write(f.payload);
                }
                if ((f.type == TYPE_HEADERS || f.type == TYPE_DATA) && (f.flags & FLAG_END_STREAM) != 0) {
                    return exchange;
                }
            }
        }
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type);
        out.write(flags);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    private static Frame readFrame(InputStream raw) throws IOException {
        var in = (DataInputStream) raw;
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int streamId = in.readInt() & 0x7FFFFFFF;
        return new Frame(type, flags, streamId, in.readNBytes(len));
    }

    /** HPACK literal without indexing, new name, no Huffman (RFC 7541 §6.2.2). */
    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        rawString(out, name);
        rawString(out, value);
    }

    private static void rawString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes.length); // short strings only: 7-bit length prefix, H=0
        out.write(bytes, 0, bytes.length);
    }
}
