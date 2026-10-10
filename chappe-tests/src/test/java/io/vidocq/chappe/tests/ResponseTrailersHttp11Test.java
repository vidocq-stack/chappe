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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/1.1 response trailer fields (RFC 9112 §7.1.2) over a raw socket.
 *
 * <p>A chunked response writes {@link Response#trailers()} after the last-chunk
 * {@code 0\r\n}, the way the Servlet TCK {@code TrailerTest} expects; the
 * trailers are queried once the body has been fully consumed, mirroring the
 * HTTP/2 transport.</p>
 */
class ResponseTrailersHttp11Test {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void trailersFollowTheLastChunk() throws IOException {
        start(_ -> Response.builder()
                .body(Body.of(stream("hello"), -1))
                .trailer("myTrailer", "foo")
                .build());

        String raw = exchange();

        assertTrue(raw.contains("Transfer-Encoding: chunked"), raw);
        assertTrue(raw.endsWith("5\r\nhello\r\n0\r\nmyTrailer: foo\r\n\r\n"), raw);
    }

    @Test
    void emptyTrailersKeepTheTerminator() throws IOException {
        start(_ -> Response.builder().body(Body.of(stream("hello"), -1)).build());

        String raw = exchange();

        assertTrue(raw.endsWith("5\r\nhello\r\n0\r\n\r\n"), raw);
    }

    @Test
    void trailersAreComputedAfterTheBody() throws IOException {
        start(_ -> {
            var seenAtEof = new AtomicReference<String>("not-yet");
            InputStream body = new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int n = super.read(b, off, len);
                    if (n == -1) seenAtEof.set("eof-reached");
                    return n;
                }
            };
            var delegate = Response.builder().body(Body.of(body, -1)).build();
            return new Response() {
                @Override
                public StatusCode status() {
                    return delegate.status();
                }

                @Override
                public Headers headers() {
                    return delegate.headers();
                }

                @Override
                public Body body() {
                    return delegate.body();
                }

                @Override
                public Headers trailers() {
                    return Headers.of("myTrailer", seenAtEof.get());
                }
            };
        });

        String raw = exchange();

        assertTrue(raw.endsWith("0\r\nmyTrailer: eof-reached\r\n\r\n"), raw);
    }

    @Test
    void trailerWithCrLfIsDropped() throws IOException {
        start(_ -> Response.builder()
                .body(Body.of(stream("hello"), -1))
                .trailer("good", "kept")
                .trailer("evil", "x\r\nInjected: yes")
                .trailer("bad\nname", "v")
                .trailer("nul", "a\0b")
                .build());

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            request(out, "/one", false);
            String first = readChunkedResponse(in);
            assertTrue(first.endsWith("0\r\ngood: kept\r\n\r\n"), first);
            assertFalse(first.contains("Injected"), first);

            // Framing must stay intact: the keep-alive connection serves a second request.
            request(out, "/two", false);
            String second = readChunkedResponse(in);
            assertTrue(second.startsWith("HTTP/1.1 200"), second);
        }
    }

    /**
     * Chars above U+00FF must not be truncated to their low byte on the wire:
     * U+010D/U+010A would otherwise become CR/LF and split the trailer section.
     */
    @Test
    void trailerValueAboveLatin1IsDropped() throws IOException {
        start(_ -> Response.builder()
                .body(Body.of(stream("hello"), -1))
                .trailer("evil", "xčĊInjected: yes")
                .trailer("nul", "aĀb")
                .trailer("ctl", "a\u0001b")
                .trailer("good", "kept\tvalue with obs-text é")
                .build());

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            request(out, "/one", false);
            String first = readChunkedResponse(in, StandardCharsets.ISO_8859_1);
            assertTrue(first.endsWith("0\r\ngood: kept\tvalue with obs-text é\r\n\r\n"), first);
            assertFalse(first.contains("Injected"), first);

            request(out, "/two", false);
            assertTrue(readChunkedResponse(in).startsWith("HTTP/1.1 200"), "framing must stay intact");
        }
    }

    /** Trailer names must be RFC 9110 §5.6.2 tokens; anything else is dropped. */
    @Test
    void trailerNameThatIsNotATokenIsDropped() throws IOException {
        start(_ -> Response.builder()
                .body(Body.of(stream("hello"), -1))
                .trailer("a: b", "v1")
                .trailer("a b", "v2")
                .trailer("x:", "v3")
                .trailer("", "v4")
                .trailer("été", "v5")
                .trailer("Ok-Name_1.~!#$%&'*+^`|", "kept")
                .build());

        String raw = exchange();

        assertTrue(raw.endsWith("0\r\nOk-Name_1.~!#$%&'*+^`|: kept\r\n\r\n"), raw);
        assertFalse(raw.contains("v1") || raw.contains("v2") || raw.contains("v3") || raw.contains("v5"), raw);
    }

    @Test
    void closeDelimitedBodyHasNoTrailers() throws IOException {
        start(_ -> Response.builder()
                .body(Body.of(stream("hello"), -1))
                .trailer("myTrailer", "foo")
                .build());

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            request(socket.getOutputStream(), "/", true);
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertFalse(raw.contains("Transfer-Encoding"), raw);
            assertTrue(raw.endsWith("\r\n\r\nhello"), raw);
        }
    }

    // --- helpers ---

    private void start(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    private String exchange() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            request(socket.getOutputStream(), "/", false);
            return readChunkedResponse(socket.getInputStream());
        }
    }

    private static void request(OutputStream out, String path, boolean close) throws IOException {
        String req = "GET " + path + " HTTP/1.1\r\nHost: h\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Reads exactly one chunked response (headers, chunks, last-chunk and
     * trailer section) and returns its raw bytes, leaving the keep-alive
     * connection positioned on the next response.
     */
    private static String readChunkedResponse(InputStream in) throws IOException {
        return readChunkedResponse(in, StandardCharsets.US_ASCII);
    }

    private static String readChunkedResponse(InputStream in, java.nio.charset.Charset charset) throws IOException {
        var raw = new ByteArrayOutputStream();
        String line;
        do {
            line = readLine(in, raw);
        } while (!line.isEmpty()); // headers
        while (true) {
            int size = Integer.parseInt(readLine(in, raw), 16);
            if (size == 0) break;
            raw.write(in.readNBytes(size + 2)); // data + CRLF
        }
        do {
            line = readLine(in, raw);
        } while (!line.isEmpty()); // trailer section
        return raw.toString(charset);
    }

    private static String readLine(InputStream in, ByteArrayOutputStream raw) throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            raw.write(b);
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        if (b == -1) throw new IOException("unexpected EOF, read so far: " + raw.toString(StandardCharsets.US_ASCII));
        return sb.toString();
    }
}
