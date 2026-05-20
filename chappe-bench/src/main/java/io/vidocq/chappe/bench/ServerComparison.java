package io.vidocq.chappe.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

/**
 * Benchmark comparatif de 5 serveurs HTTP avec un client NIO ultra-léger.
 * <p>
 * Mesure le throughput brut (req/s) avec 1, 4, 8 et 16 threads concurrents.
 * Chaque thread maintient une connexion keep-alive et envoie des requêtes GET en boucle.
 */
public class ServerComparison {

    private static final byte[] REQUEST_BYTES = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII);

    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(US_ASCII);

    // ─── BenchServer interface ─────────────────────────────────────────

    interface BenchServer {
        void start(int port) throws Exception;

        int port();

        void stop() throws Exception;
    }

    // ─── Server implementations ────────────────────────────────────────

    static final class ChappeServer implements BenchServer {
        private Server server;

        @Override
        public void start(int port) throws Exception {
            server = Server.builder().port(port).handler(_ -> Response.OK_TEXT).build();
            server.start();
        }

        @Override
        public int port() {
            return server.port();
        }

        @Override
        public void stop() throws Exception {
            server.stop();
        }
    }

    static final class HelidonServer implements BenchServer {
        private io.helidon.webserver.WebServer server;

        @Override
        public void start(int port) throws Exception {
            server = io.helidon.webserver.WebServer.builder()
                    .port(port)
                    .routing(r -> r.get("/", (req, res) -> res.send("ok")))
                    .build()
                    .start();
        }

        @Override
        public int port() {
            return server.port();
        }

        @Override
        public void stop() throws Exception {
            server.stop();
        }
    }

    static final class NettyServer implements BenchServer {
        private io.netty.channel.EventLoopGroup boss;
        private io.netty.channel.EventLoopGroup worker;
        private io.netty.channel.Channel channel;

        @Override
        public void start(int port) throws Exception {
            boss = new io.netty.channel.nio.NioEventLoopGroup(1);
            worker = new io.netty.channel.nio.NioEventLoopGroup();
            io.netty.bootstrap.ServerBootstrap b = new io.netty.bootstrap.ServerBootstrap();
            b.group(boss, worker)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                    .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                    .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                            ch.pipeline().addLast(new io.netty.handler.codec.http.HttpServerCodec());
                            ch.pipeline().addLast(new io.netty.handler.codec.http.HttpObjectAggregator(8192));
                            ch.pipeline()
                                    .addLast(
                                            new io.netty.channel.SimpleChannelInboundHandler<
                                                    io.netty.handler.codec.http.FullHttpRequest>() {
                                                @Override
                                                protected void channelRead0(
                                                        io.netty.channel.ChannelHandlerContext ctx,
                                                        io.netty.handler.codec.http.FullHttpRequest req) {
                                                    io.netty.buffer.ByteBuf content =
                                                            io.netty.buffer.Unpooled.copiedBuffer(
                                                                    "ok", io.netty.util.CharsetUtil.UTF_8);
                                                    io.netty.handler.codec.http.FullHttpResponse resp =
                                                            new io.netty.handler.codec.http.DefaultFullHttpResponse(
                                                                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                                                                    io.netty.handler.codec.http.HttpResponseStatus.OK,
                                                                    content);
                                                    resp.headers()
                                                            .set(
                                                                    io.netty.handler.codec.http.HttpHeaderNames
                                                                            .CONTENT_TYPE,
                                                                    "text/plain")
                                                            .setInt(
                                                                    io.netty.handler.codec.http.HttpHeaderNames
                                                                            .CONTENT_LENGTH,
                                                                    content.readableBytes());
                                                    if (io.netty.handler.codec.http.HttpUtil.isKeepAlive(req)) {
                                                        resp.headers()
                                                                .set(
                                                                        io.netty.handler.codec.http.HttpHeaderNames
                                                                                .CONNECTION,
                                                                        io.netty.handler.codec.http.HttpHeaderValues
                                                                                .KEEP_ALIVE);
                                                    }
                                                    ctx.writeAndFlush(resp);
                                                }
                                            });
                        }
                    });
            channel = b.bind(port).sync().channel();
        }

        @Override
        public int port() {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }

        @Override
        public void stop() throws Exception {
            channel.close().sync();
            boss.shutdownGracefully().sync();
            worker.shutdownGracefully().sync();
        }
    }

    static final class VertxServer implements BenchServer {
        private io.vertx.core.Vertx vertx;
        private io.vertx.core.http.HttpServer server;

        @Override
        public void start(int port) throws Exception {
            vertx = io.vertx.core.Vertx.vertx();
            server = vertx.createHttpServer().requestHandler(req -> req.response()
                    .putHeader("content-type", "text/plain")
                    .end("ok"));
            server.listen(port).toCompletionStage().toCompletableFuture().get();
        }

        @Override
        public int port() {
            return server.actualPort();
        }

        @Override
        public void stop() throws Exception {
            vertx.close().toCompletionStage().toCompletableFuture().get();
        }
    }

    static final class JettyServer implements BenchServer {
        private org.eclipse.jetty.server.Server server;
        private org.eclipse.jetty.server.ServerConnector connector;

        @Override
        public void start(int port) throws Exception {
            server = new org.eclipse.jetty.server.Server();
            connector = new org.eclipse.jetty.server.ServerConnector(server);
            connector.setPort(port);
            server.addConnector(connector);
            server.setHandler(new org.eclipse.jetty.server.Handler.Abstract.NonBlocking() {
                @Override
                public boolean handle(
                        org.eclipse.jetty.server.Request req,
                        org.eclipse.jetty.server.Response res,
                        org.eclipse.jetty.util.Callback callback) {
                    res.setStatus(200);
                    res.getHeaders().put("Content-Type", "text/plain");
                    org.eclipse.jetty.io.Content.Sink.write(res, true, "ok", callback);
                    return true;
                }
            });
            server.start();
        }

        @Override
        public int port() {
            return connector.getLocalPort();
        }

        @Override
        public void stop() throws Exception {
            server.stop();
        }
    }

    static final class JdkHttpServer implements BenchServer {
        private com.sun.net.httpserver.HttpServer server;

        @Override
        public void start(int port) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                byte[] body = "ok".getBytes(UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            server.start();
        }

        @Override
        public int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void stop() throws Exception {
            server.stop(0);
        }
    }

    // ─── NIO Client ────────────────────────────────────────────────────

    /**
     * Opens a keep-alive NIO SocketChannel and sends GET requests in a tight loop
     * until the stop flag is set. Returns the number of completed requests.
     */
    private static long clientLoop(int port, AtomicBoolean stop) throws IOException {
        ByteBuffer requestBuf = ByteBuffer.allocateDirect(REQUEST_BYTES.length);
        requestBuf.put(REQUEST_BYTES);

        ByteBuffer responseBuf = ByteBuffer.allocateDirect(4096);
        long count = 0;

        try (SocketChannel channel = SocketChannel.open()) {
            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(new InetSocketAddress("127.0.0.1", port));

            while (!stop.get()) {
                // Send request
                requestBuf.rewind();
                while (requestBuf.hasRemaining()) {
                    channel.write(requestBuf);
                }

                // Read response — find header end, parse Content-Length, read body
                responseBuf.clear();
                int totalRead = 0;
                int headerEnd = -1;
                int contentLength = -1;

                while (true) {
                    int n = channel.read(responseBuf);
                    if (n < 0) {
                        throw new IOException("Connection closed by server");
                    }
                    totalRead += n;

                    // Search for \r\n\r\n if not yet found
                    if (headerEnd < 0) {
                        headerEnd = findHeaderEnd(responseBuf, totalRead);
                        if (headerEnd >= 0) {
                            contentLength = parseContentLength(responseBuf, headerEnd);
                            if (contentLength < 0) {
                                // No Content-Length — assume we got the whole response
                                break;
                            }
                        }
                    }

                    if (headerEnd >= 0 && contentLength >= 0) {
                        int bodyStart = headerEnd + 4; // past \r\n\r\n
                        int bodyRead = totalRead - bodyStart;
                        if (bodyRead >= contentLength) {
                            break;
                        }
                    }

                    // Expand buffer if full
                    if (!responseBuf.hasRemaining()) {
                        break; // response should be tiny for "ok"
                    }
                }

                count++;
            }
        }

        return count;
    }

    /**
     * Searches for the \r\n\r\n sequence in the buffer.
     * Returns the index of the first \r, or -1 if not found.
     */
    private static int findHeaderEnd(ByteBuffer buf, int limit) {
        for (int i = 0; i <= limit - 4; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n' && buf.get(i + 2) == '\r' && buf.get(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses Content-Length from the response headers in the buffer.
     * Returns -1 if not found.
     */
    private static int parseContentLength(ByteBuffer buf, int headerEnd) {
        // Convert headers to string for simple parsing
        byte[] headerBytes = new byte[headerEnd];
        for (int i = 0; i < headerEnd; i++) {
            headerBytes[i] = buf.get(i);
        }
        String headers = new String(headerBytes, US_ASCII);
        String lower = headers.toLowerCase();
        int idx = lower.indexOf("content-length:");
        if (idx < 0) {
            return -1;
        }
        int start = idx + "content-length:".length();
        int end = headers.indexOf("\r\n", start);
        if (end < 0) {
            end = headers.length();
        }
        return Integer.parseInt(headers.substring(start, end).trim());
    }

    // ─── Benchmark runner ──────────────────────────────────────────────

    /**
     * Runs the benchmark for the given port with the specified number of threads.
     * Warmup: 3s, Measurement: 5s.
     *
     * @return total requests per second across all threads
     */
    private static double benchmark(int port, int threads) throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong totalRequests = new AtomicLong(0);

        // Warmup phase — 3 seconds
        stop.set(false);
        Thread[] warmupThreads = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            warmupThreads[i] = Thread.ofVirtual().start(() -> {
                try {
                    clientLoop(port, stop);
                } catch (IOException e) {
                    // ignore during warmup
                }
            });
        }
        Thread.sleep(3_000);
        stop.set(true);
        for (Thread t : warmupThreads) {
            t.join(2_000);
        }

        // Measurement phase — 5 seconds
        stop.set(false);
        totalRequests.set(0);
        Thread[] measureThreads = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            measureThreads[i] = Thread.ofVirtual().start(() -> {
                try {
                    long count = clientLoop(port, stop);
                    totalRequests.addAndGet(count);
                } catch (IOException e) {
                    System.err.println("  [warn] client error: " + e.getMessage());
                }
            });
        }
        Thread.sleep(5_000);
        stop.set(true);
        for (Thread t : measureThreads) {
            t.join(2_000);
        }

        return totalRequests.get() / 5.0;
    }

    // ─── Server factory ────────────────────────────────────────────────

    private static BenchServer createServer(int index) {
        return switch (index) {
            case 0 -> new ChappeServer();
            case 1 -> new HelidonServer();
            case 2 -> new JettyServer();
            case 3 -> new JdkHttpServer();
            case 4 -> new NettyServer();
            case 5 -> new VertxServer();
            default -> throw new IllegalArgumentException("Unknown server index: " + index);
        };
    }

    // ─── Markdown table printer ────────────────────────────────────────

    private static void printMarkdownTable(String[] serverNames, int[] threadCounts, double[][] results) {
        System.out.println("\n\n## Results (req/s)\n");

        // Header
        StringBuilder header = new StringBuilder("| Server         |");
        StringBuilder separator = new StringBuilder("|:---------------|");
        for (int t : threadCounts) {
            header.append(String.format(" %2d threads |", t));
            separator.append("-----------:|");
        }
        System.out.println(header);
        System.out.println(separator);

        // Find best per column
        double[] best = new double[threadCounts.length];
        for (int t = 0; t < threadCounts.length; t++) {
            for (int s = 0; s < serverNames.length; s++) {
                best[t] = Math.max(best[t], results[s][t]);
            }
        }

        // Rows
        for (int s = 0; s < serverNames.length; s++) {
            StringBuilder row = new StringBuilder(String.format("| %-14s |", serverNames[s]));
            for (int t = 0; t < threadCounts.length; t++) {
                String value = String.format("%,.0f", results[s][t]);
                if (results[s][t] == best[t]) {
                    value = "**" + value + "**";
                }
                row.append(String.format(" %10s |", value));
            }
            System.out.println(row);
        }

        System.out.println();
    }

    // ─── Main ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║   Server Comparison Benchmark — Chappe 0.1    ║");
        System.out.println("╚════════════════════════════════════════════════╝");

        String[] serverNames = {"Chappe", "Helidon SE 4", "Jetty 12", "JDK HttpServer", "Netty 4.2", "Vert.x 4.5"};
        int[] threadCounts = {1, 4, 8, 16};
        double[][] results = new double[serverNames.length][threadCounts.length];

        for (int s = 0; s < serverNames.length; s++) {
            BenchServer server = null;
            try {
                server = createServer(s);
                server.start(0); // ephemeral port
                int port = server.port();

                System.out.println("\n─── " + serverNames[s] + " (port " + port + ") ───");

                for (int t = 0; t < threadCounts.length; t++) {
                    results[s][t] = benchmark(port, threadCounts[t]);
                    System.out.printf("  %2d threads: %,.0f req/s%n", threadCounts[t], results[s][t]);
                }
            } catch (Exception e) {
                System.err.println("\n[ERROR] " + serverNames[s] + " failed: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                if (server != null) {
                    try {
                        server.stop();
                    } catch (Exception e) {
                        // ignore stop errors
                    }
                }
            }

            Thread.sleep(500); // let port release
        }

        // Print markdown table
        printMarkdownTable(serverNames, threadCounts, results);
    }
}
