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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import io.vidocq.chappe.api.TlsInfo;

/**
 * Wraps a {@link SocketChannel} and {@link SSLEngine} to provide transparent TLS
 * read/write as {@link ReadableByteChannel} and {@link WritableByteChannel}.
 * <p>
 * Thread-safe for concurrent reads/writes (HTTP/2: the frame loop reads on one thread,
 * stream threads write responses).
 * <ul>
 *   <li>Read operations (unwrap) are protected by {@code readLock}</li>
 *   <li>Write operations (wrap) are protected by {@code writeLock}</li>
 *   <li>SSLEngine supports concurrent unwrap/wrap if buffers are separate</li>
 * </ul>
 */
public final class SslHandler implements ReadableByteChannel, WritableByteChannel, Closeable {

    /** How long {@link #close()} waits for a concurrent writer before closing without close_notify. */
    private static final long CLOSE_LOCK_WAIT_MS = 100;

    private final SocketChannel channel;
    private final SSLEngine engine;

    // Read buffers (unwrap) — accessed only under readLock
    private final ByteBuffer netInBuffer;
    private final ByteBuffer appInBuffer;

    // Write buffer (wrap) — accessed only under writeLock
    private final ByteBuffer netOutBuffer;

    // Separate locks for concurrent read/write
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    /**
     * The bound on each close write when the given {@code writeTimeout} is {@code null}, zero or
     * negative: the lingering-close budget, so a TLS close is bounded like a plaintext one.
     */
    static final Duration CLOSE_WRITE_FALLBACK = LingeringClose.TIME;

    /**
     * Bound on each blocking write of the close path: the close_notify of {@link #close()} and
     * {@link #closeNotifyAndDrain(ByteBuffer)}, and the alert of a rejected handshake. Always
     * positive, so a close never blocks forever.
     */
    private final Duration closeWriteTimeout;

    /** The session of the completed handshake; {@code null} until {@link #doHandshake()} returns. */
    private volatile TlsInfo tlsInfo;

    /** A handler whose {@link #close()} bounds its close_notify write by the default write timeout. */
    public SslHandler(SocketChannel channel, SSLEngine engine) {
        this(channel, engine, io.vidocq.chappe.api.ServerConfig.DEFAULT.writeTimeout());
    }

    /**
     * @param writeTimeout bound on each blocking write of the close path: the close_notify sent by
     *                     {@link #close()} and the alert of a rejected handshake (normally the
     *                     server's {@code writeTimeout}); at the deadline the socket is closed
     *                     without them. Unlike data writes ({@link BoundedWrites}, where {@code
     *                     null}, zero or negative means no bound), the close is always bounded:
     *                     {@code null}, zero or negative falls back to {@link #CLOSE_WRITE_FALLBACK}
     *                     (2 s)
     */
    public SslHandler(SocketChannel channel, SSLEngine engine, Duration writeTimeout) {
        this.channel = channel;
        this.engine = engine;
        this.closeWriteTimeout = writeTimeout == null || writeTimeout.isZero() || writeTimeout.isNegative()
                ? CLOSE_WRITE_FALLBACK
                : writeTimeout;

        int packetSize = engine.getSession().getPacketBufferSize();
        int appSize = engine.getSession().getApplicationBufferSize();

        this.netInBuffer = ByteBuffer.allocate(packetSize);
        this.netOutBuffer = ByteBuffer.allocate(packetSize);
        this.appInBuffer = ByteBuffer.allocate(appSize);
        appInBuffer.flip();
    }

    // -------------------------------------------------------------------------
    // Handshake (single-threaded, before concurrent read/write)
    // -------------------------------------------------------------------------

    /** Runs the TLS handshake with no time bound; see {@link #doHandshake(Duration)}. */
    public void doHandshake() throws IOException {
        doHandshake(null);
    }

