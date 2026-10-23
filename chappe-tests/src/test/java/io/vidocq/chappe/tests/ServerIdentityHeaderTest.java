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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.BuildInfo;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The identity a response gives. By default {@code Server: Chappe}, without a version, and no
 * {@code X-Chappe-Build}: a public server does not tell which version to attack (Vidocq/vidocq#273, the first
 * ZAP scan). With {@code exposeBuildInfo(true)}, the version and the build in both headers, for diagnosis
 * behind a proxy. Every path that writes a response head is covered: the fast path of a small text 200, a
 * response the general path writes, an error the server answers itself, and the 101 of a WebSocket upgrade.
 */
class ServerIdentityHeaderTest {

    private static final String DISCREET = "\r\nServer: Chappe\r\n";
    private static final String EXPOSED = "\r\nServer: " + BuildInfo.serverHeader() + "\r\n";
    private static final String BUILD = "\r\nX-Chappe-Build: " + BuildInfo.serverHeader() + "\r\n";

    private static final String GET = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String BROKEN = "NOT A REQUEST LINE\r\n\r\n";
    private static final String UPGRADE = """
            GET /ws HTTP/1.1\r
            Host: localhost\r
            Upgrade: websocket\r
            Connection: Upgrade\r
            Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r
            Sec-WebSocket-Version: 13\r
            \r
            """;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void aSmallTextOkNamesChappeWithoutItsVersion() throws IOException {
        assertDiscreet(headOf(start(false, _ -> Response.ok("pong")), GET));
    }

    @Test
    void aResponseOfTheGeneralPathNamesChappeWithoutItsVersion() throws IOException {
        assertDiscreet(headOf(start(false, _ -> Response.of(StatusCode.CREATED)), GET));
    }

    @Test
    void anErrorTheServerAnswersNamesChappeWithoutItsVersion() throws IOException {
        assertDiscreet(headOf(start(false, _ -> Response.ok("unused")), BROKEN));
    }

    @Test
    void aWebSocketUpgradeNamesChappeWithoutItsVersion() throws IOException {
        assertDiscreet(headOf(start(false, _ -> webSocket()), UPGRADE));
    }

    @Test
    void theBuildIsExposedOnEveryPathWhenAskedFor() throws IOException {
        assertExposed(headOf(start(true, _ -> Response.ok("pong")), GET));
        server.stop();
        assertExposed(headOf(start(true, _ -> Response.of(StatusCode.CREATED)), GET));
        server.stop();
        assertExposed(headOf(start(true, _ -> Response.ok("unused")), BROKEN));
        server.stop();
        var upgrade = headOf(start(true, _ -> webSocket()), UPGRADE);
        assertTrue(upgrade.contains(EXPOSED), "the 101 carries the build when asked for: " + upgrade);
    }

    @Test
    void aServerHeaderOfTheApplicationStillWins() throws IOException {
        var head = headOf(
                start(false, _ -> Response.builder()
                        .status(StatusCode.OK)
                        .header("Server", "Bistro")
                        .body("menu")
                        .build()),
                GET);
        assertTrue(head.contains("\r\nServer: Bistro\r\n"), head);
        assertFalse(head.contains("Server: Chappe"), "one Server header only: " + head);
    }

    private static void assertDiscreet(String head) {
        assertTrue(head.contains(DISCREET), "Server names Chappe, without a version: " + head);
        assertFalse(head.contains("X-Chappe-Build"), "no X-Chappe-Build by default: " + head);
    }

    private static void assertExposed(String head) {
        assertTrue(head.contains(EXPOSED), "Server carries the build when asked for: " + head);
        assertTrue(head.contains(BUILD), "X-Chappe-Build carries the build when asked for: " + head);
    }

    private int start(boolean exposeBuildInfo, io.vidocq.chappe.api.Handler handler) {
        server = Server.builder()
                .port(0)
                .exposeBuildInfo(exposeBuildInfo)
                .handler(handler)
                .build();
        server.start();
        return server.port();
    }

    private static Response webSocket() {
        return new WebSocketUpgrade(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, Request handshake) {
                // nothing to do: the test reads the head of the 101 and closes the connection
            }
        });
    }

    /** Sends {@code request} and returns the response head, up to its blank line. */
    private static String headOf(int port, String request) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readHead(socket.getInputStream());
        }
    }

    private static String readHead(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) break;
        }
        return head.toString(StandardCharsets.US_ASCII);
    }
}
