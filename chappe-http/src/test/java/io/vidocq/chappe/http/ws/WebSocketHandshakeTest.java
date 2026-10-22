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
package io.vidocq.chappe.http.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.BuildInfo;

import org.junit.jupiter.api.Test;

/** The exact bytes of the {@code 101 Switching Protocols} response, for the sample key of RFC 6455 §1.3. */
class WebSocketHandshakeTest {

    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    @Test
    void theResponseNamesChappeWithoutItsVersionByDefault() throws IOException {
        assertEquals("""
                HTTP/1.1 101 Switching Protocols\r
                Upgrade: websocket\r
                Connection: Upgrade\r
                Server: Chappe\r
                Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r
                \r
                """, written(null, false));
    }

    @Test
    void theResponseCarriesTheBuildAndTheSubprotocolWhenAskedFor() throws IOException {
        assertEquals("""
                HTTP/1.1 101 Switching Protocols\r
                Upgrade: websocket\r
                Connection: Upgrade\r
                Server: %s\r
                X-Chappe-Build: %s\r
                Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r
                Sec-WebSocket-Protocol: chat\r
                \r
                """.formatted(BuildInfo.serverHeader(), BuildInfo.serverHeader()), written("chat", true));
    }

    private static String written(String subprotocol, boolean exposeBuildInfo) throws IOException {
        var wire = new ByteArrayOutputStream();
        WebSocketHandshake.writeResponse(Channels.newChannel(wire), KEY, subprotocol, exposeBuildInfo);
        return wire.toString(StandardCharsets.US_ASCII);
    }
}
