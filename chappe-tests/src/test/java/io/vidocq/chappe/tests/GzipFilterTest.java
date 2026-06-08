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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Filter;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.Test;

class GzipFilterTest {

    private static final String LARGE_PAYLOAD;

    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("Hello, gzip world! ");
        LARGE_PAYLOAD = sb.toString();
    }

    @Test
    void compressLargeTextResponse() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(LARGE_PAYLOAD)
                .build();
        Handler gz = Filter.gzip().apply(base);

        Response r = gz.handle(reqAccepting("gzip"));
        assertEquals("gzip", r.headers().firstOrNull("Content-Encoding"));
        assertEquals("Accept-Encoding", r.headers().firstOrNull("Vary"));

        byte[] decompressed;
        try (var in = r.body().asInputStream();
                var gin = new GZIPInputStream(in)) {
            decompressed = gin.readAllBytes();
        }
        assertArrayEquals(LARGE_PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8), decompressed);
    }

    @Test
    void skipWhenClientDoesNotAcceptGzip() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .body(LARGE_PAYLOAD)
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("identity"));
        assertNull(r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void skipWhenAlreadyEncoded() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .header("Content-Encoding", "br")
                .body(LARGE_PAYLOAD)
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("gzip"));
        assertEquals("br", r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void skipWhenNoTransform() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .header("Cache-Control", "no-transform, max-age=60")
                .body(LARGE_PAYLOAD)
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("gzip"));
        assertNull(r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void skipWhenContentTypeNotCompressible() throws Exception {
        byte[] image = new byte[2048]; // > threshold
        Handler base = _ -> Response.builder()
                .header("Content-Type", "image/png")
                .body(image)
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("gzip"));
        assertNull(r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void skipWhenBodyTooSmall() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .body("short")
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("gzip"));
        assertNull(r.headers().firstOrNull("Content-Encoding"));
    }

    @Test
    void compressionReducesSizeForRepetitiveContent() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .body(LARGE_PAYLOAD)
                .build();
        Response uncompressed = base.handle(reqAccepting("gzip"));
        long origSize = uncompressed.body().contentLength();

        Handler gz = Filter.gzip().apply(base);
        Response compressed = gz.handle(reqAccepting("gzip"));
        long gzSize = compressed.body().contentLength();

        // Repetitive content should compress at least 5x
        assertNotEquals(origSize, gzSize);
        assertEquals(true, gzSize * 5 < origSize, "expected ratio > 5: orig=" + origSize + " gz=" + gzSize);
    }

    @Test
    void preservesStatusCodeAndOtherHeaders() throws Exception {
        Handler base = _ -> Response.builder()
                .status(StatusCode.CREATED)
                .header("Content-Type", "application/json")
                .header("X-Custom", "kept")
                .body(LARGE_PAYLOAD)
                .build();
        Handler gz = Filter.gzip().apply(base);
        Response r = gz.handle(reqAccepting("gzip"));
        assertEquals(StatusCode.CREATED, r.status());
        assertEquals("kept", r.headers().firstOrNull("X-Custom"));
        assertEquals("gzip", r.headers().firstOrNull("Content-Encoding"));
    }

    private static Request reqAccepting(String acceptEncoding) {
        Headers h = Headers.builder().add("Accept-Encoding", acceptEncoding).build();
        return new Request() {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public URI uri() {
                return URI.create("http://test/");
            }

            @Override
            public String path() {
                return "/";
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public HttpVersion version() {
                return HttpVersion.HTTP_1_1;
            }

            @Override
            public Headers headers() {
                return h;
            }

            @Override
            public Body body() {
                return Body.empty();
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParams() {
                return Map.of();
            }
        };
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(Body b) throws Exception {
        try (var in = b.asInputStream();
                var bin = new ByteArrayInputStream(new byte[0])) {
            return in.readAllBytes();
        }
    }
}
