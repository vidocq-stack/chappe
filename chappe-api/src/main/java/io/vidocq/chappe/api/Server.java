/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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

        /**
         * Bound on a single blocking write — how long the socket may refuse to accept
         * bytes we are trying to send. Does not bound idle time on a response with
         * nothing in flight (e.g. gaps between Server-Sent Events); see {@link
         * ServerConfig#writeTimeout()}.
         */
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
