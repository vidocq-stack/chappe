package io.vidocq.chappe.http;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Pool de ByteBuffer directs basé sur ThreadLocal — zéro contention.
 * <p>
 * Chaque carrier thread (platform thread) a son propre pool.
 * Les virtual threads héritent du carrier sur lequel ils s'exécutent,
 * ce qui est idéal : pas de CAS, pas de lock, accès direct.
 */
public final class ByteBufferPool {

    private final int bufferSize;
    private final int maxPerThread;

    private final ThreadLocal<ArrayDeque<ByteBuffer>> local;

    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        // Distribuer le max sur ~8 carrier threads
        this.maxPerThread = Math.max(4, maxPoolSize / 8);
        this.local = ThreadLocal.withInitial(ArrayDeque::new);
    }

    /** Acquiert un buffer (cleared). Zéro contention. */
    public ByteBuffer acquire() {
        var pool = local.get();
        ByteBuffer buf = pool.pollFirst();
        if (buf != null) {
            buf.clear();
            return buf;
        }
        return ByteBuffer.allocateDirect(bufferSize);
    }

    /** Retourne un buffer au pool local. Zéro contention. */
    public void release(ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != bufferSize) return;
        var pool = local.get();
        if (pool.size() < maxPerThread) {
            pool.offerFirst(buffer);
        }
        // sinon discard — sera GC'd
    }

    /** Vide le pool du thread courant. */
    public void clear() {
        local.get().clear();
    }

    public int pooledCount() {
        return local.get().size();
    }
}
