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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * ThreadLocal-based direct ByteBuffer pool — zero contention.
 * <p>
 * Each carrier thread (platform thread) has its own pool.
 * Virtual threads inherit the carrier they run on,
 * which is ideal: no CAS, no lock, direct access.
 */
public final class ByteBufferPool {

    private final int bufferSize;
    private final int maxPerThread;

    private final ThreadLocal<ArrayDeque<ByteBuffer>> local;

    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        // Spread max capacity across ~8 carrier threads
        this.maxPerThread = Math.max(4, maxPoolSize / 8);
        this.local = ThreadLocal.withInitial(ArrayDeque::new);
    }

    /** Acquires a buffer (cleared). Zero contention. */
    public ByteBuffer acquire() {
        var pool = local.get();
        ByteBuffer buf = pool.pollFirst();
        if (buf != null) {
            buf.clear();
            return buf;
        }
        return ByteBuffer.allocateDirect(bufferSize);
    }

    /** Returns a buffer to the local pool. Zero contention. */
    public void release(ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != bufferSize) return;
        var pool = local.get();
        if (pool.size() < maxPerThread) {
            pool.offerFirst(buffer);
        }
        // otherwise discard — will be GC'd
    }

    /** Clears the current thread's pool. */
    public void clear() {
        local.get().clear();
    }

    public int pooledCount() {
        return local.get().size();
    }
}