    /**
     * Runs the TLS handshake within {@code timeout} in total ({@code null}, zero or negative: no
     * bound). The deadline covers the whole handshake, so a client that sends nothing, half a
     * ClientHello, or one byte at a time is cut when it expires: a watchdog closes the socket and
     * this method throws {@link SocketTimeoutException} (CHAPPE-028). A handshake the engine rejects
     * still ends with its alert and the bounded lingering close (CHAPPE-027), outside this deadline.
     */
    public void doHandshake(Duration timeout) throws IOException {
        HandshakeDeadline deadline = HandshakeDeadline.arm(timeout, channel);
        try {
            try {
                runHandshake();
            } finally {
                // Before the rejected-handshake path below: the alert and the lingering close
                // keep their own budgets and must not be cut by this deadline.
                deadline.disarm();
            }
        } catch (SSLException e) {
            if (deadline.fired()) throw deadline.timedOut(e);
            // A rejected handshake (no or untrusted client certificate, no shared protocol...)
            // leaves JSSE's fatal alert in the engine: send it, so the client learns why
            // instead of seeing a bare close (CHAPPE-027). Then half-close and drain, bounded:
            // closing with the client's flight still unread would answer with a TCP reset,
            // which destroys the alert before the client reads it. The caller then closes.
            if (sendPendingAlert()) {
                LingeringClose.halfCloseAndDrain(channel, netInBuffer.clear());
            }
            throw e;
        } catch (IOException e) {
            // The watchdog's close wakes a blocked read or write with an AsynchronousCloseException
            if (deadline.fired()) throw deadline.timedOut(e);
            throw e;
        }
        // The handshake ended just as the deadline fired: the socket is closed, do not hand it on
        if (deadline.fired()) throw deadline.timedOut(null);
        tlsInfo = describe(engine.getSession());
    }

    /**
     * The total deadline of one handshake (CHAPPE-028), on the pattern of {@link TimedReadChannel}:
     * one watchdog virtual thread parks until the deadline, then closes the socket. The end of the
     * handshake ({@link #disarm()}) and the watchdog race on one compare-and-set from {@code ARMED}:
     * exactly one wins, so the handshake either completes on an open socket or fails with {@link
     * SocketTimeoutException} on a closed one, never both.
     */
    private static final class HandshakeDeadline {

        private static final int ARMED = 0;
        private static final int DISARMED = 1;
        private static final int FIRED = 2;

        /** No bound: never armed, never fires. */
        private static final HandshakeDeadline NONE = new HandshakeDeadline(0, null);

        private final AtomicInteger state;
        private final long deadlineNanos;
        private final long timeoutMillis;
        private final Closeable closeable;
        private final Thread watchdog;

        private HandshakeDeadline(long timeoutNanos, Closeable closeable) {
            this.closeable = closeable;
            this.timeoutMillis = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
            if (closeable == null) {
                this.state = new AtomicInteger(DISARMED);
                this.deadlineNanos = 0;
                this.watchdog = null;
            } else {
                this.state = new AtomicInteger(ARMED);
                this.deadlineNanos = System.nanoTime() + timeoutNanos;
                this.watchdog =
                        Thread.ofVirtual().name("chappe-handshake-watchdog").unstarted(this::watch);
            }
        }

