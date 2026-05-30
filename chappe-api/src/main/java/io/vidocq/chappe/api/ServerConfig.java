package io.vidocq.chappe.api;

import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLContext;

/**
 * Immutable HTTP server configuration.
 *
 * @param host listen address (default: {@code "0.0.0.0"})
 * @param port listen port (default: {@code 8080}, {@code 0} for an ephemeral port)
 * @param backlog TCP backlog size (default: {@code 1024})
 * @param readTimeout maximum read timeout (default: 30s)
 * @param writeTimeout maximum write timeout (default: 30s)
 * @param idleTimeout idle timeout before closing the connection (default: 60s)
 * @param maxRequestSize maximum request body size in bytes (default: 10 MiB)
 * @param maxHeaderSize maximum header size in bytes (default: 8 KiB)
 * @param sslContext SSL/TLS context ({@code null} = cleartext)
 * @param alpnProtocols negotiated ALPN protocols (default: {@code ["h2", "http/1.1"]})
 * @param shutdownGracePeriod drain timeout before forced shutdown (default: 30s)
 */
public record ServerConfig(
        String host,
        int port,
        int backlog,
        Duration readTimeout,
        Duration writeTimeout,
        Duration idleTimeout,
        long maxRequestSize,
        int maxHeaderSize,
        SSLContext sslContext,
        List<String> alpnProtocols,
        Duration shutdownGracePeriod) {

    /** Default configuration (cleartext). */
    public static final ServerConfig DEFAULT = new ServerConfig(
            "0.0.0.0",
            8080,
            1024,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            10L * 1024 * 1024,
            8192,
            null,
            List.of("h2", "http/1.1"),
            Duration.ofSeconds(30));

    /** {@code true} if TLS is enabled. */
    public boolean tlsEnabled() {
        return sslContext != null;
    }
}
