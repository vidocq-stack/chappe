package io.vidocq.chappe.http;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps a {@link SocketChannel} and {@link SSLEngine} to provide transparent TLS
 * read/write as {@link ReadableByteChannel} and {@link WritableByteChannel}.
 * <p>
 * Thread-safe pour read/write concurrents (HTTP/2 : frame loop lit sur un thread,
 * stream threads écrivent des réponses).
 * <ul>
 *   <li>Les opérations de lecture (unwrap) sont protégées par {@code readLock}</li>
 *   <li>Les opérations d'écriture (wrap) sont protégées par {@code writeLock}</li>
 *   <li>SSLEngine supporte unwrap/wrap concurrents si les buffers sont séparés</li>
 * </ul>
 */
public final class SslHandler implements ReadableByteChannel, WritableByteChannel, Closeable {

    private final SocketChannel channel;
    private final SSLEngine engine;

    // Buffers de lecture (unwrap) — accédés uniquement sous readLock
    private final ByteBuffer netInBuffer;
    private final ByteBuffer appInBuffer;

    // Buffer d'écriture (wrap) — accédé uniquement sous writeLock
    private final ByteBuffer netOutBuffer;

    // Verrous séparés pour read/write concurrent
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
    // Handshake (single-threaded, avant read/write concurrents)
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
                    } while (netInBuffer.hasRemaining() && result.getStatus() == Status.OK
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
            appInBuffer.clear();

            int bytesRead = channel.read(netInBuffer);
            if (bytesRead < 0 && netInBuffer.position() == 0) {
                return -1;
            }

            netInBuffer.flip();
            SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
            netInBuffer.compact();
            appInBuffer.flip();

            // Post-handshake events (TLS 1.3 key updates)
            handlePostUnwrap(result);

            switch (result.getStatus()) {
                case OK -> {
                    if (appInBuffer.hasRemaining()) return drain(dst);
                }
                case BUFFER_UNDERFLOW -> {
                    if (bytesRead < 0) return -1;
                }
                case BUFFER_OVERFLOW -> throw new IOException("appInBuffer overflow");
                case CLOSED -> { return -1; }
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
     * Post-unwrap : gère les tâches déléguées et les key updates TLS 1.3.
     * Les wraps nécessaires sont faits sous writeLock.
     */
    private void handlePostUnwrap(SSLEngineResult result) throws IOException {
        HandshakeStatus hs = result.getHandshakeStatus();
        while (hs == HandshakeStatus.NEED_TASK || hs == HandshakeStatus.NEED_WRAP) {
            if (hs == HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
            } else { // NEED_WRAP — doit utiliser writeLock
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
        } finally {
            writeLock.unlock();
            try { engine.closeInbound(); } catch (javax.net.ssl.SSLException _) {}
            channel.close();
        }
    }
}
