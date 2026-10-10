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
import java.nio.channels.ReadableByteChannel;

/**
 * The one way chappe refills a read buffer kept in read mode: {@code compact()}, read,
 * {@code flip()}. The flip runs in a {@code finally}: a read that throws (a peer reset, a watchdog
 * close, an interrupt closing the channel) leaves the buffer in read mode with only the bytes not
 * yet consumed. Without it the buffer stayed in write mode, so its whole capacity, stale bytes
 * included, read as unread data (CHAPPE-013).
 */
final class ReadBuffers {

    private ReadBuffers() {}

    /** Compacts {@code buffer}, reads once from {@code channel}, flips back, even when the read throws. */
    static int compactAndRead(ByteBuffer buffer, ReadableByteChannel channel) throws IOException {
        buffer.compact();
        try {
            return channel.read(buffer);
        } finally {
            buffer.flip();
        }
    }
}
