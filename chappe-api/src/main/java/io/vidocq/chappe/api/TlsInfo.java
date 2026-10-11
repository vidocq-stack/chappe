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

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The TLS session of a connection, as negotiated by its handshake. Transport-neutral: it
 * describes the session, not the engine that produced it.
 *
 * <p>Chappe reads it once, when the handshake completes, and shares it with every request
 * of the connection (HTTP/1.1 keep-alive requests, HTTP/2 streams). It is immutable: the
 * session id and the chain are copied on the way in, and {@link #sessionId()} returns a copy.
 *
 * <p>Equality compares the content of {@code sessionId} (a plain record would compare the
 * array reference) and the certificates with {@link X509Certificate#equals}.
 *
 * @param protocol the negotiated protocol, for example {@code "TLSv1.3"}
 * @param cipherSuite the negotiated cipher suite, for example {@code "TLS_AES_128_GCM_SHA256"}
 * @param sessionId the session id; empty when the session has none ({@code null} is read as empty)
 * @param peerCertificates the client's certificate chain, the client's own certificate first;
 *     empty when the listener did not ask for one or the client sent none ({@code null} is read
 *     as empty)
 */
public record TlsInfo(String protocol, String cipherSuite, byte[] sessionId, List<X509Certificate> peerCertificates) {

    public TlsInfo {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(cipherSuite, "cipherSuite");
        sessionId = sessionId == null ? new byte[0] : sessionId.clone();
        peerCertificates = peerCertificates == null ? List.of() : List.copyOf(peerCertificates);
    }

    /** A copy of the session id, never {@code null}. */
    @Override
    public byte[] sessionId() {
        return sessionId.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TlsInfo other
                && protocol.equals(other.protocol)
                && cipherSuite.equals(other.cipherSuite)
                && Arrays.equals(sessionId, other.sessionId)
                && peerCertificates.equals(other.peerCertificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, cipherSuite, Arrays.hashCode(sessionId), peerCertificates);
    }

    /** Session id in hex and certificate subjects only: a certificate's own {@code toString} spans pages. */
    @Override
    public String toString() {
        return "TlsInfo[protocol=" + protocol
                + ", cipherSuite=" + cipherSuite
                + ", sessionId=" + HexFormat.of().formatHex(sessionId)
                + ", peerCertificates="
                + peerCertificates.stream()
                        .map(c -> c.getSubjectX500Principal().getName())
                        .toList()
                + "]";
    }
}
