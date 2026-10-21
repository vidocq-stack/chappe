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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded pool of direct buffers shared by every connection.
 * <p>
 * A connection takes its buffers when it opens and gives them back when it closes, on its own
 * virtual thread. The pool cannot live in a thread-local: each virtual thread has its own
 * (JEP 444), so a pool per thread never served another connection, and every connection
 * allocated fresh direct memory. The deque is lock-free and last-in first-out, so the buffer
 * handed out is the most recently used one; acquire and release happen once per connection,
 * not per request.
 */
public final class ByteBufferPool {

    private final int bufferSize;
    private final int maxPooled;

    private final ConcurrentLinkedDeque<ByteBuffer> pooled = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    /**
     * @param bufferSize the capacity of every buffer, in bytes
     * @param maxPoolSize the number of released buffers kept for reuse at most
     */
    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        this.maxPooled = maxPoolSize;
    }

    /** Acquires a cleared buffer: a pooled one when available, otherwise a new direct buffer. */
    public ByteBuffer acquire() {
        ByteBuffer buffer = pooled.pollFirst();
        if (buffer == null) {
            return ByteBuffer.allocateDirect(bufferSize);
        }
        size.decrementAndGet();
        return buffer.clear();
    }

    /**
     * Gives a buffer back for reuse. A buffer that is not one of this pool's kind (another
     * capacity, or on the heap) is ignored, and so is any buffer once the pool is full: the
     * garbage collector then frees it.
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != bufferSize || !buffer.isDirect()) return;
        if (size.incrementAndGet() > maxPooled) {
            size.decrementAndGet();
            return;
        }
        pooled.offerFirst(buffer);
    }

    /** Empties the pool. */
    public void clear() {
        while (pooled.pollFirst() != null) {
            size.decrementAndGet();
        }
    }

    /** The number of buffers waiting for reuse. */
    public int pooledCount() {
        return size.get();
    }
}
