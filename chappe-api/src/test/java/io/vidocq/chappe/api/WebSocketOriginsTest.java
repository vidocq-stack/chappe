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
package io.vidocq.chappe.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The origin policy of the native WebSocket routes (cross-site WebSocket hijacking). */
class WebSocketOriginsTest {

    private static final String LISTED = "https://admin.example.com, http://localhost:5173";

    @Test
    void noOriginIsAllowed() {
        assertTrue(WebSocketOrigins.sameOrigin().allows(null, "a:8080"));
    }

    @Test
    void theSameHostAndPortIsAllowed() {
        assertTrue(WebSocketOrigins.sameOrigin().allows("http://a:8080", "a:8080"));
    }

    @Test
    void allowsTheOriginOfATlsTerminatingProxy() {
        assertTrue(WebSocketOrigins.sameOrigin().allows("https://app.example.com", "app.example.com"));
    }

    @Test
    void anotherPortIsRefused() {
        assertFalse(WebSocketOrigins.sameOrigin().allows("http://localhost:5173", "localhost:8080"));
    }

    @Test
    void theNullOriginIsRefused() {
        assertFalse(WebSocketOrigins.sameOrigin().allows("null", "a"));
    }

    @Test
    void aListedOriginIsAllowed() {
        var origins = WebSocketOrigins.parse(LISTED);

        assertTrue(origins.allows("https://admin.example.com", "a:8080"));
        assertTrue(origins.allows("http://localhost:5173", "a:8080"));
    }

    @Test
    void aListedOriginMustMatchItsScheme() {
        assertFalse(WebSocketOrigins.parse(LISTED).allows("http://admin.example.com", "a:8080"));
    }

    @Test
    void starAllowsAny() {
        var origins = WebSocketOrigins.parse("http://localhost:5173, *");

        assertTrue(origins.allowsAny());
        assertSame(WebSocketOrigins.any(), origins);
        assertTrue(origins.allows("http://evil.example", "a:8080"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x", "https://x/p", "x.example", "https://u@x"})
    void aMalformedEntryNamesIt(String entry) {
        var list = "http://localhost:5173, " + entry;

        var e = assertThrows(IllegalArgumentException.class, () -> WebSocketOrigins.parse(list));

        assertTrue(e.getMessage().contains(entry), e.getMessage());
    }
}
