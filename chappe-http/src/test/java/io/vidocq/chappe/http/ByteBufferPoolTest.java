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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The buffer pool serves every connection, whatever virtual thread runs it: each virtual thread
 * has its own thread-locals (JEP 444), so a pool kept in a thread-local never served another
 * connection.
 */
class ByteBufferPoolTest {

    @Test
    void aBufferReleasedOnOneVirtualThreadServesTheNext() throws InterruptedException {
        var pool = new ByteBufferPool(1024, 16);
        var released = new AtomicReference<ByteBuffer>();
        Thread.ofVirtual()
                .start(() -> {
                    ByteBuffer buffer = pool.acquire();
                    released.set(buffer);
                    pool.release(buffer);
                })
                .join();

        var acquired = new AtomicReference<ByteBuffer>();
        Thread.ofVirtual().start(() -> acquired.set(pool.acquire())).join();

        assertSame(released.get(), acquired.get(), "the second connection must reuse the first one's buffer");
    }

    @Test
    void sequentialConnectionsOnVirtualThreadsAllocateOneBuffer() throws InterruptedException {
        var pool = new ByteBufferPool(1024, 16);
        Set<ByteBuffer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 100; i++) {
            Thread.ofVirtual()
                    .start(() -> {
                        ByteBuffer buffer = pool.acquire();
                        seen.add(buffer);
                        pool.release(buffer);
                    })
                    .join();
        }
        assertEquals(1, seen.size(), "100 connections one after the other need a single buffer");
    }

    @Test
    void buffersHeldAtTheSameTimeAreDistinct() throws InterruptedException {
        var pool = new ByteBufferPool(1024, 16);
        for (int i = 0; i < 8; i++) pool.release(ByteBuffer.allocateDirect(1024));
        int holders = 32;
        var allAcquired = new CountDownLatch(holders);
        var done = new CountDownLatch(1);
        Set<ByteBuffer> held = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < holders; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                ByteBuffer buffer = pool.acquire();
                held.add(buffer);
                allAcquired.countDown();
                try {
                    done.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                pool.release(buffer);
            }));
        }
        allAcquired.await();
        assertEquals(holders, held.size(), "a buffer must never be handed to two holders at once");
        done.countDown();
        for (Thread t : threads) t.join();
    }

    @Test
    void acquiredBuffersAreCleared() {
        var pool = new ByteBufferPool(1024, 16);
        ByteBuffer buffer = pool.acquire();
        buffer.put(new byte[100]).flip();
        pool.release(buffer);

        ByteBuffer again = pool.acquire();
        assertSame(buffer, again);
        assertEquals(0, again.position());
        assertEquals(1024, again.limit());
    }

    @Test
    void thePoolKeepsAtMostItsMaximum() {
        var pool = new ByteBufferPool(1024, 4);
        for (int i = 0; i < 10; i++) pool.release(ByteBuffer.allocateDirect(1024));
        assertEquals(4, pool.pooledCount());
    }

    @Test
    void buffersOfAnotherSizeOrOnTheHeapAreNotPooled() {
        var pool = new ByteBufferPool(1024, 4);
        pool.release(ByteBuffer.allocateDirect(512));
        pool.release(ByteBuffer.allocate(1024));
        pool.release(null);
        assertEquals(0, pool.pooledCount());
    }

    @Test
    void clearEmptiesThePoolForEveryThread() throws InterruptedException {
        var pool = new ByteBufferPool(1024, 4);
        Thread.ofVirtual().start(() -> pool.release(pool.acquire())).join();
        pool.clear();
        assertEquals(0, pool.pooledCount());
    }
}
