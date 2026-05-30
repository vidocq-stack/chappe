package io.vidocq.chappe.api;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ServiceLoader;

import javax.net.ssl.SSLContext;

/**
 * HTTP server — entry point for starting and stopping the server.
 * <p>
 * Implements {@link AutoCloseable} to support {@code try-with-resources}.
 *
 * <pre>{@code
 * try (var server = Server.builder()
 *         .port(8080)
 *         .handler(myRouter)
 *         .build()) {
 *     server.start();
 *     // The server handles requests...
 * } // stop() called automatically
 * }</pre>
 */
public interface Server extends AutoCloseable {

    /** Starts the server (bind + accept). Non-blocking. */
    void start();

    /** Gracefully stops the server (drains active connections). */
    void stop();

    /** Equivalent to {@link #stop()}. */
    @Override
    default void close() {
        stop();
    }

    /** {@code true} if the server is running. */
    boolean isRunning();

    /** Actual port the server is listening on (useful with port 0). */
    int port();

    /** Full local address of the server. */
    InetSocketAddress localAddress();

    /** Server configuration. */
    ServerConfig config();

    /** Creates a new builder via {@link ServiceLoader} ({@link ServerProvider}). */
    static Builder builder() {
        return ServiceLoader.load(ServerProvider.class)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("No ServerProvider found — add chappe-core to the module path"))
                .newBuilder();
    }

    /** Fluent builder for configuring and constructing a {@link Server}. */
    interface Builder {

        Builder port(int port);

        Builder host(String host);

        Builder handler(Handler handler);

        Builder backlog(int backlog);

        Builder readTimeout(Duration timeout);

        Builder writeTimeout(Duration timeout);

        Builder idleTimeout(Duration timeout);

        Builder maxRequestSize(long bytes);

        Builder maxHeaderSize(int bytes);

        /** Configures TLS with the given {@link SSLContext}. */
        Builder tls(SSLContext sslContext);

        /** ALPN protocols to negotiate (default: {@code ["h2", "http/1.1"]}). */
        Builder alpnProtocols(String... protocols);

        /** Drain delay before forced closure on stop (default: 30s). */
        Builder shutdownGracePeriod(Duration duration);

        Server build();
    }
}
