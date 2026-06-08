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

import java.nio.ByteBuffer;

/**
 * Handler for a WebSocket connection — RFC 6455.
 * <p>
 * All methods have an empty default implementation:
 * an application only needs to override the events it handles.
 * <p>
 * All callbacks for the same connection are invoked sequentially
 * on the connection's virtual thread: no synchronization is needed
 * to access state attached via {@link WebSocket#attribute(String, Object)}.
 *
 * <pre>{@code
 * var router = Router.builder()
 *     .webSocket("/echo", new WebSocketHandler() {
 *         @Override public void onText(WebSocket ws, String message) throws Exception {
 *             ws.sendText(message);
 *         }
 *     })
 *     .build();
 * }</pre>
 */
public interface WebSocketHandler {

    /** Called once after a successful handshake, before the first frame. */
    default void onOpen(WebSocket ws, Request handshake) throws Exception {}

    /** Complete text message (TEXT + CONTINUATION frames reassembled, UTF-8 validated). */
    default void onText(WebSocket ws, String message) throws Exception {}

    /**
     * Complete binary message (BINARY + CONTINUATION frames reassembled).
     * <p>
     * The {@link ByteBuffer} is in read mode (position = 0, limit = size);
     * it must not be retained beyond the callback — its content may be recycled.
     */
    default void onBinary(WebSocket ws, ByteBuffer data) throws Exception {}

    /**
     * Received PING frame. By default, the server automatically replies with a PONG
     * carrying the same payload <em>before</em> this callback is invoked (RFC 6455 §5.5.2).
     * Override only for observability.
     */
    default void onPing(WebSocket ws, ByteBuffer payload) throws Exception {}

    /** Received PONG frame (response to a previous PING). */
    default void onPong(WebSocket ws, ByteBuffer payload) throws Exception {}

    /**
     * Received or inferred Close frame (close handshake completed / connection lost).
     * <p>
     * {@code code} is {@link CloseCodes#NO_STATUS_RCVD} if the peer did not send a code,
     * and {@link CloseCodes#ABNORMAL_CLOSURE} if the TCP connection was cut without a Close.
     */
    default void onClose(WebSocket ws, int code, String reason) throws Exception {}

    /**
     * Error while processing the connection (read/write, application callback).
     * Invoked just before {@link #onClose} with an appropriate code.
     */
    default void onError(WebSocket ws, Throwable error) {}
}
