package io.vidocq.chappe.core;

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
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import io.vidocq.chappe.api.ChappeException;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.http.ByteBufferPool;
import io.vidocq.chappe.http.HttpConnection;
import io.vidocq.chappe.http.SslHandler;
import io.vidocq.chappe.http.h2.Http2Connection;

/**
 * Implémentation du serveur HTTP Chappe — virtual threads, TLS, buffer pooling.
 * <p>
 * Chaque connexion acceptée est gérée par un virtual thread dédié.
 * Le protocole (HTTP/1.1 ou HTTP/2) est détecté par ALPN (TLS) ou
 * par byte sniffing (cleartext).
 */
final class ChappeServer implements Server {

    private static final System.Logger LOG = System.getLogger(ChappeServer.class.getName());

    private static final int BUFFER_SIZE = 16384;
    private static final int MAX_POOL_SIZE = 1024;

    private final ServerConfig config;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ByteBufferPool bufferPool = new ByteBufferPool(BUFFER_SIZE, MAX_POOL_SIZE);

    private final AtomicReference<ServerSocketChannel> serverChannel = new AtomicReference<>();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
    private final AtomicReference<Thread> acceptThread = new AtomicReference<>();

    ChappeServer(ServerConfig config, Handler handler) {
        this.config = config;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new ChappeException.ServerException("Server is already running");
        }

        ServerSocketChannel ch;
        try {
            ch = bindWithRetry();
        } catch (IOException e) {
            running.set(false);
            throw new ChappeException.ServerException("Failed to bind to " + config.host() + ":" + config.port(), e);
        }

        serverChannel.set(ch);
        executor.set(Executors.newVirtualThreadPerTaskExecutor());

        // unstarted() puis set() puis start() : garantit que acceptThread est visible
        // avant un éventuel stop() concurrent (sinon le join serait sauté)
        var thread = Thread.ofVirtual().name("chappe-accept").unstarted(this::acceptLoop);
        acceptThread.set(thread);
        thread.start();
    }

    @SuppressWarnings("java:S2095") // we open a socket and then keep it
    private ServerSocketChannel bindWithRetry() throws IOException {
        ServerSocketChannel ch = null;
        IOException lastBindEx = null;
        boolean done = false;
        for (int attempt = 0; attempt < 5 && !done; attempt++) {
            try {
                ch = ServerSocketChannel.open();
                ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
                setReusePort(ch);
                ch.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
                ch.configureBlocking(true);
                lastBindEx = null;
                done = true;
            } catch (IOException e) {
                lastBindEx = e;
                closeQuietly(ch);
                if (attempt < 4 && !sleepBeforeRetry(attempt)) {
                    done = true;
                }
            }
        }
        if (lastBindEx != null) {
            throw lastBindEx;
        }
        return ch;
    }

    private static void closeQuietly(ServerSocketChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException closeEx) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    () -> "Cleanup serverChannel échoué avant retry bind: " + closeEx.getMessage());
        }
    }

    private static boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * (attempt + 1));
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void setReusePort(ServerSocketChannel ch) throws IOException {
        try {
            ch.setOption(java.net.StandardSocketOptions.SO_REUSEPORT, true);
        } catch (UnsupportedOperationException _) {
            // SO_REUSEPORT non supporté sur cet OS — comportement attendu
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // 1. Fermer le ServerSocketChannel — débloque accept()
        var ch = serverChannel.getAndSet(null);
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "Fermeture serverChannel pendant stop: " + e.getMessage());
            }
        }

        // 2. Attendre la fin de l'accept thread
        var th = acceptThread.getAndSet(null);
        if (th != null) {
            try {
                th.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // 3. Graceful shutdown : interrompre les connexions bloquées sur I/O,
        //    puis attendre le drain dans le grace period
        var exec = executor.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow(); // interrompt les virtual threads bloqués sur channel.read()
            try {
                long graceMs = config.shutdownGracePeriod().toMillis();
                //noinspection ResultOfMethodCallIgnored
                exec.awaitTermination(graceMs, TimeUnit.MILLISECONDS);
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
        var ch = serverChannel.get();
        if (ch == null || !ch.isOpen()) return config.port();
        try {
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        } catch (IOException _) {
            return config.port();
        }
    }

    @Override
    public InetSocketAddress localAddress() {
        var ch = serverChannel.get();
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
        // Capture une fois — ces références ne changent pas pendant la vie de l'acceptLoop
        var ch = serverChannel.get();
        var exec = executor.get();
        if (ch == null || exec == null) return;

        int timeoutMs = (int) config.idleTimeout().toMillis();
        while (running.get()) {
            try {
                SocketChannel clientChannel = ch.accept();
                clientChannel.configureBlocking(true);
                // Optimisation cruciale pour les benchmarks de latence/throughput
                clientChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                // Configurer le timeout de lecture (idle/read timeout)
                clientChannel.socket().setSoTimeout(timeoutMs);
                exec.execute(() -> handleConnection(clientChannel));
            } catch (AsynchronousCloseException _) {
                break;
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(System.Logger.Level.WARNING, "Accept error", e);
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
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "I/O error sur connexion, fermeture: " + e.getMessage());
            try {
                channel.close();
            } catch (IOException _) {
                // best-effort, connexion déjà perdue
            }
        } finally {
            bufferPool.release(readBuffer);
            bufferPool.release(writeBuffer);
        }
    }

    /**
     * Connexion TLS : handshake SSLEngine → ALPN → dispatch.
     */
    private void handleTlsConnection(SocketChannel channel, ByteBuffer readBuffer, ByteBuffer writeBuffer)
            throws IOException {
        SSLContext sslContext = config.sslContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);

        // Configurer ALPN
        var sslParams = engine.getSSLParameters();
        sslParams.setApplicationProtocols(config.alpnProtocols().toArray(String[]::new));
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
    private void handleCleartextConnection(SocketChannel channel, ByteBuffer readBuffer, ByteBuffer writeBuffer)
            throws IOException {
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
                && buf.get(0) == 'P'
                && buf.get(1) == 'R'
                && buf.get(2) == 'I'
                && buf.get(3) == ' '
                && buf.get(4) == '*'
                && buf.get(5) == ' ';
    }
}
