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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;

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
 * all. Any other read arms the read side of the connection's {@link ConnectionWatchdog}: a field
 * write and a compare-and-set, no thread started per read. The socket's blocking mode is never
 * switched: that would take the channel's write lock, and a body read would wait behind a blocked
 * response write.
 */
final class TimedReadChannel implements ReadableByteChannel {

    /** See {@link ConnectionWatchdog#MAX_TIMEOUT}. */
    static final Duration MAX_TIMEOUT = ConnectionWatchdog.MAX_TIMEOUT;

    private final ReadableByteChannel delegate;
    private final ConnectionWatchdog watchdog;
    private final long timeoutNanos;
    private final long timeoutMillis;

    private TimedReadChannel(ReadableByteChannel delegate, ConnectionWatchdog watchdog, Duration timeout) {
        this.delegate = delegate;
        this.watchdog = watchdog;
        this.timeoutNanos = ConnectionWatchdog.nanos(timeout);
        this.timeoutMillis = timeoutNanos / 1_000_000;
    }

    /**
     * {@code delegate} bounded by {@code timeout}, or {@code delegate} itself when {@code timeout}
     * is {@code null}, zero or negative (no bound). A timeout above {@link #MAX_TIMEOUT} is saturated.
     *
     * @param watchdog the connection's watchdog, which closes the connection at a deadline
     */
    static ReadableByteChannel of(ReadableByteChannel delegate, ConnectionWatchdog watchdog, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return delegate;
        return new TimedReadChannel(delegate, watchdog, timeout);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (watchdog.readFired()) throw timedOut(null);
        if (!dst.hasRemaining()) return delegate.read(dst);

        int ready = readIfReady(dst);
        if (ready != 0) return ready;

        ConnectionWatchdog.Deadline deadline = watchdog.armRead(timeoutNanos);
        if (deadline == null) throw timedOut(null);
        int n;
        boolean returned = false;
        try {
            n = delegate.read(dst);
            returned = true;
        } catch (IOException e) {
            if (!watchdog.disarmRead(deadline)) throw timedOut(e);
            throw e;
        } finally {
            // Any other failure (an unchecked exception, an error) disarms the deadline too.
            if (!returned) watchdog.disarmRead(deadline);
        }
        if (!watchdog.disarmRead(deadline)) throw timedOut(null);
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

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
