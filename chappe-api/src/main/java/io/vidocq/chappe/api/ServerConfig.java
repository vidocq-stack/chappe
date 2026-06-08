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
