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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Active WebSocket connection — RFC 6455.
 * <p>
 * An instance is provided to the {@link WebSocketHandler} after a successful handshake.
 * The {@code send*} methods are thread-safe: outgoing frames are serialised
 * internally to prevent interleaving (forbidden by RFC §5.4).
 * <p>
 * Calls block until the complete frame has been written to the socket —
 * natural on virtual threads. A closed connection raises {@link IOException}.
 * <p>
 * Nothing of the application follows this side's Close frame: a send, a ping or a pong that was waiting for an
 * earlier write when the Close frame went out fails with {@link IOException} without writing. The automatic
 * Pong still answers a Ping of the peer until the peer's Close arrives (RFC 6455 §5.5.2).
 */
public interface WebSocket {

    /** Sends a text message (TEXT frame, opcode 0x1) as a single unfragmented frame. */
    void sendText(String message) throws IOException;

    /** Sends a binary message (BINARY frame, opcode 0x2) as a single unfragmented frame. */
    void sendBinary(ByteBuffer payload) throws IOException;

    /** Sends a PING (opcode 0x9) — payload ≤ 125 bytes; fails once this side's Close frame went out. */
    void sendPing(ByteBuffer payload) throws IOException;

    /** Sends a PONG (opcode 0xA) — payload ≤ 125 bytes; fails once this side's Close frame went out. */
    void sendPong(ByteBuffer payload) throws IOException;

    /**
     * Initiates the close handshake with a status code and a text reason.
     * <p>
     * The reason is UTF-8 encoded and limited to 123 bytes ({@code 125 - 2} for the code).
     * After this call, the connection waits for the peer's Close frame and then closes.
     * <p>
     * Once the connection was {@linkplain #abort() aborted}, this method does nothing and does not throw.
     * <p>
     * It sends the Close frame as {@link #sendClose(int, String)} does, without telling whether this call sent it.
     *
     * @throws IOException when the Close frame cannot be written to a connection that was not aborted; an
     *     {@link java.io.InterruptedIOException} when the calling thread is interrupted before it takes the
     *     connection's write lock, as with {@link #sendClose(int, String)}: nothing is written
     */
    void close(int code, String reason) throws IOException;

    /** Shortcut: closes with {@link CloseCodes#NORMAL_CLOSURE} and no reason. */
    default void close() throws IOException {
        close(CloseCodes.NORMAL_CLOSURE, "");
    }

    /**
     * Sends this side's Close frame as {@link #close(int, String)} does, and tells whether this call sent it.
     * <p>
     * A connection sends one Close frame. This call sends it unless a Close frame went out before it (an earlier
     * close of this side, or the echo of the peer's Close) or the peer's Close arrived before it, which the
     * connection then answers with that echo. Once the connection was {@linkplain #abort() aborted}, this method
     * does nothing and does not throw, also when the abort cut this call's write. The result tells which side
     * closed first: {@code true}, this side; {@code false}, a Close that came before, or the abort.
     * <p>
     * A call that finds a Close frame already out answers without writing, once it gets past the write in
     * progress, if any: after the Close frame, only the automatic Pong of a Ping the peer sent before its Close can
     * be one. The server's {@code writeTimeout} bounds that write, and any other, when it is positive.
     *
     * @param code   the close code
     * @param reason the close reason, at most 123 bytes in UTF-8
     * @return {@code true} when this call sent the Close frame; {@code false} when a Close frame went out before
     *     it, the peer's Close arrived before it, or the connection was aborted, before or during this call
     * @throws IOException when the Close frame cannot be written to a connection that was not aborted; an
     *     {@link java.io.InterruptedIOException} when the calling thread is interrupted before it takes the
     *     connection's write lock, while it waits for it or already on entry: this call then wrote nothing, so
     *     the close did not start, and the thread's interrupt status is set again
     */
    boolean sendClose(int code, String reason) throws IOException;

    /**
     * Closes the TCP connection at once, without a Close frame and without waiting for the lock that
     * serialises the writes; over TLS, without a {@code close_notify} either.
     * <p>
     * A send blocked in another thread on a peer that does not read fails with an {@link IOException}, and so
     * does a send queued behind it or made afterwards: its data is lost, and the caller must know. A
     * {@link #close(int, String)}, queued, cut in its own write or made afterwards, does nothing and returns
     * without throwing: the connection is closed, which is what it asked for.
     * <p>
     * The handler's {@link WebSocketHandler#onClose onClose} then reports {@link CloseCodes#ABNORMAL_CLOSURE}
     * (1006), unless the connection had already ended (the peer's Close frame received). The I/O failure the
     * abort causes on the connection's own read is not reported to {@link WebSocketHandler#onError onError}.
     * One exception, the rule for a failed {@link WebSocketHandler#onOpen onOpen}, unchanged: an {@code onOpen}
     * that throws, including the {@code IOException} of a send the abort cut, gets {@code onError} and no
     * {@code onClose}.
     * <p>
     * Idempotent, never throws, safe from any thread.
     * <p>
     * For a connection that must end now: a peer that does not read, a shutdown past its grace. A session
     * that may end normally ends with {@link #close(int, String)}; {@code abort()} is for when that close
     * cannot complete in time.
     */
    void abort();

    /** {@code true} as long as the connection has neither received nor sent a Close frame. */
    boolean isOpen();

    /** Remote (client) endpoint address. */
    InetSocketAddress remoteAddress();

    /** {@code true} if the underlying connection is TLS-secured (wss://). */
    boolean isSecure();

    /**
     * Negotiated sub-protocol (header {@code Sec-WebSocket-Protocol}), or {@code null}
     * if none was requested or accepted.
     */
    String subprotocol();

    /** Mutable attribute attached to the connection (session state). */
    Object attribute(String key);

    /** Sets an attribute. Returns {@code this} for chaining. */
    WebSocket attribute(String key, Object value);
}
