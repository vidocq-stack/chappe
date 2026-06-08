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
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

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

    public SslHandler(SocketChannel channel, SSLEngine engine) {
        this.channel = channel;
        this.engine = engine;

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

    public void doHandshake() throws IOException {
        engine.beginHandshake();
        HandshakeStatus hs = engine.getHandshakeStatus();

        while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
            switch (hs) {
                case NEED_UNWRAP -> {
                    int bytesRead = channel.read(netInBuffer);
                    if (bytesRead < 0) {
                        throw new IOException("Channel closed during TLS handshake");
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
        return totalWritten;
    }

    // -------------------------------------------------------------------------
    // ALPN & lifecycle
    // -------------------------------------------------------------------------

    public String getAlpnProtocol() {
        return engine.getApplicationProtocol();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            engine.closeOutbound();
            netOutBuffer.clear();
            engine.wrap(EMPTY, netOutBuffer);
            netOutBuffer.flip();
            if (netOutBuffer.hasRemaining()) {
                while (netOutBuffer.hasRemaining()) {
                    channel.write(netOutBuffer);
                }
            }
        } catch (IOException _) {
            // best-effort: flush outbound close_notify
        } finally {
            writeLock.unlock();
            try {
                engine.closeInbound();
            } catch (javax.net.ssl.SSLException _) {
                // best-effort: peer did not send close_notify
            }
            channel.close();
        }
    }
}
