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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

/**
 * CHAPPE-019: {@link Filter#addHeaderIf} and {@link Filter#gzip} used to rebuild the response from its status,
 * headers and body, so the trailers of the handler's response (gRPC-style status, Servlet trailer fields) were
 * lost, including trailers computed once the body has been read.
 */
class FilterTrailersTest {

    private static final String PAYLOAD = "trailers survive the filters ".repeat(100);

    /** A response whose trailer names the body as read only once the transport has consumed it. */
    private static Response withLateTrailer() {
        var consumed = new AtomicBoolean();
        return new Response() {
            @Override
            public StatusCode status() {
                return StatusCode.OK;
            }

            @Override
            public Headers headers() {
                return Headers.of("Content-Type", "text/plain");
            }

            @Override
            public Body body() {
                InputStream in =
                        new FilterInputStream(new ByteArrayInputStream(PAYLOAD.getBytes(StandardCharsets.UTF_8))) {
                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = super.read(b, off, len);
                                if (n < 0) consumed.set(true);
                                return n;
                            }

                            @Override
                            public int read() throws IOException {
                                int n = super.read();
                                if (n < 0) consumed.set(true);
                                return n;
                            }
                        };
                return Body.of(in);
            }

            @Override
            public Headers trailers() {
                return Headers.of("x-checksum", consumed.get() ? "after-body" : "too-early");
            }
        };
    }

    /** What a transport does: reads the whole body, then asks for the trailers. */
    private static String trailerAfterBody(Response r) throws IOException {
        try (InputStream in = r.body().asInputStream()) {
            in.readAllBytes();
        }
        return r.trailers().firstOrNull("x-checksum");
    }

    @Test
    void addHeader_keeps_the_trailers() throws Exception {
        Handler base = _ -> withLateTrailer();

        Response r = Filter.addHeader("X-Foo", "bar").apply(base).handle(request(Headers.empty()));

        assertEquals("bar", r.headers().firstOrNull("X-Foo"));
        assertEquals("after-body", trailerAfterBody(r));
    }

    @Test
    void addHeaderIf_false_keeps_the_trailers_too() throws Exception {
        Handler base = _ -> withLateTrailer();

        Response r = Filter.addHeaderIf(() -> false, "X-Foo", "bar").apply(base).handle(request(Headers.empty()));

        assertEquals("after-body", trailerAfterBody(r));
    }

    @Test
    void gzip_keeps_the_trailers() throws Exception {
        Handler base = _ -> withLateTrailer();

        Response r = Filter.gzip().apply(base).handle(request(Headers.of("Accept-Encoding", "gzip")));

        assertEquals("gzip", r.headers().firstOrNull("Content-Encoding"));
        assertEquals("after-body", trailerAfterBody(r));
    }

    private static Request request(Headers headers) {
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
                return headers;
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
}
