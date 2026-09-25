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
import java.nio.channels.SocketChannel;
import java.time.Duration;

/**
 * Lingering close (RFC 9112 §9.6). With request bytes still unread in the
 * receive buffer, a plain {@code close()} makes the TCP stack answer with a
 * reset, and the reset destroys the response the client has not read yet.
 * So the server half-closes (its last bytes are followed by a FIN), drains what
 * the client still sends within a bounded budget, and only then closes.
 */
final class LingeringClose {

    /** How long, and how much, the server drains before its full close. */
    static final Duration TIME = Duration.ofSeconds(2);

    static final long MAX_BYTES = 1024 * 1024;

    private LingeringClose() {}

    /**
     * Half-closes {@code channel}, then reads and discards input until the peer's
     * EOF, {@link #TIME} or {@link #MAX_BYTES}, whichever comes first. Does not
     * close the channel: the caller does. Over TLS the bytes are ciphertext and
     * are discarded without decryption: only their arrival matters.
     */
    static void halfCloseAndDrain(SocketChannel channel, ByteBuffer buffer) {
        try {
            channel.shutdownOutput();
            long deadline = System.nanoTime() + TIME.toNanos();
            long drained = 0;
            while (drained < MAX_BYTES) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                buffer.clear();
                int n = BoundedReads.readWithTimeout(channel, channel, buffer, Duration.ofNanos(left));
                if (n == -1) break; // the client closed its side
                drained += n;
            }
        } catch (IOException _) {
            // reset by the client, or closed by the watchdog at the deadline
        }
    }
}
