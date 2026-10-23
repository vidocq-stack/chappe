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
package io.vidocq.chappe.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.ServerConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The HTTP/1.1 request head parser (RFC 9112 §2-§5), byte by byte and over larger reads. */
class HttpRequestParserTest {

    private final HttpRequestParser parser = new HttpRequestParser();
    private final HttpRequestImpl request = new HttpRequestImpl();

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4096})
    void parsesTheRequestLineAndTheHeaders(int sliceSize) throws Exception {
        var channel = new SlicedChannel("""
                GET /path?x=1 HTTP/1.1\r
                host: example.org\r
                X-Empty:\r
                X-Blank:   \r
                X-Padded: \t value \t\r
                \r
                """, sliceSize);

        assertEquals(ParseResult.COMPLETE, parse(emptyBuffer(), channel));
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/path?x=1", request.rawUri);
        assertEquals(HttpVersion.HTTP_1_1, request.version());
        assertEquals("example.org", request.headers().firstOrNull("Host"));
        assertEquals("", request.headers().firstOrNull("X-Empty"));
        assertEquals("", request.headers().firstOrNull("X-Blank"));
        assertEquals("value", request.headers().firstOrNull("X-Padded"));
    }

    @Test
    void ignoresEmptyLinesBeforeTheRequestLineAndAcceptsBareLf() throws Exception {
        var channel = new SlicedChannel("\r\n\n\r\nPOST / HTTP/1.0\nContent-Length: 0\n\n", 1);

        assertEquals(ParseResult.COMPLETE, parse(emptyBuffer(), channel));
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/", request.rawUri);
        assertEquals(HttpVersion.HTTP_1_0, request.version());
        assertEquals("0", request.headers().firstOrNull("Content-Length"));
    }

    @Test
    void stopsAtTheEndOfTheHeadAndLeavesPipelinedBytesInTheBuffer() throws Exception {
        var buffer = bufferOf("GET /a HTTP/1.1\r\nHost: h\r\n\r\nGET /b HTTP/1.1\r\nHost: h\r\n\r\n");
        var channel = new SlicedChannel("", 1);

        assertEquals(ParseResult.COMPLETE, parse(buffer, channel));
        assertEquals("/a", request.rawUri);

        parser.reset();
        request.reset();
        assertEquals(ParseResult.COMPLETE, parse(buffer, channel));
        assertEquals("/b", request.rawUri);
        assertEquals(0, buffer.remaining());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "GET / HTTP/1.1\r\nHost: h\r\n"})
    void reportsAConnectionClosedBeforeTheEndOfTheHead(String sent) throws Exception {
        assertEquals(ParseResult.CONNECTION_CLOSED, parse(emptyBuffer(), new SlicedChannel(sent, 2)));
    }

    @ParameterizedTest
    @MethodSource("malformedRequests")
    void refusesAMalformedRequestWithItsStatus(String sent, int status) {
        var buffer = emptyBuffer();
        var channel = new SlicedChannel(sent, 7);

        var e = assertThrows(ParseException.class, () -> parse(buffer, channel));
        assertEquals(status, e.statusCode().code());
    }

    static Stream<Arguments> malformedRequests() {
        return Stream.of(
                Arguments.of("GE\rT / HTTP/1.1\r\n\r\n", 400),
                Arguments.of("GET /a\nb HTTP/1.1\r\n\r\n", 400),
                Arguments.of("GET / HTTP/2.0\r\n\r\n", 400),
                Arguments.of("GET / HTTP/1.1\r\nHost: h\r\n folded\r\n\r\n", 400),
                Arguments.of("GET / HTTP/1.1\r\nHost : h\r\n\r\n", 400),
                Arguments.of("GET / HTTP/1.1\r\nHost\r\n\r\n", 400),
                Arguments.of("BREW / HTTP/1.1\r\n\r\n", 501),
                Arguments.of("GET / HTTP/1.1\r\nX-Big: " + "a".repeat(8192) + "\r\n\r\n", 431),
                Arguments.of("GET / HTTP/1.1\r\n" + "X-H: v\r\n".repeat(101) + "\r\n", 431));
    }

    @Test
    void refusesAUriLongerThanItsLimitWith414() {
        var config = withMaxHeaderSize(64 * 1024);
        var buffer = emptyBuffer();
        var channel = new SlicedChannel("GET /" + "a".repeat(8192) + " HTTP/1.1\r\n\r\n", 4096);

        var e = assertThrows(ParseException.class, () -> parser.parse(buffer, channel, request, config));
        assertEquals(414, e.statusCode().code());
    }

    @Test
    void aParserNotResetTakesOneByteAndReportsComplete() throws Exception {
        var buffer = bufferOf("GET / HTTP/1.1\r\nHost: h\r\n\r\nXY");

        assertEquals(ParseResult.COMPLETE, parse(buffer, new SlicedChannel("", 1)));
        assertEquals(ParseResult.COMPLETE, parse(buffer, new SlicedChannel("", 1)));
        assertEquals(1, buffer.remaining());
    }

    private ParseResult parse(ByteBuffer buffer, ReadableByteChannel channel) throws ParseException, IOException {
        return parser.parse(buffer, channel, request, ServerConfig.DEFAULT);
    }

    private static ServerConfig withMaxHeaderSize(int maxHeaderSize) {
        var d = ServerConfig.DEFAULT;
        return new ServerConfig(
                d.host(),
                d.port(),
                d.backlog(),
                d.readTimeout(),
                d.writeTimeout(),
                d.idleTimeout(),
                d.maxRequestSize(),
                maxHeaderSize,
                d.maxDrainSize(),
                d.sslContext(),
                d.clientAuth(),
                d.alpnProtocols(),
                d.shutdownGracePeriod(),
                d.exposeBuildInfo());
    }

    private static ByteBuffer emptyBuffer() {
        return ByteBuffer.allocate(16 * 1024).flip();
    }

    private static ByteBuffer bufferOf(String bytes) {
        return emptyBuffer()
                .clear()
                .put(bytes.getBytes(StandardCharsets.ISO_8859_1))
                .flip();
    }
}
