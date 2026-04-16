package fr.vidocq.chappe.http;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe pool of direct ByteBuffers for connection reuse.
 *
 * <p>Uses a lock-free {@link ConcurrentLinkedQueue} internally. Buffers are
 * cleared (position=0, limit=capacity) before being handed out. If the pool
 * is empty a new direct buffer is allocated on the fly; if the pool is full
 * a released buffer is simply discarded so the pool never grows beyond
 * {@code maxPoolSize}.
 *
 * <p>Typical configuration: bufferSize=16384 (16 KB), maxPoolSize=1024
 * (≈ 16 MB of pooled memory).
 */
public final class ByteBufferPool {

    private final int bufferSize;
    private final int maxPoolSize;
    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

    /** Tracks the current number of buffers sitting in the queue. */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Creates a new pool.
     *
     * @param bufferSize  capacity of each individual buffer in bytes (e.g. 16384)
     * @param maxPoolSize maximum number of buffers kept in the pool at any time
     */
    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be > 0, got: " + bufferSize);
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize must be > 0, got: " + maxPoolSize);
        }
        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
    }

    /**
     * Acquires a buffer from the pool, or allocates a fresh direct buffer if the
     * pool is empty.
     *
     * <p>The returned buffer is always in a cleared state: position=0,
     * limit=capacity.
     *
     * @return a direct {@link ByteBuffer} with capacity == {@code bufferSize}
     */
    public ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer != null) {
            count.decrementAndGet();
            buffer.clear();
            return buffer;
        }
        return ByteBuffer.allocateDirect(bufferSize);
    }

    /**
     * Returns a buffer to the pool so it can be reused by a future {@link #acquire}
     * call.
     *
     * <p>The buffer is only accepted back when:
     * <ul>
     *   <li>its capacity exactly matches {@code bufferSize}, and</li>
     *   <li>the pool has not yet reached {@code maxPoolSize}.</li>
     * </ul>
     * Otherwise the buffer is silently discarded and will be garbage-collected.
     *
     * @param buffer the buffer to release; must not be {@code null}
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (buffer.capacity() != bufferSize) {
            // Wrong size — discard
            return;
        }
        // Optimistic check before the atomic increment to avoid overshooting.
        if (count.get() >= maxPoolSize) {
            return;
        }
        // Reserve a slot; another thread might beat us, so re-check.
        int slot = count.incrementAndGet();
        if (slot > maxPoolSize) {
            // We overshot — undo the increment and discard.
            count.decrementAndGet();
            return;
        }
        pool.offer(buffer);
    }

    /**
     * Removes and discards all buffers currently held in the pool.
     *
     * <p>Buffers that have been acquired but not yet released are unaffected.
     * This method is useful during orderly shutdown to release off-heap memory.
     */
    public void clear() {
        while (pool.poll() != null) {
            count.decrementAndGet();
        }
    }

    /**
     * Returns the number of buffers currently sitting idle in the pool.
     *
     * <p>This is a snapshot value; the actual count may change concurrently.
     *
     * @return current pool size
     */
    public int pooledCount() {
        return count.get();
    }
}
