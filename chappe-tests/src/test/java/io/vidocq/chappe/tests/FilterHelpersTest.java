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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
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

import org.junit.jupiter.api.Test;

class FilterHelpersTest {

    private static final Handler OK = _ -> Response.ok("body");

    @Test
    void addHeaderInjectsValueOnEveryResponse() throws Exception {
        Handler h = Filter.addHeader("X-Foo", "bar").apply(OK);
        Response r = h.handle(req());
        assertEquals("bar", r.headers().firstOrNull("X-Foo"));
    }

    @Test
    void addHeaderPreservesExistingHeaders() throws Exception {
        Handler base = _ -> Response.builder()
                .header("Content-Type", "text/plain")
                .body("body")
                .build();
        Handler h = Filter.addHeader("X-Foo", "bar").apply(base);
        Response r = h.handle(req());
        assertEquals("text/plain", r.headers().firstOrNull("Content-Type"));
        assertEquals("bar", r.headers().firstOrNull("X-Foo"));
    }

    @Test
    void addHeaderPreservesStatusAndBody() throws Exception {
        Handler base = _ -> Response.of(io.vidocq.chappe.api.StatusCode.CREATED, Body.of("payload"));
        Handler h = Filter.addHeader("X-Foo", "bar").apply(base);
        Response r = h.handle(req());
        assertEquals(io.vidocq.chappe.api.StatusCode.CREATED, r.status());
        try (var in = r.body().asInputStream()) {
            assertEquals("payload", new String(in.readAllBytes()));
        }
    }

    @Test
    void addHeaderIfTrueAddsHeader() throws Exception {
        Handler h = Filter.addHeaderIf(() -> true, "X-Robots-Tag", "noindex").apply(OK);
        assertEquals("noindex", h.handle(req()).headers().firstOrNull("X-Robots-Tag"));
    }

    @Test
    void addHeaderIfFalseSkipsHeader() throws Exception {
        Handler h = Filter.addHeaderIf(() -> false, "X-Robots-Tag", "noindex").apply(OK);
        assertFalse(h.handle(req()).headers().contains("X-Robots-Tag"));
    }

    @Test
    void addHeaderIfPredicateEvaluatedPerRequest() throws Exception {
        AtomicBoolean toggle = new AtomicBoolean(false);
        Handler h = Filter.addHeaderIf(toggle::get, "X-Toggle", "on").apply(OK);
        assertFalse(h.handle(req()).headers().contains("X-Toggle"));
        toggle.set(true);
        assertEquals("on", h.handle(req()).headers().firstOrNull("X-Toggle"));
        toggle.set(false);
        assertFalse(h.handle(req()).headers().contains("X-Toggle"));
    }

    @Test
    void addHeaderIfEnvMatchesAdds() throws Exception {
        String present = System.getenv().keySet().stream().findFirst().orElse(null);
        if (present == null) return;
        String value = System.getenv(present);
        Handler h = Filter.addHeaderIfEnv(present, value, "X-Env", "yes").apply(OK);
        assertEquals("yes", h.handle(req()).headers().firstOrNull("X-Env"));
    }

    @Test
    void addHeaderIfEnvMismatchSkips() throws Exception {
        Handler h = Filter.addHeaderIfEnv(
                        "CHAPPE_TEST_VAR_THAT_DOES_NOT_EXIST_42", "yes",
                        "X-Env", "no")
                .apply(OK);
        assertFalse(h.handle(req()).headers().contains("X-Env"));
    }

    @Test
    void filtersComposeWithAndThen() throws Exception {
        Filter chain = Filter.addHeader("X-A", "a").andThen(Filter.addHeader("X-B", "b"));
        Handler h = chain.apply(OK);
        Response r = h.handle(req());
        assertEquals("a", r.headers().firstOrNull("X-A"));
        assertEquals("b", r.headers().firstOrNull("X-B"));
    }

    private static Request req() {
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
                return Headers.empty();
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
