package fr.vidocq.chappe.core;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.ServerConfig;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.List;

/**
 * Implémentation de {@link Server.Builder} — accumule la configuration
 * et produit un {@link ChappeServer}.
 */
final class ChappeServerBuilder implements Server.Builder {

    private String host = ServerConfig.DEFAULT.host();
    private int port = ServerConfig.DEFAULT.port();
    private int backlog = ServerConfig.DEFAULT.backlog();
    private Duration readTimeout = ServerConfig.DEFAULT.readTimeout();
    private Duration writeTimeout = ServerConfig.DEFAULT.writeTimeout();
    private Duration idleTimeout = ServerConfig.DEFAULT.idleTimeout();
    private long maxRequestSize = ServerConfig.DEFAULT.maxRequestSize();
    private int maxHeaderSize = ServerConfig.DEFAULT.maxHeaderSize();
    private SSLContext sslContext;
    private List<String> alpnProtocols = ServerConfig.DEFAULT.alpnProtocols();
    private Duration shutdownGracePeriod = ServerConfig.DEFAULT.shutdownGracePeriod();
    private Handler handler;

    @Override
    public Server.Builder port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public Server.Builder host(String host) {
        this.host = host;
        return this;
    }

    @Override
    public Server.Builder handler(Handler handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public Server.Builder backlog(int backlog) {
        this.backlog = backlog;
        return this;
    }

    @Override
    public Server.Builder readTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return this;
    }

    @Override
    public Server.Builder writeTimeout(Duration timeout) {
        this.writeTimeout = timeout;
        return this;
    }

    @Override
    public Server.Builder idleTimeout(Duration timeout) {
        this.idleTimeout = timeout;
        return this;
    }

    @Override
    public Server.Builder maxRequestSize(long bytes) {
        this.maxRequestSize = bytes;
        return this;
    }

    @Override
    public Server.Builder maxHeaderSize(int bytes) {
        this.maxHeaderSize = bytes;
        return this;
    }

    @Override
    public Server.Builder tls(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    @Override
    public Server.Builder alpnProtocols(String... protocols) {
        this.alpnProtocols = List.of(protocols);
        return this;
    }

    @Override
    public Server.Builder shutdownGracePeriod(Duration duration) {
        this.shutdownGracePeriod = duration;
        return this;
    }

    @Override
    public Server build() {
        if (handler == null) {
            throw new IllegalStateException("Handler is required — call handler(Handler) before build()");
        }
        var config = new ServerConfig(host, port, backlog, readTimeout, writeTimeout,
                idleTimeout, maxRequestSize, maxHeaderSize, sslContext, alpnProtocols,
                shutdownGracePeriod);
        return new ChappeServer(config, handler);
    }
}
