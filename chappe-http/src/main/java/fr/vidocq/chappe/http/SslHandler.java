package fr.vidocq.chappe.http;

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

/**
 * Wraps a {@link SocketChannel} and {@link SSLEngine} to provide transparent TLS
 * read/write as {@link ReadableByteChannel} and {@link WritableByteChannel}.
 * <p>
 * Conçu pour les virtual threads — les opérations bloquent proprement.
 */
public final class SslHandler implements ReadableByteChannel, WritableByteChannel, Closeable {

    private final SocketChannel channel;
    private final SSLEngine engine;

    private final ByteBuffer netInBuffer;
    private final ByteBuffer netOutBuffer;
    private final ByteBuffer appInBuffer;

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    public SslHandler(SocketChannel channel, SSLEngine engine) {
        this.channel = channel;
        this.engine = engine;

        int packetSize = engine.getSession().getPacketBufferSize();
        int appSize = engine.getSession().getApplicationBufferSize();

        this.netInBuffer = ByteBuffer.allocate(packetSize);
        this.netOutBuffer = ByteBuffer.allocate(packetSize);
        this.appInBuffer = ByteBuffer.allocate(appSize);
        appInBuffer.flip(); // démarre vide
    }

    // -------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------

    public void doHandshake() throws IOException {
        engine.beginHandshake();
        HandshakeStatus hs = engine.getHandshakeStatus();

        while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
            switch (hs) {
                case NEED_UNWRAP -> {
                    int bytesRead = channel.read(netInBuffer);
                    if (bytesRead < 0) {
                        throw new IOException("Channel closed during TLS handshake (NEED_UNWRAP)");
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
                        throw new IOException("netOutBuffer overflow during handshake wrap");
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
    // ReadableByteChannel — ne retourne JAMAIS 0 (bloque sur virtual thread)
    // -------------------------------------------------------------------------

    @Override
    public int read(ByteBuffer dst) throws IOException {
        // Drainer les données déjà déchiffrées
        if (appInBuffer.hasRemaining()) {
            return drain(dst);
        }

        // Boucle jusqu'à obtenir des données applicatives ou EOF
        while (true) {
            appInBuffer.clear();

            // netInBuffer est en write mode (après compact précédent ou init)
            // Lire plus de données chiffrées depuis le réseau (bloque sur virtual thread)
            int bytesRead = channel.read(netInBuffer);
            if (bytesRead < 0 && netInBuffer.position() == 0) {
                return -1; // EOF et pas de données résiduelles
            }

            netInBuffer.flip();
            SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
            netInBuffer.compact();
            appInBuffer.flip();

            // Gérer les tâches post-handshake (TLS 1.3 key updates, etc.)
            handlePostUnwrap(result);

            switch (result.getStatus()) {
                case OK -> {
                    if (appInBuffer.hasRemaining()) return drain(dst);
                    // unwrap a consommé des octets protocole sans produire de données app — boucler
                }
                case BUFFER_UNDERFLOW -> {
                    // Enregistrement TLS incomplet — il faut plus de données réseau
                    // netInBuffer contient déjà le fragment partiel (après compact)
                    // La boucle relira depuis le channel (bloque sur virtual thread)
                    if (bytesRead < 0) return -1; // channel fermé, impossible d'obtenir plus
                }
                case BUFFER_OVERFLOW -> {
                    throw new IOException("appInBuffer overflow during read unwrap");
                }
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
     * Gère les événements post-unwrap (tasks déléguées, key updates TLS 1.3).
     */
    private void handlePostUnwrap(SSLEngineResult result) throws IOException {
        HandshakeStatus hs = result.getHandshakeStatus();
        while (hs == HandshakeStatus.NEED_TASK || hs == HandshakeStatus.NEED_WRAP) {
            if (hs == HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
            } else { // NEED_WRAP
                netOutBuffer.clear();
                engine.wrap(EMPTY, netOutBuffer);
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    channel.write(netOutBuffer);
                }
            }
            hs = engine.getHandshakeStatus();
        }
    }

    // -------------------------------------------------------------------------
    // WritableByteChannel
    // -------------------------------------------------------------------------

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
                case BUFFER_OVERFLOW -> {
                    throw new IOException("netOutBuffer overflow during write wrap");
                }
                case BUFFER_UNDERFLOW -> {
                    throw new IOException("Unexpected BUFFER_UNDERFLOW during write wrap");
                }
            }

            if (result.getStatus() == Status.CLOSED) break;
        }
        return totalWritten;
    }

    // -------------------------------------------------------------------------
    // ALPN
    // -------------------------------------------------------------------------

    public String getAlpnProtocol() {
        return engine.getApplicationProtocol();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        try {
            engine.closeOutbound();
            netOutBuffer.clear();
            SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
            netOutBuffer.flip();
            if (netOutBuffer.hasRemaining()) {
                while (netOutBuffer.hasRemaining()) {
                    channel.write(netOutBuffer);
                }
            }
        } catch (IOException _) {
            // Best effort
        } finally {
            try {
                engine.closeInbound();
            } catch (javax.net.ssl.SSLException _) {
                // Peer may not have sent close_notify
            }
            channel.close();
        }
    }
}
