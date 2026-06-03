package io.vidocq.chappe.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Chappe HTTP server implementation — virtual threads, TLS, buffer pooling.
 * <p>
 * Each accepted connection is handled by a dedicated virtual thread.
 * The protocol (HTTP/1.1 or HTTP/2) is detected via ALPN (TLS) or
 * by byte sniffing (cleartext).
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

    /**
     * Live client sockets currently being handled. Required so {@link #stop()} can
     * force-close them <em>before</em> the executor is shut down, which short-circuits
     * any in-flight {@code channel.write()} via {@link java.nio.channels.ClosedChannelException}.
     *
     * <p>Without this, a virtual thread that was mid-write when {@code stop()} runs
     * could finish its write <em>after</em> the listening port had been freed and
     * recycled by the OS for a sibling {@code Server} instance in the same JVM
     * (typical in test forks). The leftover bytes then surfaced as a fake response
     * on the next test's socket — observed as 401/200/EOF/broken-pipe symptoms in
     * CHAPPE-004.
     */
    private final Set<SocketChannel> activeConnections = ConcurrentHashMap.newKeySet();

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

        // unstarted() then set() then start(): guarantees acceptThread visibility
        // before a concurrent stop() call (otherwise join could be skipped)
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
                // SO_REUSEADDR only: allows rebinding a port still in TIME_WAIT on restart.
                // SO_REUSEPORT is deliberately NOT set — Chappe has a single accept loop per
                // Server, so kernel load-balancing across sockets buys nothing, and it would
                // let two live Server instances in the same JVM share one port and steal each
                // other's connections (cross-server contamination — see CHAPPE-004).
                ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
                ch.bind(new InetSocketAddress(config.host(), resolveBindPort()), config.backlog());
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

    /**
     * Resolve the port to bind to. For a fixed port this is simply {@code config.port()}.
     * <p>
     * For an ephemeral request ({@code port == 0}) we first bind a throwaway socket to
     * {@code 127.0.0.1:0}: the kernel only hands back a port that is free on the loopback
     * address, skipping any port a foreign process already holds there. We then bind the
     * real listener (on {@code config.host()}, typically the {@code 0.0.0.0} wildcard) to
     * that port. A plain wildcard {@code bind(0.0.0.0, 0)} can otherwise return a port a
     * foreign service already owns on {@code 127.0.0.1}/{@code ::1} — a different address
     * family, so the two binds silently coexist — and a loopback client is then sometimes
     * routed to the foreign socket instead of ours. This surfaced as tests receiving
     * responses from an IDE's built-in server or a local DB driver (CHAPPE-004).
     */
    private int resolveBindPort() throws IOException {
        if (config.port() != 0) {
            return config.port();
        }
        try (ServerSocketChannel probe = ServerSocketChannel.open()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) probe.getLocalAddress()).getPort();
        }
    }

    private static void closeQuietly(ServerSocketChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException closeEx) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    () -> "Cleanup serverChannel failed before bind retry: " + closeEx.getMessage());
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

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // 1. Close the ServerSocketChannel — unblocks accept() and prevents new
        //    connections from being accepted while we drain the existing ones.
        var ch = serverChannel.getAndSet(null);
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "Closing serverChannel during stop: " + e.getMessage());
            }
        }

        // 2. Wait for accept thread termination
        var th = acceptThread.getAndSet(null);
        if (th != null) {
            try {
                th.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // 3. Force-close every live client socket. This is what makes any
        //    in-flight `channel.write()` fail with ClosedChannelException —
        //    interrupt alone (via shutdownNow below) does NOT cancel a blocking
        //    write, only closing the channel does. Without this step, a virtual
        //    thread mid-write could finish *after* the listen port has been
        //    recycled by the OS to a sibling Server in the same JVM, leaking
        //    bytes onto another test's socket (CHAPPE-004).
        for (SocketChannel conn : activeConnections) {
            try {
                conn.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "Closing active client during stop: " + e.getMessage());
            }
        }
        activeConnections.clear();

        // 4. Graceful shutdown of handler executor: interrupt the (now writable-
        //    failing) virtual threads, then wait for draining during the grace
        //    period — by now every blocked write has thrown.
        var exec = executor.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow();
            try {
                long graceMs = config.shutdownGracePeriod().toMillis();
                //noinspection ResultOfMethodCallIgnored
                exec.awaitTermination(graceMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // 5. Release the buffer pool
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
        // Capture once — these references do not change during the acceptLoop lifetime
        var ch = serverChannel.get();
        var exec = executor.get();
        if (ch == null || exec == null) return;

        int timeoutMs = (int) config.idleTimeout().toMillis();
        while (running.get()) {
            try {
                SocketChannel clientChannel = ch.accept();
                clientChannel.configureBlocking(true);
                // Critical optimization for latency/throughput benchmarks
                clientChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                // Configure read timeout (idle/read timeout)
                clientChannel.socket().setSoTimeout(timeoutMs);
                // Track for stop()-time force-close. Registering BEFORE submitting
                // avoids a race where stop() runs between submit and the handler's
                // own add: the handler removes itself in its finally block.
                activeConnections.add(clientChannel);
                // Race: stop() may have already iterated the live set and is now in
                // the middle of shutdownNow(). Re-check `running`: if stop() has
                // started, drop the freshly accepted client ourselves so its bytes
                // can never reach the OS layer of a sibling Server (CHAPPE-004).
                if (!running.get()) {
                    activeConnections.remove(clientChannel);
                    try {
                        clientChannel.close();
                    } catch (IOException _) {
                        // best-effort
                    }
                    break;
                }
                try {
                    exec.execute(() -> handleConnection(clientChannel));
                } catch (java.util.concurrent.RejectedExecutionException _) {
                    // Executor was shut down between our `running` check and submit.
                    // Same remediation: deregister + close.
                    activeConnections.remove(clientChannel);
                    try {
                        clientChannel.close();
                    } catch (IOException _) {
                        // best-effort
                    }
                    break;
                }
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
            LOG.log(System.Logger.Level.DEBUG, () -> "I/O error on connection, closing: " + e.getMessage());
            try {
                channel.close();
            } catch (IOException _) {
                // best-effort, connection already lost
            }
        } finally {
            // Deregister BEFORE returning the buffers so stop() never sees a
            // channel that this thread is about to release a buffer for.
            activeConnections.remove(channel);
            bufferPool.release(readBuffer);
            bufferPool.release(writeBuffer);
        }
    }

    /**
     * TLS connection: SSLEngine handshake → ALPN → dispatch.
     */
    private void handleTlsConnection(SocketChannel channel, ByteBuffer readBuffer, ByteBuffer writeBuffer)
            throws IOException {
        SSLContext sslContext = config.sslContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);

        // Configure ALPN
        var sslParams = engine.getSSLParameters();
        sslParams.setApplicationProtocols(config.alpnProtocols().toArray(String[]::new));
        engine.setSSLParameters(sslParams);

        // Handshake
        var sslHandler = new SslHandler(channel, engine);
        sslHandler.doHandshake();

        // Protocol negotiated via ALPN
        String protocol = sslHandler.getAlpnProtocol();
        boolean isH2 = "h2".equals(protocol);

        if (isH2) {
            // HTTP/2 via ALPN — no pre-read, Http2Connection handles the preface
            readBuffer.clear();
            readBuffer.flip(); // empty
            new Http2Connection(sslHandler, sslHandler, handler, config, readBuffer).run();
        } else {
            // HTTP/1.1 — pre-read for the parser
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
     * Cleartext connection: byte sniffing → dispatch.
     */
    private void handleCleartextConnection(SocketChannel channel, ByteBuffer readBuffer, ByteBuffer writeBuffer)
            throws IOException {
        readBuffer.clear();
        int read = channel.read(readBuffer);
        if (read == -1) {
            channel.close();
            return;
        }
        // Protocol sniffing needs the first 6 bytes ("PRI * ") to recognise the
        // HTTP/2 cleartext connection preface. A single read() is NOT guaranteed
        // to deliver them: TCP may split the 24-byte preface across segments,
        // especially under load. Keep reading while the bytes so far are still a
        // viable preface prefix and we have fewer than 6 of them. Bail out early
        // (route to HTTP/1.1) the moment a byte diverges from the preface — so a
        // real HTTP/1.1 request pays no extra latency. The socket SO_TIMEOUT
        // bounds the wait; EOF ends it too. Without this loop a fragmented
        // preface was misrouted to the HTTP/1.1 parser, which then choked on the
        // binary SETTINGS frame and closed mid-handshake (client saw EOF) —
        // CHAPPE-004.
        while (readBuffer.position() < H2_PREFACE_PROBE.length && matchesPrefacePrefix(readBuffer)) {
            if (channel.read(readBuffer) == -1) {
                break;
            }
        }
        readBuffer.flip();

        if (isHttp2Preface(readBuffer)) {
            new Http2Connection(channel, handler, config, readBuffer).run();
        } else {
            new HttpConnection(channel, channel, channel, handler, config, readBuffer, writeBuffer).run();
        }
    }

    /** The 6 bytes that uniquely identify the start of the HTTP/2 connection preface. */
    private static final byte[] H2_PREFACE_PROBE = {'P', 'R', 'I', ' ', '*', ' '};

    /** True while every byte read so far is still consistent with the HTTP/2 preface prefix. */
    private static boolean matchesPrefacePrefix(ByteBuffer buf) {
        int n = Math.min(buf.position(), H2_PREFACE_PROBE.length);
        for (int i = 0; i < n; i++) {
            if (buf.get(i) != H2_PREFACE_PROBE[i]) {
                return false;
            }
        }
        return true;
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
