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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The extra headers a {@link WebSocketUpgrade} puts on the {@code 101}. */
class WebSocketUpgradeTest {

    private static final WebSocketHandler HANDLER = new WebSocketHandler() {};

    @Test
    void extraHeadersAreKept() {
        var headers = Headers.builder()
                .add("Set-Cookie", "JSESSIONID=x")
                .add("X-Trace", "1")
                .build();

        var upgrade = new WebSocketUpgrade(HANDLER, null, null, headers);

        assertEquals(List.of("Set-Cookie: JSESSIONID=x", "X-Trace: 1"), lines(upgrade.headers()));
    }

    @Test
    void headersChappeWritesAreDropped() {
        var headers = Headers.builder()
                .add("Upgrade", "h2c")
                .add("Connection", "close")
                .add("Server", "x")
                .add("X-Chappe-Build", "x")
                .add("Sec-WebSocket-Accept", "x")
                .add("Sec-WebSocket-Protocol", "x")
                .add("Sec-WebSocket-Extensions", "x")
                .add("Content-Length", "9")
                .add("Transfer-Encoding", "chunked")
                .add("X-Kept", "yes")
                .build();

        var upgrade = new WebSocketUpgrade(HANDLER, null, null, headers);

        assertEquals(List.of("X-Kept: yes"), lines(upgrade.headers()));
    }

    @Test
    void anUpgradeBuiltWithoutHeadersHasNone() {
        assertEquals(List.of(), lines(new WebSocketUpgrade(HANDLER, "chat").headers()));
    }

    @Test
    void aValueWithCrLfIsRefused() {
        var headers = Headers.of("X-Evil", "a\r\nInjected: 1");

        assertThrows(IllegalArgumentException.class, () -> new WebSocketUpgrade(HANDLER, null, null, headers));
    }

    @Test
    void aBadNameIsEscapedInTheMessage() {
        var headers = Headers.of("X-Evil\r\nInjected", "v");

        var e = assertThrows(IllegalArgumentException.class, () -> new WebSocketUpgrade(HANDLER, null, null, headers));

        assertFalse(e.getMessage().contains("\r") || e.getMessage().contains("\n"), "raw CR or LF in the message");
        assertTrue(e.getMessage().contains("X-Evil\\u000D\\u000AInjected"), e.getMessage());
    }

    private static List<String> lines(Headers headers) {
        var lines = new ArrayList<String>();
        for (var entry : headers) {
            lines.add(entry.name() + ": " + entry.value());
        }
        return lines;
    }
}
