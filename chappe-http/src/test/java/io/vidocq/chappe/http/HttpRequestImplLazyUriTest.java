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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@code java.net.URI} parsing is the costly part of a request's identity, and most consumers
 * only need the raw path and query: the URI is built on the first {@code uri()} call only.
 */
class HttpRequestImplLazyUriTest {

    /** A target {@code URI.create} rejects (a space, a pipe, braces): only {@code uri()} may fail on it. */
    private static final String UNPARSEABLE = "/a b|{c}?q=x y";

    @Test
    void pathAndQueryNeverBuildTheUri() {
        var request = request(UNPARSEABLE);
        assertEquals("/a b|{c}", request.path());
        assertEquals("q=x y", request.query());
        assertEquals("x y", request.queryParams().get("q"));
        assertThrows(IllegalArgumentException.class, request::uri, "the URI is built on demand");
    }

    @Test
    void theUriIsStillBuiltThroughTheHost() {
        var request = request("/items?id=3");
        assertEquals("http://example.org:8080/items?id=3", request.uri().toString());
        assertEquals("/items", request.path());
        assertEquals("id=3", request.query());
    }

    @Test
    void anOriginFormWithoutHostKeepsItsRawTarget() {
        var request = new HttpRequestImpl();
        request.setRawUri("/plain");
        assertEquals("/plain", request.uri().toString());
        request.reset();
        assertEquals("/", request.uri().toString());
        assertEquals("/", request.path());
        assertNull(request.query());
    }

    private static HttpRequestImpl request(String target) {
        var request = new HttpRequestImpl();
        request.setRawUri(target);
        request.addHeader("Host", "example.org:8080");
        return request;
    }
}
