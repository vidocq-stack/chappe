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

/**
 * Whether a TLS listener asks clients for a certificate. Applied to every TLS handshake of
 * the listener; the client certificate is never requested later in the connection (no
 * renegotiation, and HTTP/2 forbids it). The trust material that validates the certificate
 * comes from the listener's {@link javax.net.ssl.SSLContext}.
 *
 * <p>The chain the client sent is exposed through {@link Request#tls()}
 * ({@link TlsInfo#peerCertificates()}).
 */
public enum ClientAuth {

    /** No certificate is requested; {@link TlsInfo#peerCertificates()} is always empty. */
    NONE,

    /**
     * A certificate is requested and optional: a client that sends none is accepted, with an
     * empty {@link TlsInfo#peerCertificates()}. A client that sends one the trust managers
     * reject fails the handshake, as with {@link #NEED}: optional does not mean unchecked.
     */
    WANT,

    /** A certificate is required: a client that sends none, or an untrusted one, fails the handshake. */
    NEED
}
