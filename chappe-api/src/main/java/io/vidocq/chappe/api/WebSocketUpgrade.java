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

import java.util.Objects;

/**
 * Marker response telling the HTTP transport that a connection must be upgraded
 * to WebSocket (RFC 6455 §1.3) after sending the {@code 101 Switching Protocols} response.
 * <p>
 * Built by {@link Router.Builder#webSocket(String, WebSocketHandler)} and recognized
 * by {@code chappe-http} via {@code instanceof}. A normal application does not need
 * to instantiate it directly.
 */
public final class WebSocketUpgrade implements Response {

    private final WebSocketHandler handler;
    private final String subprotocol;
    private final Request handshakeRequest;

    public WebSocketUpgrade(WebSocketHandler handler, String subprotocol, Request handshakeRequest) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.subprotocol = subprotocol;
        this.handshakeRequest = handshakeRequest;
    }

    public WebSocketUpgrade(WebSocketHandler handler, String subprotocol) {
        this(handler, subprotocol, null);
    }

    public WebSocketUpgrade(WebSocketHandler handler) {
        this(handler, null, null);
    }

    /** Handler to invoke once the handshake is confirmed. */
    public WebSocketHandler handler() {
        return handler;
    }

    /**
     * The matched handshake {@link Request} — including route {@code pathParams()} — to hand to
     * {@link WebSocketHandler#onOpen}. Null when the upgrade was built without routing context
     * (then the transport falls back to the raw request, which carries no path parameters).
     */
    public Request handshakeRequest() {
        return handshakeRequest;
    }

    /** Accepted subprotocol (sent in {@code Sec-WebSocket-Protocol}), or {@code null}. */
    public String subprotocol() {
        return subprotocol;
    }

    @Override
    public StatusCode status() {
        return StatusCode.of(101);
    }

    @Override
    public Headers headers() {
        return Headers.empty();
    }

    @Override
    public Body body() {
        return Body.empty();
    }
}
