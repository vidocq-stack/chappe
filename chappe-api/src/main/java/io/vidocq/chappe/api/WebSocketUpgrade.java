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
import java.util.Set;

import io.vidocq.chappe.api.internal.HeaderFields;

/**
 * Marker response telling the HTTP transport that a connection must be upgraded
 * to WebSocket (RFC 6455 §1.3) after sending the {@code 101 Switching Protocols} response.
 * <p>
 * Built by {@link Router.Builder#webSocket(String, WebSocketHandler)} and recognized
 * by {@code chappe-http} via {@code instanceof}. A normal application does not need
 * to instantiate it directly.
 * <p>
 * The optional {@link #headers() headers} are written on the {@code 101} after the ones Chappe
 * sets itself (a session cookie, a header an application sets during the handshake). A header
 * Chappe writes itself, or that frames the message, is dropped from them.
 */
public final class WebSocketUpgrade implements Response {

    private final WebSocketHandler handler;
    private final String subprotocol;
    private final Request handshakeRequest;
    private final Headers headers;

    /** The headers Chappe writes itself on the {@code 101}, or that cannot frame a message that has no body. */
    private static final Set<String> DROPPED_HEADERS = Set.of(
            "upgrade",
            "connection",
            "server",
            "x-chappe-build",
            "sec-websocket-accept",
            "sec-websocket-protocol",
            "sec-websocket-extensions",
            "content-length",
            "transfer-encoding");

    /**
     * @param headers extra headers for the {@code 101}; validated, and the ones Chappe writes itself are dropped
     * @throws IllegalArgumentException if a header name or value is invalid, or the subprotocol is not a token
     */
    public WebSocketUpgrade(WebSocketHandler handler, String subprotocol, Request handshakeRequest, Headers headers) {
        this.handler = Objects.requireNonNull(handler, "handler");
        // Written as the Sec-WebSocket-Protocol header: an RFC 6455 §4.3 token (CHAPPE-008).
        if (subprotocol != null && !subprotocol.isEmpty() && !HeaderFields.isToken(subprotocol)) {
            throw new IllegalArgumentException(
                    "Invalid WebSocket subprotocol: " + HeaderFields.printable(subprotocol) + " (must be a token)");
        }
        this.subprotocol = subprotocol;
        this.handshakeRequest = handshakeRequest;
        this.headers =
                HeaderFields.validatedUpgradeHeaders(Objects.requireNonNull(headers, "headers"), DROPPED_HEADERS);
    }

    public WebSocketUpgrade(WebSocketHandler handler, String subprotocol, Request handshakeRequest) {
        this(handler, subprotocol, handshakeRequest, Headers.empty());
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
        return headers;
    }

    @Override
    public Body body() {
        return Body.empty();
    }
}
