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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.chappe.api.WebSocketOrigins;
import io.vidocq.chappe.tests.log.CapturingLoggerFinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The handshake checks of the native WebSocket routes: the origin policy against cross-site
 * WebSocket hijacking, and the {@code Sec-WebSocket-Key} of RFC 6455 §4.2.1 (CHAPPE-057).
 */
class WebSocketOriginTest {

    private static final WebSocketHandler HANDLER = new WebSocketHandler() {};

    private static final String ROUTER_LOGGER = "io.vidocq.chappe.api.DefaultRouterBuilder";

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void anotherOriginGets403AndAWarning() throws Exception {
        int port = start(Router.builder().webSocket("/ws", HANDLER).build());

        assertEquals(403, status(port, "/ws", Map.of("Origin", "http://evil.example")));
        boolean warned = CapturingLoggerFinder.RECORDS.stream()
                .anyMatch(r -> ROUTER_LOGGER.equals(r.logger())
                        && r.level() == System.Logger.Level.WARNING
                        && r.message().contains("http://evil.example"));
        assertTrue(warned, "a refused origin must be logged at WARNING; records=" + CapturingLoggerFinder.RECORDS);
    }

    @Test
    void theSameOriginGets101() throws Exception {
        int port = start(Router.builder().webSocket("/ws", HANDLER).build());

        assertEquals(101, status(port, "/ws", Map.of("Origin", "http://127.0.0.1:" + port)));
    }

    @Test
    void allowsTheOriginOfATlsTerminatingProxy() throws Exception {
        int port = start(Router.builder().webSocket("/ws", HANDLER).build());

        var headers = new LinkedHashMap<String, String>();
        headers.put("Host", "app.example.com");
        headers.put("Origin", "https://app.example.com");
        assertEquals(101, status(port, "/ws", headers));
    }

    @Test
    void aListedOriginGets101EvenWhenSetAfterTheRoute() throws Exception {
        int port = start(Router.builder()
                .webSocket("/ws", HANDLER)
                .webSocketOrigins(WebSocketOrigins.parse("http://localhost:5173"))
                .build());

        assertEquals(101, status(port, "/ws", Map.of("Origin", "http://localhost:5173")));
    }

    @Test
    void aRouteOfAGroupFollowsThePolicyOfTheBuilderThatBuilds() throws Exception {
        int port = start(Router.builder()
                .group("/chat", chat -> chat.webSocket("/ws", HANDLER))
                .webSocketOrigins(WebSocketOrigins.parse("http://localhost:5173"))
                .build());

        assertEquals(101, status(port, "/chat/ws", Map.of("Origin", "http://localhost:5173")));
        assertEquals(403, status(port, "/chat/ws", Map.of("Origin", "http://evil.example")));
    }

    @Test
    void aGroupBuilderRefusesWebSocketOrigins() {
        var builder = Router.builder();
        var origins = WebSocketOrigins.any();
        Consumer<Router.Builder> group = chat -> chat.webSocketOrigins(origins);

        var e = assertThrows(IllegalStateException.class, () -> builder.group("/chat", group));

        assertTrue(e.getMessage().contains("root Router.Builder"), e.getMessage());
    }

    @Test
    void aPolicySetAfterBuildDoesNotChangeTheBuiltRouter() throws Exception {
        var builder = Router.builder().webSocket("/ws", HANDLER);
        int port = start(builder.build());
        builder.webSocketOrigins(WebSocketOrigins.any());

        assertEquals(403, status(port, "/ws", Map.of("Origin", "http://evil.example")));
    }

    @Test
    void acceptsAConnectionHeaderListingSeveralTokens() throws Exception {
        int port = start(Router.builder().webSocket("/ws", HANDLER).build());

        var headers = new LinkedHashMap<String, String>();
        headers.put("Connection", "keep-alive, Upgrade");
        headers.put("Upgrade", "WebSocket");
        assertEquals(101, status(port, "/ws", headers));
    }

    @Test
    void aKeyThatIsNot16BytesGets400() throws Exception {
        int port = start(Router.builder().webSocket("/ws", HANDLER).build());

        assertEquals(400, status(port, "/ws", Map.of("Sec-WebSocket-Key", "abc")));
        String fifteenBytes = Base64.getEncoder().encodeToString(new byte[15]);
        assertEquals(400, status(port, "/ws", Map.of("Sec-WebSocket-Key", fifteenBytes)));
    }

    private int start(Router router) {
        server = Server.builder().port(0).handler(router).build();
        server.start();
        return server.port();
    }

    /** The status of a handshake on {@code path} with {@code headers} over the defaults of {@link RawWebSocket}. */
    private static int status(int port, String path, Map<String, String> headers) throws IOException {
        try (var socket = RawWebSocket.open(port, path, headers, 0)) {
            return socket.status();
        }
    }
}
