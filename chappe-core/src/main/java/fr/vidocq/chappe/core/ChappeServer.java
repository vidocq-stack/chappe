package fr.vidocq.chappe.core;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.ServerConfig;
import fr.vidocq.chappe.api.ChappeException;
import fr.vidocq.chappe.http.ByteBufferPool;
import fr.vidocq.chappe.http.HttpConnection;
import fr.vidocq.chappe.http.SslHandler;
import fr.vidocq.chappe.http.h2.Http2Connection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implémentation du serveur HTTP Chappe — virtual threads, TLS, buffer pooling.
 * <p>
 * Chaque connexion acceptée est gérée par un virtual thread dédié.
 * Le protocole (HTTP/1.1 ou HTTP/2) est détecté par ALPN (TLS) ou
 * par byte sniffing (cleartext).
 */
final class ChappeServer implements Server {

    private static final int BUFFER_SIZE = 16384;
    private static final int MAX_POOL_SIZE = 1024;

    private final ServerConfig config;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ByteBufferPool bufferPool = new ByteBufferPool(BUFFER_SIZE, MAX_POOL_SIZE);

    private volatile ServerSocketChannel serverChannel;
    private volatile ExecutorService executor;
    private volatile Thread acceptThread;

    ChappeServer(ServerConfig config, Handler handler) {
        this.config = config;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new ChappeException.ServerException("Server is already running");
        }

        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
            serverChannel.configureBlocking(true);
        } catch (IOException e) {
            running.set(false);
            throw new ChappeException.ServerException(
                    "Failed to bind to " + config.host() + ":" + config.port(), e);
        }

        executor = Executors.newVirtualThreadPerTaskExecutor();

        acceptThread = Thread.ofVirtual()
                .name("chappe-accept")
                .start(this::acceptLoop);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // 1. Fermer le ServerSocketChannel — débloque accept()
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException _) {}
        }

        // 2. Attendre la fin de l'accept thread
        if (acceptThread != null) {
            try {
                acceptThread.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // 3. Graceful shutdown : interrompre les connexions bloquées sur I/O,
        //    puis attendre le drain dans le grace period
        if (executor != null) {
            executor.shutdownNow(); // interrompt les virtual threads bloqués sur channel.read()
            try {
                long graceMs = config.shutdownGracePeriod().toMillis();
                executor.awaitTermination(graceMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // 4. Libérer le pool de buffers
        bufferPool.clear();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int port() {
        var ch = serverChannel;
        if (ch == null || !ch.isOpen()) return config.port();
        try {
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        } catch (IOException _) {
            return config.port();
        }
    }

    @Override
    public InetSocketAddress localAddress() {
        var ch = serverChannel;
        if (ch == null || !ch.isOpen()) {
            return new InetSocketAddress(config.host(), config.port());
        }
        try {
            return (InetSocketAddress) ch.getLocalAddress();
        } catch (IOException _) {
            return new InetSocketAddress(config.host(), config.port());
        }
    }

    @Override
    public ServerConfig config() {
        return config;
    }

    // --- Accept loop ---

    private void acceptLoop() {
        while (running.get()) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                clientChannel.configureBlocking(true);
                // Configurer le timeout de lecture (idle/read timeout)
                int timeoutMs = (int) config.idleTimeout().toMillis();
                clientChannel.socket().setSoTimeout(timeoutMs);
                executor.submit(() -> handleConnection(clientChannel));
            } catch (AsynchronousCloseException _) {
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[chappe] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(SocketChannel channel) {
        ByteBuffer readBuffer = bufferPool.acquire();
        ByteBuffer writeBuffer = bufferPool.acquire();
        try {
            if (config.tlsEnabled()) {
                handleTlsConnection(channel, readBuffer, writeBuffer);
            } else {
                handleCleartextConnection(channel, readBuffer, writeBuffer);
            }
        } catch (IOException _) {
            try { channel.close(); } catch (IOException _2) {}
        } finally {
            bufferPool.release(readBuffer);
            bufferPool.release(writeBuffer);
        }
    }

    /**
     * Connexion TLS : handshake SSLEngine → ALPN → dispatch.
     */
    private void handleTlsConnection(SocketChannel channel, ByteBuffer readBuffer,
                                     ByteBuffer writeBuffer) throws IOException {
        SSLContext sslContext = config.sslContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);

        // Configurer ALPN
        var sslParams = engine.getSSLParameters();
        sslParams.setApplicationProtocols(
                config.alpnProtocols().toArray(String[]::new));
        engine.setSSLParameters(sslParams);

        // Handshake
        var sslHandler = new SslHandler(channel, engine);
        sslHandler.doHandshake();

        // Protocole négocié via ALPN
        String protocol = sslHandler.getAlpnProtocol();
        boolean isH2 = "h2".equals(protocol);

        if (isH2) {
            // HTTP/2 via ALPN — pas de pré-lecture, Http2Connection gère le preface
            readBuffer.clear();
            readBuffer.flip(); // vide
            new Http2Connection(sslHandler, sslHandler, handler, config, readBuffer).run();
        } else {
            // HTTP/1.1 — pré-lire pour le parser
            readBuffer.clear();
            int read = sslHandler.read(readBuffer);
            if (read == -1) {
                sslHandler.close();
                return;
            }
            readBuffer.flip();
            new HttpConnection(sslHandler, sslHandler, sslHandler, handler, config, readBuffer, writeBuffer).run();
        }
    }

    /**
     * Connexion cleartext : byte sniffing → dispatch.
     */
    private void handleCleartextConnection(SocketChannel channel, ByteBuffer readBuffer,
                                          ByteBuffer writeBuffer) throws IOException {
        readBuffer.clear();
        int read = channel.read(readBuffer);
        if (read == -1) {
            channel.close();
            return;
        }
        readBuffer.flip();

        if (read >= 6 && isHttp2Preface(readBuffer)) {
            new Http2Connection(channel, handler, config, readBuffer).run();
        } else {
            new HttpConnection(channel, channel, channel, handler, config, readBuffer, writeBuffer).run();
        }
    }

    private static boolean isHttp2Preface(ByteBuffer buf) {
        return buf.remaining() >= 6
                && buf.get(0) == 'P' && buf.get(1) == 'R' && buf.get(2) == 'I'
                && buf.get(3) == ' ' && buf.get(4) == '*' && buf.get(5) == ' ';
    }
}
