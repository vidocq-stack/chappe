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
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking channel reads bounded by a deadline — the Loom-friendly way.
 *
 * <p>{@code SO_TIMEOUT} is a silent no-op for blocking {@link
 * java.nio.channels.SocketChannel} reads, and {@code Selector.select} would
 * pin the carrier thread. Instead, a virtual-thread watchdog closes the
 * channel at the deadline, which wakes the blocked read with
 * {@code AsynchronousCloseException} (surfaced as {@link IOException} /
 * {@code -1} to the caller, who closes the connection).</p>
 */
public final class BoundedReads {

    private BoundedReads() {}

    /**
     * Performs one blocking {@code channel.read(buf)} bounded by
     * {@code timeout}. On deadline, {@code closeable} is closed, waking the
     * read.
     *
     * @return the read count, or {@code -1} on EOF
     * @throws IOException on I/O failure — including the watchdog close
     *         (idle timeout), which the caller must treat as end-of-connection
     */
    public static int readWithTimeout(
            ReadableByteChannel channel, Closeable closeable, ByteBuffer buf, Duration timeout) throws IOException {
        var got = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("chappe-idle-watchdog").start(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException _) {
                return; // data arrived, watchdog disarmed
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
            return channel.read(buf);
        } finally {
            got.set(true);
            watchdog.interrupt();
        }
    }
}
