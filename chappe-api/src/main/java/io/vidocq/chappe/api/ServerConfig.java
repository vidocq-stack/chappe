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
import java.util.Objects;

import javax.net.ssl.SSLContext;

/**
 * Immutable HTTP server configuration.
 *
 * @param host listen address (default: {@code "0.0.0.0"})
 * @param port listen port (default: {@code 8080}). {@code 0} asks for an ephemeral port: the
 *     configuration keeps {@code 0} and the bound port is read from {@code Server.port()} once the
 *     server has started. On a specific host the kernel picks the port when the listener binds, with
 *     no race; on a wildcard host (such as the default {@code 0.0.0.0}) a free loopback port is
 *     probed first and a rare lost race is repaired by retrying the bind
 * @param backlog TCP backlog size (default: {@code 1024})
 * @param readTimeout bound on each blocking read of a request body (default: 30s). Over HTTP/1.1
 *     it bounds each socket read, including the drain of a body the handler left unread: when no
 *     byte arrives for that long, the read throws {@link java.net.SocketTimeoutException} and the
 *     connection is closed; over TLS a read waits for a whole TLS record (up to 16 KiB), so each
 *     record must arrive within it. Over HTTP/2 it bounds the wait for the stream's next DATA frame:
 *     the read throws {@link java.net.SocketTimeoutException}, and, if the request body has not
 *     ended, the stream is reset once the response is sent. A call the router sends to a gRPC
 *     handler ({@code Router.grpc}), or to a gRPC-Web handler ({@code Router.grpcWeb}) in binary
 *     mode, is exempt, so a client or bidi streaming call may pause; a gRPC-Web call in text mode,
 *     whose whole body is read at once, is not. A gRPC content-type on a request to any other
 *     handler exempts nothing. It bounds each read, not the whole body.
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
 *     connection inactivity instead. On HTTP/2 it bounds each write of a flushed frame
 *     buffer (up to 16 KiB), and on expiry the whole multiplexed connection is closed, with
 *     every stream on it. Zero or negative: no bound for responses; the writes of a TLS close
 *     (close_notify, the alert of a rejected handshake) stay bounded, by 2 s in that case.
 * @param idleTimeout idle timeout before closing the connection (default: 60s). It also bounds the
 *     whole TLS handshake as one total deadline, not a per-read one: a client that has not
 *     completed its handshake {@code idleTimeout} after the server started it is disconnected.
 *     Use a positive value: zero or negative removes the handshake deadline but does not disable
 *     the idle bound, and a connection waiting for a request may then be closed at once. A very
 *     large value is saturated.
 * @param maxRequestSize maximum request body size in bytes (default: 10 MiB), on HTTP/1.1 and
 *     HTTP/2. A call routed to a gRPC handler, or to a gRPC-Web handler in binary mode, is not
 *     capped: nothing bounds its total, and each message is bounded only when the handler reads it,
 *     by the receive message size of the gRPC layer. A gRPC-Web call in text mode is capped
 * @param maxHeaderSize maximum header size in bytes (default: 8 KiB). On HTTP/1.1, the raw bytes of
 *     the request header section; past it, {@code 431}. On HTTP/2, it sets the advertised
 *     {@code SETTINGS_MAX_HEADER_LIST_SIZE} to {@code maxHeaderSize + 32 * 100} (11,392 by default),
 *     measured as RFC 9113 §6.5.2 does. A header list past it is still decoded, so the HPACK
 *     context stays intact, and its stream alone gets {@code 431}; trailers past it reset their
 *     stream. The hard caps end the connection with {@code GOAWAY(ENHANCE_YOUR_CALM)}: a header
 *     block of more than {@code max(4 * limit, 64 KiB)} encoded bytes, or a list of more than
 *     {@code max(8 * limit, 256 KiB)} decoded bytes. The HTTP/2 setting is advisory: compliant
 *     clients do not send a larger list.
 * @param maxDrainSize most bytes of a request body the handler left unread that the server reads
 *     and discards to keep an HTTP/1.1 connection (default: 1 MiB), counted in wire bytes: a
 *     chunked body counts its framing and trailers too. Past it the connection is closed instead,
 *     and an upgrade is refused. Must be zero or positive
 * @param sslContext SSL/TLS context ({@code null} = cleartext)
 * @param clientAuth whether TLS clients are asked for a certificate (default: {@link ClientAuth#NONE}); anything but {@code NONE} requires an {@code sslContext}
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
        ClientAuth clientAuth,
        List<String> alpnProtocols,
        Duration shutdownGracePeriod) {

    /**
     * Validates the components the server cannot run with.
     *
     * @throws NullPointerException when {@code clientAuth} is {@code null}
     * @throws IllegalArgumentException when {@code clientAuth} is not {@link ClientAuth#NONE} and
     *     {@code sslContext} is {@code null}, or when {@code maxDrainSize} is negative
     */
    public ServerConfig {
        Objects.requireNonNull(clientAuth, "clientAuth");
        if (sslContext == null && clientAuth != ClientAuth.NONE) {
            throw new IllegalArgumentException("clientAuth " + clientAuth + " requires TLS: configure an SSLContext");
        }
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
            ClientAuth.NONE,
            List.of("h2", "http/1.1"),
            Duration.ofSeconds(30));

    /** {@code true} if TLS is enabled. */
    public boolean tlsEnabled() {
        return sslContext != null;
    }
}
