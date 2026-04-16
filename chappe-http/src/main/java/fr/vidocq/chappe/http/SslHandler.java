package fr.vidocq.chappe.http;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Wraps a {@link SocketChannel} and {@link SSLEngine} to provide transparent TLS
 * read/write as {@link ReadableByteChannel} and {@link WritableByteChannel}.
 *
 * <p>Buffer conventions:
 * <ul>
 *   <li>{@code netInBuffer}  — encrypted data read from the network (write mode after read, flip before unwrap)</li>
 *   <li>{@code netOutBuffer} — encrypted data to be written to the network</li>
 *   <li>{@code appInBuffer}  — cleartext data produced by unwrap, ready to be consumed</li>
 * </ul>
 */
public final class SslHandler implements ReadableByteChannel, WritableByteChannel {

    private final SocketChannel channel;
    private final SSLEngine engine;

    private final ByteBuffer netInBuffer;
    private final ByteBuffer netOutBuffer;
    private final ByteBuffer appInBuffer;

    /** Empty buffer used as the plain-text source during NEED_WRAP handshake steps. */
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    public SslHandler(SocketChannel channel, SSLEngine engine) {
        this.channel = channel;
        this.engine = engine;

        int packetSize = engine.getSession().getPacketBufferSize();
        int appSize = engine.getSession().getApplicationBufferSize();

        this.netInBuffer = ByteBuffer.allocate(packetSize);
        this.netOutBuffer = ByteBuffer.allocate(packetSize);
        this.appInBuffer = ByteBuffer.allocate(appSize);
        // Start appInBuffer in a "drained" state so the first read() triggers network I/O
        this.appInBuffer.flip();
    }

    // -------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------

    /**
     * Performs the TLS handshake, following the {@link SSLEngine} state machine until
     * {@link HandshakeStatus#FINISHED} or {@link HandshakeStatus#NOT_HANDSHAKING}.
     */
    public void doHandshake() throws IOException {
        engine.beginHandshake();
        HandshakeStatus hs = engine.getHandshakeStatus();

        while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
            switch (hs) {
                case NEED_UNWRAP -> {
                    // Read encrypted data from the network
                    if (channel.read(netInBuffer) < 0) {
                        throw new IOException("Channel closed during TLS handshake (NEED_UNWRAP)");
                    }
                    netInBuffer.flip();
                    SSLEngineResult result;
                    do {
                        // Ensure appInBuffer is in write mode for unwrap
                        appInBuffer.clear();
                        result = engine.unwrap(netInBuffer, appInBuffer);
                        appInBuffer.flip(); // switch to read mode (data may be empty during handshake)
                        switch (result.getStatus()) {
                            case OK -> { /* continue */ }
                            case BUFFER_UNDERFLOW -> {
                                // Need more network data — compact and break inner loop
                                netInBuffer.compact();
                                break;
                            }
                            case BUFFER_OVERFLOW -> {
                                // appInBuffer too small — should not happen if sized correctly
                                throw new IOException("appInBuffer overflow during handshake unwrap");
                            }
                            case CLOSED -> throw new IOException("SSLEngine closed during handshake unwrap");
                        }
                        if (result.getStatus() == Status.BUFFER_UNDERFLOW) break;
                    } while (netInBuffer.hasRemaining() && result.getStatus() == Status.OK);

                    if (result.getStatus() != Status.BUFFER_UNDERFLOW) {
                        netInBuffer.compact();
                    }
                    hs = result.getHandshakeStatus();
                }
                case NEED_WRAP -> {
                    netOutBuffer.clear();
                    SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
                    netOutBuffer.flip();
                    switch (result.getStatus()) {
                        case OK, CLOSED -> { /* flush below */ }
                        case BUFFER_OVERFLOW ->
                                throw new IOException("netOutBuffer overflow during handshake wrap");
                        case BUFFER_UNDERFLOW ->
                                throw new IOException("Unexpected BUFFER_UNDERFLOW during handshake wrap");
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
                    // Renegotiation path: unwrap with empty netInBuffer
                    appInBuffer.clear();
                    SSLEngineResult result = engine.unwrap(EMPTY, appInBuffer);
                    appInBuffer.flip();
                    hs = result.getHandshakeStatus();
                }
                default -> throw new IOException("Unexpected handshake status: " + hs);
            }
        }
    }

    // -------------------------------------------------------------------------
    // ReadableByteChannel
    // -------------------------------------------------------------------------

    /**
     * Reads decrypted application data into {@code dst}.
     *
     * <p>Returns {@code -1} when the SSLEngine signals end-of-stream.
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        // Drain buffered cleartext first
        if (appInBuffer.hasRemaining()) {
            return drain(dst);
        }

        // Refill: read encrypted bytes from network
        appInBuffer.clear();
        netInBuffer.compact(); // preserve unprocessed bytes

        int bytesRead = channel.read(netInBuffer);
        if (bytesRead < 0 && !netInBuffer.hasRemaining()) {
            // Channel closed and nothing left to unwrap
            return -1;
        }

        netInBuffer.flip();

        SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
        netInBuffer.compact();
        appInBuffer.flip();

        return switch (result.getStatus()) {
            case OK -> drain(dst);
            case BUFFER_UNDERFLOW -> {
                // Not enough data yet; return 0 so caller can retry
                yield 0;
            }
            case BUFFER_OVERFLOW -> {
                // appInBuffer too small — allocate larger and retry is caller's responsibility
                throw new IOException("appInBuffer overflow during read unwrap");
            }
            case CLOSED -> -1;
        };
    }

    /** Copies as many bytes as possible from {@code appInBuffer} into {@code dst}. */
    private int drain(ByteBuffer dst) {
        int toTransfer = Math.min(appInBuffer.remaining(), dst.remaining());
        if (toTransfer == 0) return 0;
        int limit = appInBuffer.limit();
        appInBuffer.limit(appInBuffer.position() + toTransfer);
        dst.put(appInBuffer);
        appInBuffer.limit(limit);
        return toTransfer;
    }

    // -------------------------------------------------------------------------
    // WritableByteChannel
    // -------------------------------------------------------------------------

    /**
     * Encrypts and writes {@code src} to the underlying channel.
     *
     * @return the number of plaintext bytes consumed from {@code src}
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
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
                case BUFFER_OVERFLOW ->
                        throw new IOException("netOutBuffer overflow during write wrap");
                case BUFFER_UNDERFLOW ->
                        throw new IOException("Unexpected BUFFER_UNDERFLOW during write wrap");
            }

            if (result.getStatus() == Status.CLOSED) break;
        }
        return totalWritten;
    }

    // -------------------------------------------------------------------------
    // ALPN
    // -------------------------------------------------------------------------

    /**
     * Returns the ALPN protocol negotiated during the handshake, or an empty string if none.
     * Must be called after {@link #doHandshake()} completes.
     */
    public String getAlpnProtocol() {
        return engine.getApplicationProtocol();
    }

    // -------------------------------------------------------------------------
    // Channel lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    /**
     * Performs a clean TLS shutdown and closes the underlying channel.
     */
    @Override
    public void close() throws IOException {
        try {
            engine.closeOutbound();
            // Send close_notify alert
            netOutBuffer.clear();
            SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
            if (result.getStatus() != Status.CLOSED && netOutBuffer.position() > 0) {
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    channel.write(netOutBuffer);
                }
            }
        } catch (IOException ignored) {
            // Best-effort: proceed with channel close
        } finally {
            try {
                engine.closeInbound();
            } catch (javax.net.ssl.SSLException ignored) {
                // Peer may not have sent close_notify; ignore
            }
            channel.close();
        }
    }
}
