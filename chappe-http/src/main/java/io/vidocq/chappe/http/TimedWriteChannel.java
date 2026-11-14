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
package io.vidocq.chappe.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;

/**
 * The channel a WebSocket session writes to, its {@code 101} response included: each blocking write
 * is bounded by {@code writeTimeout} (CHAPPE-055). At the deadline the connection's {@link
 * ConnectionWatchdog} closes the connection, which wakes the write; the write then throws an {@link
 * IOException}, and the session ends as a lost connection.
 *
 * <p>The bound is per slice of progress, not per frame nor per session: one write hands the channel
 * at most {@value #SLICE} bytes, and the caller loops until its buffer is empty. Over TLS this
 * matters: {@code SslHandler} writes the whole buffer it is given before it returns, so a bound on a
 * whole frame would cut a large frame to a peer that reads slowly but never stops. A session that
 * sends nothing for a long time is not cut either.
 *
 * <p>Without it, a write to a peer that stopped reading blocks without limit while it holds the
 * session's write lock, so {@code close()} and the automatic PONG block behind it. The session's
 * writes are serialized by that lock, which keeps the watchdog's one write deadline per connection.
 */
final class TimedWriteChannel implements WritableByteChannel {

    /** The most one bounded write hands the channel: the HTTP/2 flush unit, one TLS record of plaintext. */
    static final int SLICE = 16 * 1024;

    private final WritableByteChannel channel;
    private final ConnectionWatchdog watchdog;
    private final long timeoutNanos;

    private TimedWriteChannel(WritableByteChannel channel, ConnectionWatchdog watchdog, long timeoutNanos) {
        this.channel = channel;
        this.watchdog = watchdog;
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * {@code channel} bounded by {@code timeout}, or {@code channel} itself when {@code timeout} is
     * {@code null}, zero or negative (no bound). A timeout above {@link ConnectionWatchdog#MAX_TIMEOUT}
     * is saturated.
     *
     * @param watchdog the connection's watchdog, which closes the connection at a deadline
     */
    static WritableByteChannel of(WritableByteChannel channel, ConnectionWatchdog watchdog, Duration timeout) {
        long timeoutNanos = ConnectionWatchdog.nanos(timeout);
        if (timeoutNanos == 0) return channel;
        return new TimedWriteChannel(channel, watchdog, timeoutNanos);
    }

    /** Writes at most {@value #SLICE} bytes of {@code src}, bounded by the timeout; {@code src}'s limit is kept. */
    @Override
    public int write(ByteBuffer src) throws IOException {
        int limit = src.limit();
        src.limit(src.position() + Math.min(src.remaining(), SLICE));
        try {
            return watchdog.write(channel, src, timeoutNanos);
        } finally {
            src.limit(limit);
        }
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
