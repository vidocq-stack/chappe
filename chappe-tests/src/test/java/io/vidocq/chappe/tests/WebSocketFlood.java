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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.vidocq.chappe.api.WebSocket;

/**
 * A server-side flood of a WebSocket peer: 64 KiB frames sent until a send fails. Against a peer that
 * stops reading, the socket buffers fill and a send blocks; a blocked write gives no signal of its own,
 * so {@link #stalled} tells how long the send in flight has been running.
 */
final class WebSocketFlood {

    /** One frame of the flood: a few of them fill a 4 KiB receive buffer and the server's send buffer. */
    private static final int FRAME_SIZE = 64 * 1024;

    /** The value of {@link #inFlightSince} while no send is in flight. */
    private static final long NOT_SENDING = Long.MIN_VALUE;

    /** When the send in flight started, {@link #NOT_SENDING} between two sends. */
    private final AtomicLong inFlightSince = new AtomicLong(NOT_SENDING);

    /** Sends frames until a send fails, and returns that failure. */
    IOException untilCut(WebSocket ws) {
        var frame = new byte[FRAME_SIZE];
        IOException failure = null;
        while (failure == null) {
            inFlightSince.set(System.nanoTime());
            try {
                ws.sendBinary(ByteBuffer.wrap(frame));
            } catch (IOException e) {
                failure = e;
            } finally {
                inFlightSince.set(NOT_SENDING);
            }
        }
        return failure;
    }

    /** Whether the send in flight has been running for longer than {@code stall}. */
    boolean stalled(Duration stall) {
        long since = inFlightSince.get();
        return since != NOT_SENDING && System.nanoTime() - since > stall.toNanos();
    }
}
