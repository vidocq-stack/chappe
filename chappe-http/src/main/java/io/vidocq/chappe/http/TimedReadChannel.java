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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The channel HTTP/1.1 request bodies are read from: each blocking read is bounded by {@code
 * readTimeout} (CHAPPE-022). At the deadline the connection is closed, which wakes the read; the
 * read then throws {@link SocketTimeoutException}. It bounds each read, not the whole body: an upload
 * is not cut as long as each read makes progress within the timeout. Over plaintext a read returns
 * as soon as one byte arrives; over TLS it returns only once a whole TLS record (up to 16 KiB) is
 * received and decrypted, so a client must deliver each record within the timeout.
 *
 * <p>Cost on the hot path: bytes chappe already holds (the parse buffer, read by the body stream
 * before it reaches this channel, or bytes the {@link SslHandler} already decrypted) take no timer at
 * all. Any other read arms the deadline, which is a field write and a compare-and-set: one watchdog
 * virtual thread per connection, started at the first armed read, follows the armed deadlines and
 * parks in between. No thread is started per read. The socket's blocking mode is never switched: that
 * would take the channel's write lock, and a body read would wait behind a blocked response write.
 *
 * <p>A read that completes and the watchdog that fires race on one {@link AtomicReference}: exactly
 * one of them wins, so a read that ends at the deadline either returns its bytes on an open
 * connection or throws {@link SocketTimeoutException} on a closed one, never both.
 */
final class TimedReadChannel implements ReadableByteChannel {

    /**
     * One armed wait: its own identity, so a stale deadline is never mistaken for a new one. A class,
     * not a record: it is compared by identity only, as {@link #armed} does.
     */
    private static final class Deadline {
        private final long nanos;

        Deadline(long nanos) {
            this.nanos = nanos;
        }
    }

    /**
     * Longest bound applied: a larger timeout (up to {@code ChronoUnit.FOREVER}) is saturated to it,
     * about 73 years, so the deadline arithmetic in nanoseconds cannot overflow.
     */
    static final Duration MAX_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE / 4);

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
        Duration bounded = timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        this.timeoutNanos = bounded.toNanos();
        this.timeoutMillis = bounded.toMillis();
    }

    /**
     * {@code delegate} bounded by {@code timeout}, or {@code delegate} itself when {@code timeout}
     * is {@code null}, zero or negative (no bound). A timeout above {@link #MAX_TIMEOUT} is saturated.
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
        if (!armed.compareAndSet(null, deadline)) throw timedOut(null); // only FIRED is left
        wakeWatchdog();
        int n;
        boolean returned = false;
        try {
            n = delegate.read(dst);
            returned = true;
        } catch (IOException e) {
            if (!armed.compareAndSet(deadline, null)) throw timedOut(e);
            throw e;
        } finally {
            // Any other failure (an unchecked exception, an error) disarms the deadline too.
            if (!returned) armed.compareAndSet(deadline, null);
        }
        if (!armed.compareAndSet(deadline, null)) throw timedOut(null);
        return n;
    }

    /**
     * Fast path: reads bytes chappe already holds, {@code 0} when there are none. Only the TLS
     * channel's decrypted bytes qualify: probing the socket would mean switching its blocking mode.
     */
    private int readIfReady(ByteBuffer dst) throws IOException {
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
            long wait = d.nanos - System.nanoTime();
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
