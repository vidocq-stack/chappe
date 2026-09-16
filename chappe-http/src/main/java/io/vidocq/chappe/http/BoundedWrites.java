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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking channel writes bounded by a deadline — the Loom-friendly way.
 *
 * <p>Bounds exactly one blocking {@code channel.write(buf)} call: how long
 * the socket may refuse to accept bytes we are trying to send (kernel send
 * buffer saturated because the peer is not reading). It does
 * <strong>not</strong> bound inactivity on the response as a whole — a
 * long-lived streamed response (e.g. Server-Sent Events) with genuine idle
 * gaps between events is healthy and must not be affected, because {@code
 * write} is simply not called while there is nothing to send. Each call to
 * {@link #writeWithTimeout} arms and disarms its own watchdog, so idle time
 * between calls never accumulates against the deadline.</p>
 *
 * <p>Mirrors {@link BoundedReads}: {@code SO_TIMEOUT} has no effect on
 * blocking {@link java.nio.channels.SocketChannel} writes, and {@code
 * Selector.select} would pin the carrier thread. Instead, a virtual-thread
 * watchdog closes the channel at the deadline, which wakes the blocked write
 * with {@code AsynchronousCloseException} (surfaced as an {@link IOException}
 * to the caller, who treats it as a lost connection).</p>
 */
public final class BoundedWrites {

    private BoundedWrites() {}

    /**
     * Performs one blocking {@code channel.write(buf)} bounded by {@code
     * timeout}. On deadline, {@code closeable} is closed, waking the write.
     *
     * <p>A partial write (some but not all of {@code buf} accepted) is
     * <em>not</em> a timeout — it means the socket made progress, and the
     * caller's own write loop decides whether to issue another bounded call
     * for the remainder. Only a call that returns nothing at all within
     * {@code timeout} is treated as stalled.</p>
     *
     * @return the number of bytes written (possibly {@code 0} if the socket
     *         accepted nothing before more data became writable, but did not
     *         block long enough to hit the deadline)
     * @throws IOException on I/O failure — including the watchdog close
     *         (write timeout), which the caller must treat as a dead
     *         connection
     */
    public static int writeWithTimeout(
            WritableByteChannel channel, Closeable closeable, ByteBuffer buf, Duration timeout) throws IOException {
        var got = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("chappe-write-watchdog").start(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException _) {
                return; // write completed, watchdog disarmed
            }
            if (!got.get()) {
                try {
                    closeable.close();
                } catch (IOException _) {
                    // already closing
                }
            }
        });
        try {
            return channel.write(buf);
        } finally {
            got.set(true);
            watchdog.interrupt();
        }
    }
}
