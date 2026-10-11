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

import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLContext;

/**
 * Immutable HTTP server configuration.
 *
 * @param host listen address (default: {@code "0.0.0.0"})
 * @param port listen port (default: {@code 8080}, {@code 0} for an ephemeral port)
 * @param backlog TCP backlog size (default: {@code 1024})
 * @param readTimeout bound on each blocking read of a request body (default: 30s). Over HTTP/1.1
 *     it bounds each socket read, including the drain of a body the handler left unread: when no
 *     byte arrives for that long, the read throws {@link java.net.SocketTimeoutException} and the
 *     connection is closed; over TLS a read waits for a whole TLS record (up to 16 KiB), so each
 *     record must arrive within it. Over HTTP/2 it bounds the wait for the stream's next DATA frame:
 *     the read throws {@link java.net.SocketTimeoutException}, and the stream is reset once the
 *     response is sent. A call the router sends to a gRPC handler ({@code Router.grpc} or {@code
 *     grpcWeb}) is exempt, so a client or bidi streaming call may pause; a gRPC content-type on a
 *     request to any other handler exempts nothing. It bounds each read, not the whole body.
 *     Zero or negative disables it; a value too large for nanoseconds in a {@code long} is saturated
 *     (about 73 years). The wait for a next request is bounded by {@code idleTimeout}
 *     instead.
 * @param writeTimeout bound on a single blocking {@code channel.write()} call — how
 *     long the socket may refuse to accept bytes we are trying to send before the
 *     connection is considered dead (default: 30s). This is <strong>not</strong> an
 *     inactivity/idle bound on the response: {@code write} is only invoked — and only
 *     timed — while there is data to send, so a streamed response (e.g. Server-Sent
 *     Events) with long idle gaps between events, and no bytes in flight, is never
 *     affected by this timeout, however long the gap. Use {@code idleTimeout} to bound
 *     connection inactivity instead.
 * @param idleTimeout idle timeout before closing the connection (default: 60s)
 * @param maxRequestSize maximum request body size in bytes (default: 10 MiB)
 * @param maxHeaderSize maximum header size in bytes (default: 8 KiB)
 * @param maxDrainSize most bytes of a request body the handler left unread that the server reads
 *     and discards to keep an HTTP/1.1 connection (default: 1 MiB), counted in wire bytes: a
 *     chunked body counts its framing and trailers too. Past it the connection is closed instead,
 *     and an upgrade is refused. Must be zero or positive
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
        long maxDrainSize,
        SSLContext sslContext,
        List<String> alpnProtocols,
        Duration shutdownGracePeriod) {

    /**
     * Validates the components the server cannot run with.
     *
     * @throws IllegalArgumentException when {@code maxDrainSize} is negative
     */
    public ServerConfig {
        if (maxDrainSize < 0) throw new IllegalArgumentException("maxDrainSize must be >= 0, got " + maxDrainSize);
    }

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
            1024L * 1024,
            null,
            List.of("h2", "http/1.1"),
            Duration.ofSeconds(30));

    /** {@code true} if TLS is enabled. */
    public boolean tlsEnabled() {
        return sslContext != null;
    }
}
