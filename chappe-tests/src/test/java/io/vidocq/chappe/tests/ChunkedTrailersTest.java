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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/1.1 chunked trailer fields (RFC 9112 §7.1.2) over a raw socket.
 *
 * <p>Mirrors the Servlet TCK {@code HttpServletRequest40Tests.TrailerTest}
 * request byte-for-byte (the TCK client reads the socket without
 * {@code SO_TIMEOUT}, so a server that never answers froze the whole suite —
 * foy BUG-20260611-01). {@code Request.trailers()} must expose the parsed
 * trailer fields once the body has been fully read, mirroring the HTTP/2
 * trailers behavior.</p>
 */
class ChunkedTrailersTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    var t1 = req.trailers().firstOrNull("myTrailer");
                    var t2 = req.trailers().firstOrNull("myTrailer2");
                    return Response.ok("body=" + body + ";myTrailer=" + t1 + ";myTrailer2=" + t2);
                })
                .build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** Exact TCK TrailerTest shape: Trailer header, two trailer fields, keep-alive. */
    @Test
    void tckTrailerRequest_isAnsweredAndTrailersExposed() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000); // the real TCK client has NO timeout — a hang here froze the suite
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /TrailerTestServlet HTTP/1.1\r\n");
            write(out, "Host: 127.0.0.1:" + port + "\r\n");
            write(out, "Connection: keep-alive\r\n");
            write(out, "Content-Type: text/plain\r\n");
            write(out, "Transfer-Encoding: chunked\r\n");
            write(out, "Trailer: myTrailer, myTrailer2\r\n");
            write(out, "\r\n");
            write(out, "3\r\n");
            write(out, "ABC\r\n");
            write(out, "0\r\n");
            write(out, "myTrailer:foo\r\n");
            write(out, "myTrailer2:bar\r\n");
            write(out, "\r\n");
            out.flush();

            // keep-alive connection: read exactly one framed response (EOF never comes)
            var response = readOneResponse(in);
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(response.contains("body=ABC"), "Body should be decoded: " + response);
            assertTrue(response.contains("myTrailer=foo"), "Trailer 1 should be exposed: " + response);
            assertTrue(response.contains("myTrailer2=bar"), "Trailer 2 should be exposed: " + response);
        }
    }

    /** Trailer values may carry optional whitespace after the colon (RFC 9110 field syntax). */
    @Test
    void trailerValueWhitespaceIsTrimmed() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /t HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
            write(out, "1\r\nX\r\n0\r\nmyTrailer:  spaced value \r\n\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("myTrailer=spaced value"), "Value should be trimmed: " + response);
        }
    }

    /** No trailers after the terminal chunk → trailers() stays empty, request still answered. */
    @Test
    void chunkedWithoutTrailers_trailersEmpty() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /t HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
            write(out, "3\r\nABC\r\n0\r\n\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("200"), "Should be 200 OK: " + response);
            assertTrue(response.contains("myTrailer=null"), "No trailers expected: " + response);
        }
    }

    /**
     * Keep-alive safety: the trailer block must be consumed exactly, so a
     * pipelined second request parses cleanly after it.
     */
    @Test
    void keepAlive_secondRequestAfterTrailers() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /one HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n");
            write(out, "3\r\nABC\r\n0\r\nmyTrailer:foo\r\n\r\n");
            out.flush();

            var first = readOneResponse(in);
            assertTrue(first.contains("200"), "First response should be 200: " + first);
            assertTrue(first.contains("myTrailer=foo"), "First trailers should be exposed: " + first);

            write(out, "POST /two HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
            write(out, "2\r\nZZ\r\n0\r\nmyTrailer2:bar\r\n\r\n");
            out.flush();

            var second = readResponse(in);
            assertTrue(second.contains("200"), "Second response should be 200: " + second);
            assertTrue(second.contains("body=ZZ"), "Second body should be decoded: " + second);
            assertTrue(second.contains("myTrailer=null"), "Trailers must reset between requests: " + second);
            assertTrue(second.contains("myTrailer2=bar"), "Second trailers should be exposed: " + second);
        }
    }

    /**
     * Trailers must survive the {@code Router.mount} wrapper (foy mounts the
     * Servlet bridge this way — BUG-20260611-01 second leg: the mount wrapper
     * fell back to the {@code trailers()} interface default).
     */
    @Test
    void trailersSurviveMountWrapper() throws IOException {
        var router = io.vidocq.chappe.api.Router.builder()
                .mount("/ctx", req -> {
                    String body;
                    try {
                        body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return Response.of(io.vidocq.chappe.api.StatusCode.INTERNAL_SERVER_ERROR);
                    }
                    return Response.ok(
                            "body=" + body + ";myTrailer=" + req.trailers().firstOrNull("myTrailer"));
                })
                .build();
        var mounted = Server.builder().port(0).handler(router).build();
        mounted.start();
        try (var socket = new Socket("127.0.0.1", mounted.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /ctx/t HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
            write(out, "3\r\nABC\r\n0\r\nmyTrailer:foo\r\n\r\n");
            out.flush();

            var response = readResponse(in);
            assertTrue(response.contains("myTrailer=foo"), "Trailers must traverse the mount wrapper: " + response);
        } finally {
            mounted.stop();
        }
    }

    /** Trailers must also be parsed when the handler does NOT read the body (drain path). */
    @Test
    void drainPath_doesNotDesyncKeepAlive() throws IOException {
        var ignoring =
                Server.builder().port(0).handler(req -> Response.ok("ignored")).build();
        ignoring.start();
        try (var socket = new Socket("127.0.0.1", ignoring.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            write(out, "POST /one HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n");
            write(out, "3\r\nABC\r\n0\r\nmyTrailer:foo\r\n\r\n");
            out.flush();
            var first = readOneResponse(in);
            assertTrue(first.contains("200"), "First response should be 200: " + first);

            write(out, "GET /two HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            out.flush();
            var second = readResponse(in);
            assertTrue(second.contains("200"), "Keep-alive must survive unread trailers: " + second);
        } finally {
            ignoring.stop();
        }
    }

    private void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads exactly one Content-Length-framed response, leaving the connection usable. */
    private String readOneResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int headerEnd = sb.indexOf("\r\n\r\n");
            if (headerEnd >= 0) {
                String headers = sb.substring(0, headerEnd + 4);
                var clIdx = headers.toLowerCase().indexOf("content-length: ");
                int contentLength = 0;
                if (clIdx >= 0) {
                    var clEnd = headers.indexOf("\r\n", clIdx);
                    contentLength = Integer.parseInt(
                            headers.substring(clIdx + 16, clEnd).trim());
                }
                int bodyReceived = sb.length() - (headerEnd + 4);
                if (bodyReceived >= contentLength) return sb.toString();
            }
        }
        return sb.toString();
    }

    private String readResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }
}