        /**
         * A started deadline {@code timeout} from now that closes {@code closeable} when it fires,
         * or a no-op one for a {@code null}, zero or negative {@code timeout}. A timeout above
         * {@link TimedReadChannel#MAX_TIMEOUT} is saturated, so the arithmetic cannot overflow.
         */
        static HandshakeDeadline arm(Duration timeout, Closeable closeable) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) return NONE;
            Duration bounded =
                    timeout.compareTo(TimedReadChannel.MAX_TIMEOUT) > 0 ? TimedReadChannel.MAX_TIMEOUT : timeout;
            var deadline = new HandshakeDeadline(bounded.toNanos(), closeable);
            deadline.watchdog.start();
            return deadline;
        }

        /** Sleeps to the deadline, re-checking the state at every wake-up, then fires if still armed. */
        private void watch() {
            while (state.get() == ARMED) {
                long wait = deadlineNanos - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(this, wait); // unparked by disarm(), or spurious: loop
                } else {
                    if (state.compareAndSet(ARMED, FIRED)) {
                        try {
                            closeable.close();
                        } catch (IOException _) {
                            // already closing
                        }
                    }
                    return;
                }
            }
        }

        /** Ends the deadline if it has not fired; the watchdog then exits. Idempotent. */
        void disarm() {
            if (state.compareAndSet(ARMED, DISARMED)) LockSupport.unpark(watchdog);
        }

        boolean fired() {
            return state.get() == FIRED;
        }

        SocketTimeoutException timedOut(IOException cause) {
            var timedOut = new SocketTimeoutException("TLS handshake timed out after " + timeoutMillis + " ms");
            if (cause != null) timedOut.initCause(cause);
            return timedOut;
        }
    }

    /**
     * Best-effort: wraps and writes what the engine still has to send (the alert of a rejected
     * handshake), each write bounded by the close write timeout (always positive).
     *
     * @return whether bytes were written and the connection is still open, so a lingering close
     *         protects them; {@code false} when there was nothing to send (for example, the
     *         handshake failed on the client's own fatal alert) or the write failed
     */
    private boolean sendPendingAlert() {
        boolean wrote = false;
        try {
            for (int i = 0; i < 4 && !engine.isOutboundDone(); i++) {
                netOutBuffer.clear();
                SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    BoundedWrites.writeWithTimeout(channel, channel, netOutBuffer, closeWriteTimeout);
                }
                if (result.bytesProduced() == 0) {
                    break;
                }
                wrote = true;
            }
            return wrote && channel.isOpen();
        } catch (IOException _) {
            return false; // the connection is gone; the caller closes it anyway
        }
    }

    private void runHandshake() throws IOException {
        engine.beginHandshake();
        HandshakeStatus hs = engine.getHandshakeStatus();
        boolean needsBytes = true;

        while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
            switch (hs) {
                case NEED_UNWRAP -> {
                    // Unwrap what is already buffered before touching the socket: the loop below stops when
                    // the engine asks for a task or a wrap, possibly with whole records left (a TLS 1.2 client
                    // sends Certificate..CertificateVerify, ChangeCipherSpec and Finished in one write, then
                    // waits for the server's Finished). Reading first would block on a client that has sent
                    // everything. Read only when nothing is buffered or the engine needs more bytes
                    // (BUFFER_UNDERFLOW), as readInternal does (CHAPPE-004, chappe#24).
                    if (needsBytes || netInBuffer.position() == 0) {
                        int bytesRead = channel.read(netInBuffer);
                        if (bytesRead < 0) {
                            throw new IOException("Channel closed during TLS handshake");
                        }
                    }
                    netInBuffer.flip();

                    SSLEngineResult result;
                    do {
                        appInBuffer.clear();
                        result = engine.unwrap(netInBuffer, appInBuffer);
                        appInBuffer.flip();

                        if (result.getStatus() == Status.BUFFER_UNDERFLOW) break;
                        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                            throw new IOException("appInBuffer overflow during handshake");
                        }
                        if (result.getStatus() == Status.CLOSED) {
                            throw new IOException("SSLEngine closed during handshake");
                        }
                    } while (netInBuffer.hasRemaining()
                            && result.getStatus() == Status.OK
                            && result.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP);

                    needsBytes = result.getStatus() == Status.BUFFER_UNDERFLOW;
                    netInBuffer.compact();
                    hs = result.getHandshakeStatus();
                }
                case NEED_WRAP -> {
                    netOutBuffer.clear();
                    SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
                    netOutBuffer.flip();
                    if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                        throw new IOException("netOutBuffer overflow during handshake");
                    }
                    while (netOutBuffer.hasRemaining()) {
                        channel.write(netOutBuffer);
                    }
                    hs = result.getHandshakeStatus();
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    hs = engine.getHandshakeStatus();
                }
                case NEED_UNWRAP_AGAIN -> {
                    netInBuffer.flip();
                    appInBuffer.clear();
                    SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
                    netInBuffer.compact();
                    appInBuffer.flip();
                    hs = result.getHandshakeStatus();
                }
                default -> throw new IOException("Unexpected handshake status: " + hs);
            }
        }
    }

    // -------------------------------------------------------------------------
    // ReadableByteChannel — thread-safe via readLock
    // -------------------------------------------------------------------------

    @Override
    public int read(ByteBuffer dst) throws IOException {
        readLock.lock();
        try {
            return readInternal(dst);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Whether a {@link #read} would return decrypted bytes without touching the socket: the fast
     * path of {@link TimedReadChannel}, which then takes no timer (CHAPPE-022).
     */
    boolean hasDecryptedInput() {
        readLock.lock();
        try {
            return appInBuffer.hasRemaining();
        } finally {
            readLock.unlock();
        }
    }

    private int readInternal(ByteBuffer dst) throws IOException {
        if (appInBuffer.hasRemaining()) {
            return drain(dst);
        }

        while (true) {
            // Unwrap whatever is ALREADY buffered in netInBuffer before touching the
            // socket. Application data often arrives coalesced with the final TLS
            // handshake flight in a single TCP segment; doHandshake() leaves those
            // encrypted bytes compacted in netInBuffer. If we blindly called
            // channel.read() first (as the previous version did), a client that has
            // sent its full request and is now waiting for the response would deadlock
            // us forever — the complete record is in hand but the peer sends no more
            // bytes. So we only read from the socket on BUFFER_UNDERFLOW (the engine
            // genuinely needs more ciphertext). (CHAPPE-004)
            netInBuffer.flip();
            appInBuffer.clear();
            SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
            netInBuffer.compact();
            appInBuffer.flip();

            // TLS 1.2 renegotiation would change the session (and client certificate) under the
            // TlsInfo read at the handshake: refuse it and let the caller close the connection.
            if (isRenegotiationAttempt(result)) {
                throw new SSLException("TLS renegotiation refused: the session is fixed at the handshake");
            }

            // Post-handshake events (TLS 1.3 key updates)
            handlePostUnwrap(result);

            switch (result.getStatus()) {
                case OK -> {
                    if (appInBuffer.hasRemaining()) return drain(dst);
                    // Produced no application data (e.g. a non-app record was consumed)
                    // — loop to unwrap any further buffered records.
                }
                case BUFFER_UNDERFLOW -> {
                    // Engine needs more ciphertext: now it is safe to block on the socket.
                    int bytesRead = channel.read(netInBuffer);
                    if (bytesRead < 0) {
                        return -1;
                    }
                }
                case BUFFER_OVERFLOW -> throw new IOException("appInBuffer overflow");
                case CLOSED -> {
                    return -1;
                }
            }
        }
    }

    private int drain(ByteBuffer dst) {
        int toTransfer = Math.min(appInBuffer.remaining(), dst.remaining());
        if (toTransfer == 0) return 0;
        int limit = appInBuffer.limit();
        appInBuffer.limit(appInBuffer.position() + toTransfer);
        dst.put(appInBuffer);
        appInBuffer.limit(limit);
        return toTransfer;
    }

    /**
     * A handshake message unwrapped after the initial handshake of a TLS 1.2 (or older) session
     * is a renegotiation attempt. TLS 1.3 has no renegotiation, and its post-handshake messages
     * (KeyUpdate) stay allowed; a close_notify is not one either.
     */
    private boolean isRenegotiationAttempt(SSLEngineResult result) {
        if (result.getStatus() == Status.CLOSED) {
            return false;
        }
        HandshakeStatus hs = result.getHandshakeStatus();
        if (hs == HandshakeStatus.NOT_HANDSHAKING || hs == HandshakeStatus.FINISHED) {
            return false;
        }
        TlsInfo info = tlsInfo;
        return info != null && !"TLSv1.3".equals(info.protocol());
    }

    /**
     * Post-unwrap: handles delegated tasks and TLS 1.3 key updates.
     * Required wraps are performed under writeLock.
     */
    private void handlePostUnwrap(SSLEngineResult result) throws IOException {
        HandshakeStatus hs = result.getHandshakeStatus();
        while (hs == HandshakeStatus.NEED_TASK || hs == HandshakeStatus.NEED_WRAP) {
            if (hs == HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
            } else { // NEED_WRAP — must use writeLock
                writeLock.lock();
                try {
                    netOutBuffer.clear();
                    engine.wrap(EMPTY, netOutBuffer);
                    netOutBuffer.flip();
                    while (netOutBuffer.hasRemaining()) {
                        channel.write(netOutBuffer);
                    }
                } finally {
                    writeLock.unlock();
                }
            }
            hs = engine.getHandshakeStatus();
        }
    }

    // -------------------------------------------------------------------------
    // WritableByteChannel — thread-safe via writeLock
    // -------------------------------------------------------------------------

    @Override
    public int write(ByteBuffer src) throws IOException {
        writeLock.lock();
        try {
            return writeInternal(src);
        } finally {
            writeLock.unlock();
        }
    }

    private int writeInternal(ByteBuffer src) throws IOException {
        int totalWritten = 0;
        while (src.hasRemaining()) {
            netOutBuffer.clear();
            SSLEngineResult result = engine.wrap(src, netOutBuffer);
            netOutBuffer.flip();

            switch (result.getStatus()) {
                case OK, CLOSED -> {
                    while (netOutBuffer.hasRemaining()) {
                        channel.write(netOutBuffer);
                    }
                    totalWritten += result.bytesConsumed();
                }
                case BUFFER_OVERFLOW -> throw new IOException("netOutBuffer overflow during write");
                case BUFFER_UNDERFLOW -> throw new IOException("Unexpected BUFFER_UNDERFLOW during write");
            }

            if (result.getStatus() == Status.CLOSED) break;
        }
        // The engine's outbound side is closed (close() or a peer close_notify) and refused bytes:
        // nothing written ever will be. Returning 0 would make a "write until done" loop spin until
        // its write timeout (CHAPPE-022: a read timeout closes the connection under the handler).
        if (totalWritten == 0 && src.hasRemaining() && engine.isOutboundDone()) {
            throw new ClosedChannelException();
        }
        return totalWritten;
    }

    // -------------------------------------------------------------------------
    // ALPN & lifecycle
    // -------------------------------------------------------------------------

    public String getAlpnProtocol() {
        return engine.getApplicationProtocol();
    }

    /**
     * The TLS session negotiated by {@link #doHandshake()}, or {@code null} before it returned.
     * Read once, at the end of the handshake.
     */
    public TlsInfo tlsInfo() {
        return tlsInfo;
    }

    private static TlsInfo describe(SSLSession session) {
        List<X509Certificate> chain = new ArrayList<>();
        try {
            for (Certificate certificate : session.getPeerCertificates()) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
        } catch (SSLPeerUnverifiedException _) {
            // The client sent no certificate, or the listener did not ask for one.
        }
        return new TlsInfo(session.getProtocol(), session.getCipherSuite(), session.getId(), chain);
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    /**
     * First half of a lingering close over TLS (see {@link LingeringClose}): sends
     * close_notify, then half-closes the socket and drains the ciphertext the peer
     * still sends. {@link #close()} completes it.
     * <p>
     * Each write of close_notify is bounded by the close write timeout given at construction: a
     * client that keeps uploading but never reads leaves the send buffer full, and an unbounded
     * write would then hold the connection forever. At the deadline the socket is closed.
     */
    void closeNotifyAndDrain(ByteBuffer buffer) {
        writeLock.lock();
        try {
            engine.closeOutbound();
            netOutBuffer.clear();
            engine.wrap(EMPTY, netOutBuffer);
            netOutBuffer.flip();
            while (netOutBuffer.hasRemaining()) {
                BoundedWrites.writeWithTimeout(channel, channel, netOutBuffer, closeWriteTimeout);
            }
        } catch (IOException _) {
            return; // the connection is gone: nothing left to drain
        } finally {
            writeLock.unlock();
        }
        LingeringClose.halfCloseAndDrain(channel, buffer);
    }

    /**
     * Sends close_notify (best-effort) and closes the socket.
     * <p>
     * close_notify needs {@code writeLock}. If another thread holds it for longer than
     * {@link #CLOSE_LOCK_WAIT_MS} — typically a writer stalled in {@code channel.write} because
     * the peer stopped reading — close_notify is skipped and the socket is closed at once, which
     * wakes that writer (and any blocked reader) with an exception. Each write of close_notify
     * itself is bounded by the close write timeout given at construction, which is always positive
     * (a {@code null}, zero or negative {@code writeTimeout} falls back to {@link
     * #CLOSE_WRITE_FALLBACK}): a peer that stopped reading leaves the send buffer full, and the
     * socket is then closed without it. {@code close()} therefore never blocks behind stalled I/O.
     */
    @Override
    public void close() throws IOException {
        boolean locked = false;
        try {
            locked = writeLock.tryLock(CLOSE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        try {
            if (locked) {
                engine.closeOutbound();
                netOutBuffer.clear();
                engine.wrap(EMPTY, netOutBuffer);
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    BoundedWrites.writeWithTimeout(channel, channel, netOutBuffer, closeWriteTimeout);
                }
            }
        } catch (IOException _) {
            // best-effort: flush outbound close_notify (a timed-out write closed the socket)
        } finally {
            if (locked) writeLock.unlock();
            try {
                engine.closeInbound();
            } catch (SSLException _) {
                // best-effort: peer did not send close_notify
            }
            channel.close();
        }
    }
}
