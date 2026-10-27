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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The deadlines of one connection's blocking waits, kept by one virtual thread per connection.
 *
 * <p>{@code SO_TIMEOUT} is a silent no-op for blocking {@link java.nio.channels.SocketChannel}
 * reads and writes, and {@code Selector.select} would pin the carrier thread. A wait is bounded
 * by closing the connection at its deadline, which wakes the blocked read or write with an
 * {@code AsynchronousCloseException}. Until CHAPPE-022 every bounded wait started its own
 * watchdog virtual thread, slept it and interrupted it when the wait ended: the wait for a next
 * keep-alive request and every flush of a response each cost a thread start, a timed park and an
 * {@code InterruptedException} per request, which JFR counted at 40 000 requests per second as
 * 80 000 threads and exceptions per second.
 *
 * <p>Here a wait arms a deadline, a field write and a compare-and-set, on one of two slots: the
 * read side (the wait for a next request, a body read) and the write side (a response write).
 * The connection's one watchdog thread, started at the first armed wait, sleeps to the nearest
 * deadline and parks while nothing is armed; it never starts another thread. On a connection
 * only its own thread reads, and only one thread writes at a time, so one deadline per side is
 * enough: arming a side that already holds a live deadline is a programming error.
 *
 * <p>A wait that completes and the watchdog that fires race on the slot: exactly one of them
 * wins, so a wait that ends at the deadline either returns on an open connection or fails on a
 * closed one, never both.
 */
final class ConnectionWatchdog {

    /**
     * One armed wait: its own identity, so a stale deadline is never mistaken for a new one. A
     * class, not a record: it is compared by identity only.
     */
    static final class Deadline {
        private final long nanos;

        private Deadline(long nanos) {
            this.nanos = nanos;
        }
    }

    /**
     * Longest bound applied: a larger timeout (up to {@code ChronoUnit.FOREVER}) is saturated to
     * it, about 73 years, so the deadline arithmetic in nanoseconds cannot overflow.
     */
    static final Duration MAX_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE / 4);

    /** The watchdog fired on this side and closed the connection: terminal. */
    private static final Deadline FIRED = new Deadline(0);

    private final Closeable closeable;
    private final AtomicReference<Deadline> read = new AtomicReference<>();
    private final AtomicReference<Deadline> write = new AtomicReference<>();

    private volatile Thread thread;
    private volatile boolean idle;
    /** The deadline the thread sleeps towards, {@code Long.MAX_VALUE} when it does not sleep. */
    private volatile long parkedUntil = Long.MAX_VALUE;

    private volatile boolean stopped;

    /** @param closeable what is closed at a deadline: the connection */
    ConnectionWatchdog(Closeable closeable) {
        this.closeable = closeable;
    }

    /** {@code timeout} in nanoseconds, saturated to {@link #MAX_TIMEOUT}; {@code 0} when it means no bound. */
    static long nanos(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return 0;
        return (timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout).toNanos();
    }

    /**
     * Arms the read side for a wait that starts now, or returns {@code null} when the watchdog
     * already closed the connection from that side.
     */
    Deadline armRead(long timeoutNanos) {
        return arm(read, timeoutNanos);
    }

    /** Disarms the read side; {@code false} when the watchdog fired meanwhile: the wait ended by the close. */
    boolean disarmRead(Deadline deadline) {
        return read.compareAndSet(deadline, null);
    }

    /** Whether the watchdog closed the connection from the read side. */
    boolean readFired() {
        return read.get() == FIRED;
    }

    /**
     * One blocking write bounded by {@code timeoutNanos}, none when it is {@code 0} or less: at the
     * deadline the connection is closed, which wakes the write.
     *
     * @return the number of bytes written
     * @throws IOException on I/O failure, including the close at the deadline, which the caller
     *         treats as a dead connection
     */
    int write(WritableByteChannel channel, ByteBuffer buffer, long timeoutNanos) throws IOException {
        if (timeoutNanos <= 0) return channel.write(buffer);
        Deadline deadline = arm(write, timeoutNanos);
        if (deadline == null) throw new ClosedChannelException();
        int n;
        boolean returned = false;
        try {
            n = channel.write(buffer);
            returned = true;
        } finally {
            // A failure of the write, an exception or an error, disarms the deadline too.
            if (!returned) write.compareAndSet(deadline, null);
        }
        if (!write.compareAndSet(deadline, null)) {
            throw new IOException("Write timed out: the connection was closed by its watchdog");
        }
        return n;
    }

    private Deadline arm(AtomicReference<Deadline> side, long timeoutNanos) {
        var deadline = new Deadline(System.nanoTime() + timeoutNanos);
        if (!side.compareAndSet(null, deadline)) {
            if (side.get() == FIRED) return null;
            throw new IllegalStateException("A wait is already armed on this side of the connection");
        }
        wake(deadline.nanos);
        return deadline;
    }

    /**
     * Wakes the thread when it parks idle, or sleeps towards a deadline later than the one just
     * armed: a wait disarmed before its deadline leaves the thread sleeping towards it, and a
     * shorter wait armed meanwhile must not wait for that stale deadline. In the steady state of
     * a keep-alive connection the deadlines armed are later than the one slept towards, and no
     * wake-up is needed.
     */
    private void wake(long deadlineNanos) {
        Thread t = thread;
        if (t == null) {
            start();
        } else if (idle || deadlineNanos - parkedUntil < 0) {
            LockSupport.unpark(t);
        }
    }

    private synchronized void start() {
        if (thread == null && !stopped) {
            thread = Thread.ofVirtual().name("chappe-connection-watchdog").start(this::watch);
        }
    }

    /** The watchdog loop: parks while nothing waits, sleeps to the nearest deadline, fires past it. */
    private void watch() {
        while (!stopped) {
            Deadline r = read.get();
            Deadline w = write.get();
            if (r == FIRED || w == FIRED) return;
            if (r == null && w == null) {
                // Dekker pairing with wake(): either this sees the new deadline, or the arming
                // thread sees the idle flag and unparks.
                idle = true;
                if (read.get() == null && write.get() == null && !stopped) LockSupport.park(this);
                idle = false;
                continue;
            }
            long until = Long.MAX_VALUE;
            if (r != null) until = r.nanos;
            if (w != null && (r == null || w.nanos - r.nanos < 0)) until = w.nanos;
            long now = System.nanoTime();
            if (until - now > 0) {
                // Dekker pairing with wake(): a shorter wait armed after this point sees
                // parkedUntil and unparks; one armed before it is seen by the check below.
                parkedUntil = until;
                if (armedBefore(until)) {
                    parkedUntil = Long.MAX_VALUE;
                    continue;
                }
                LockSupport.parkNanos(this, until - now);
                parkedUntil = Long.MAX_VALUE;
                continue;
            }
            // A deadline passed: fire the side whose wait is still armed, and close.
            if ((r != null && r.nanos - now <= 0 && read.compareAndSet(r, FIRED))
                    || (w != null && w.nanos - now <= 0 && write.compareAndSet(w, FIRED))) {
                try {
                    closeable.close();
                } catch (IOException _) {
                    // already closing
                }
                return;
            }
            // The wait ended between the check and the compare-and-set: look again.
        }
    }

    /** Whether a side holds a deadline earlier than {@code until}. */
    private boolean armedBefore(long until) {
        Deadline r = read.get();
        Deadline w = write.get();
        return (r != null && r != FIRED && r.nanos - until < 0) || (w != null && w != FIRED && w.nanos - until < 0);
    }

    /** Ends the watchdog, if any; the connection closes on its own. */
    void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }
}
