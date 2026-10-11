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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The channel HTTP/1.1 request bodies are read from: each blocking read is bounded by {@code
 * readTimeout} (CHAPPE-022). At the deadline the connection is closed, which wakes the read; the
 * read then throws {@link SocketTimeoutException}. It bounds each read, not the whole body: an upload
 * that keeps sending, however slowly, is not cut.
 *
 * <p>Cost on the hot path: a read that finds bytes ready (in the kernel buffer for a plain socket, or
 * already decrypted by the {@link SslHandler}) takes no timer at all. Only a read that has to wait
 * arms the deadline, which is a field write: one watchdog virtual thread per connection, started at
 * the first read that waits, follows the armed deadlines and parks in between. No thread is started
 * per read.
 *
 * <p>A read that completes and the watchdog that fires race on one {@link AtomicReference}: exactly
 * one of them wins, so a read that ends at the deadline either returns its bytes on an open
 * connection or throws {@link SocketTimeoutException} on a closed one, never both.
 */
final class TimedReadChannel implements ReadableByteChannel {

    /** One armed wait: its own identity, so a stale deadline is never mistaken for a new one. */
    private record Deadline(long nanos) {}

    /** The watchdog fired and closed the connection: terminal. */
    private static final Deadline FIRED = new Deadline(0);

    private final ReadableByteChannel delegate;
    private final Closeable closeable;
    private final long timeoutNanos;
    private final long timeoutMillis;

    /** The deadline of the read waiting now, {@code null} when none waits, or {@link #FIRED}. */
    private final AtomicReference<Deadline> armed = new AtomicReference<>();

    private volatile Thread watchdog;
    private volatile boolean watchdogIdle;
    private volatile boolean stopped;

    private TimedReadChannel(ReadableByteChannel delegate, Closeable closeable, Duration timeout) {
        this.delegate = delegate;
        this.closeable = closeable;
        this.timeoutNanos = timeout.toNanos();
        this.timeoutMillis = timeout.toMillis();
    }

    /**
     * {@code delegate} bounded by {@code timeout}, or {@code delegate} itself when {@code timeout}
     * is {@code null}, zero or negative (no bound).
     *
     * @param closeable what is closed at the deadline: the connection
     */
    static ReadableByteChannel of(ReadableByteChannel delegate, Closeable closeable, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return delegate;
        return new TimedReadChannel(delegate, closeable, timeout);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (armed.get() == FIRED) throw timedOut(null);
        if (!dst.hasRemaining()) return delegate.read(dst);

        int ready = readIfReady(dst);
        if (ready != 0) return ready;

        var deadline = new Deadline(System.nanoTime() + timeoutNanos);
        armed.set(deadline);
        wakeWatchdog();
        int n;
        try {
            n = delegate.read(dst);
        } catch (IOException e) {
            if (!armed.compareAndSet(deadline, null)) throw timedOut(e);
            throw e;
        } catch (RuntimeException | Error e) {
            armed.compareAndSet(deadline, null);
            throw e;
        }
        if (!armed.compareAndSet(deadline, null)) throw timedOut(null);
        return n;
    }

    /**
     * Fast path: reads what is ready without waiting, {@code 0} when the read would have to wait. A
     * plain socket is probed with a non-blocking read (as {@code HttpConnection.awaitNextRequest}
     * does); a TLS channel is read directly when it holds decrypted bytes.
     */
    private int readIfReady(ByteBuffer dst) throws IOException {
        if (delegate instanceof SocketChannel sc) {
            sc.configureBlocking(false);
            try {
                return sc.read(dst);
            } finally {
                sc.configureBlocking(true);
            }
        }
        if (delegate instanceof SslHandler ssl && ssl.hasDecryptedInput()) return ssl.read(dst);
        return 0;
    }

    private SocketTimeoutException timedOut(IOException cause) {
        var timedOut = new SocketTimeoutException("Request body read timed out after " + timeoutMillis + " ms");
        if (cause != null) timedOut.initCause(cause);
        return timedOut;
    }

    private void wakeWatchdog() {
        Thread w = watchdog;
        if (w == null) {
            startWatchdog();
        } else if (watchdogIdle) {
            LockSupport.unpark(w);
        }
    }

    private synchronized void startWatchdog() {
        if (watchdog == null && !stopped) {
            watchdog = Thread.ofVirtual().name("chappe-read-watchdog").start(this::watch);
        }
    }

    /** The watchdog loop: parks while no read waits, sleeps to the armed deadline, fires past it. */
    private void watch() {
        while (!stopped) {
            Deadline d = armed.get();
            if (d == FIRED) return;
            if (d == null) {
                // Dekker pairing with wakeWatchdog(): either this sees the new deadline, or the
                // reader sees the idle flag and unparks.
                watchdogIdle = true;
                if (armed.get() == null && !stopped) LockSupport.park(this);
                watchdogIdle = false;
                continue;
            }
            long wait = d.nanos() - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(this, wait); // a later deadline is re-read on wake-up
            } else if (armed.compareAndSet(d, FIRED)) {
                try {
                    closeable.close();
                } catch (IOException _) {
                    // already closing
                }
                return;
            }
        }
    }

    /** Ends the watchdog, if any; the connection closes on its own. */
    void stop() {
        stopped = true;
        Thread w = watchdog;
        if (w != null) LockSupport.unpark(w);
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
        stop();
        delegate.close();
    }
}
