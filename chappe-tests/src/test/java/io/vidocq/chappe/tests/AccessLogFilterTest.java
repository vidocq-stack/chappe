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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Filter;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link Filter#accessLog(java.util.function.Consumer)}: one line per exchange, failures included (CHAPPE-049). */
class AccessLogFilterTest {

    @Test
    void aSuccessfulExchangeLogsItsStatusAndSize() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add).apply(_ -> Response.ok("body"));

        h.handle(req());

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("\"GET / HTTP/1.1\" 200 4 "), lines.getFirst());
    }

    static Stream<Throwable> failures() {
        return Stream.of(
                new IOException("checked: backend down"),
                new IllegalStateException("unchecked: broken"),
                new AssertionError("error: fatal"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFailureIsLoggedAsA500AndRethrownUnchanged(Throwable failure) {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add).apply(_ -> fail(failure));
        Request request = req();

        Throwable thrown = assertThrows(failure.getClass(), () -> h.handle(request));

        assertSame(failure, thrown);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("\"GET / HTTP/1.1\" 500 - "), lines.getFirst());
    }

    /** Throws the failure as it is, checked exception or error. */
    private static Response fail(Throwable failure) throws Exception {
        if (failure instanceof Exception e) {
            throw e;
        }
        throw (Error) failure;
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
