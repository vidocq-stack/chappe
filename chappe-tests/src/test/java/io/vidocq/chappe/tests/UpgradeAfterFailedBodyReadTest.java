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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.vidocq.chappe.api.ConnectionUpgrade;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketUpgrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An upgrade answered to a request whose body read failed: the position in the byte stream is
 * unknown, so the connection closes without draining the body, writing the upgrade head or handing
 * the connection over (the same guard as the plain path, CHAPPE-013).
 */
@Timeout(30)
class UpgradeAfterFailedBodyReadTest {

    /**
     * A chunked body whose first size byte is invalid: the first read throws after consuming 'Z';
     * a drain would then resume on "0\r\n\r\n" and end the body cleanly.
     */
    private static final String REQUEST = """
            POST /u HTTP/1.1\r
            Host: h\r
            Upgrade: echo\r
            Connection: Upgrade\r
            Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r
            Sec-WebSocket-Version: 13\r
            Transfer-Encoding: chunked\r
            \r
            Z0\r
            \r
            """;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void aConnectionUpgradeAfterAFailedBodyReadClosesTheConnection() throws Exception {
        var upgraded = new CountDownLatch(1);
        String response = exchange(
                () -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, Headers.of("Upgrade", "echo"), conn -> {
                    upgraded.countDown();
                    conn.close();
                }));
        assertFalse(response.contains("101"), response);
        assertEquals(1, upgraded.getCount(), "the connection was handed over");
    }

    @Test
    void aWebSocketUpgradeAfterAFailedBodyReadClosesTheConnection() throws Exception {
        var opened = new CountDownLatch(1);
        String response = exchange(() -> new WebSocketUpgrade(new WebSocketHandler() {
            @Override
            public void onOpen(WebSocket ws, io.vidocq.chappe.api.Request handshake) throws IOException {
                opened.countDown();
                ws.close();
            }
        }));
        assertFalse(response.contains("101"), response);
        assertEquals(1, opened.getCount(), "the WebSocket was opened");
    }

    /** Sends {@link #REQUEST}; the handler reads the body (which fails) then answers {@code upgrade}. */
    private String exchange(Supplier<Response> upgrade) throws Exception {
        var bodyFailed = new CountDownLatch(1);
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    try {
                        req.body().asInputStream().readAllBytes();
                    } catch (IOException _) {
                        // expected: the invalid chunk size fails the read
                        bodyFailed.countDown();
                    }
                    return upgrade.get();
                })
                .build();
        server.start();
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = readToEnd(socket.getInputStream());
            assertTrue(bodyFailed.await(5, TimeUnit.SECONDS), "the body read did not fail");
            return response;
        }
    }

    private static String readToEnd(InputStream in) throws IOException {
        var all = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try {
            int n;
            while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
        } catch (SocketException _) {
            // a reset ends the connection too
        }
        return all.toString(StandardCharsets.US_ASCII);
    }
}
