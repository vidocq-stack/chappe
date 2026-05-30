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
