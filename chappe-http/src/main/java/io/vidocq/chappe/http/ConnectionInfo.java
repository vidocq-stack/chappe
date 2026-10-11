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
package io.vidocq.chappe.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import io.vidocq.chappe.api.TlsInfo;

/**
 * Connection-level metadata every request of one connection reports: HTTP/1.1 keep-alive
 * requests and HTTP/2 streams alike. Built once by the server engine when the connection is
 * ready, after the TLS handshake on a TLS listener, and never changed.
 *
 * @param remoteAddress the client's address, or {@code null} if the socket could not report it
 * @param localAddress the server's address, or {@code null} if the socket could not report it
 * @param tls the TLS session, or {@code null} on a cleartext connection
 */
public record ConnectionInfo(InetSocketAddress remoteAddress, InetSocketAddress localAddress, TlsInfo tls) {

    /** {@code true} on a TLS connection. */
    public boolean secure() {
        return tls != null;
    }

    /** Reads the socket's addresses; one the socket cannot report (already closed) stays {@code null}. */
    public static ConnectionInfo of(SocketChannel channel, TlsInfo tls) {
        return new ConnectionInfo(address(channel, true), address(channel, false), tls);
    }

    private static InetSocketAddress address(SocketChannel channel, boolean remote) {
        try {
            return (InetSocketAddress) (remote ? channel.getRemoteAddress() : channel.getLocalAddress());
        } catch (IOException _) {
            return null;
        }
    }
}
