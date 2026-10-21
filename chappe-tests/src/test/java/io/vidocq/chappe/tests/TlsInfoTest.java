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
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.TlsInfo;

import org.junit.jupiter.api.Test;

/** Contract of {@link TlsInfo}: defensive copies, content equality, absent values. */
class TlsInfoTest {

    private static final String SUITE = "TLS_AES_128_GCM_SHA256";

    @Test
    void sessionIdIsCopiedOnTheWayInAndOut() {
        byte[] id = {1, 2, 3};
        var info = new TlsInfo("TLSv1.3", SUITE, id, List.of());

        id[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, info.sessionId(), "the caller's array must not leak in");

        info.sessionId()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, info.sessionId(), "the returned array must be a copy");
    }

    @Test
    void peerCertificatesAreAnImmutableCopy() {
        var source = new ArrayList<X509Certificate>();
        var info = new TlsInfo("TLSv1.3", SUITE, new byte[0], source);

        source.add(null);
        assertTrue(info.peerCertificates().isEmpty(), "the caller's list must not leak in");
        var certificates = info.peerCertificates();
        assertThrows(UnsupportedOperationException.class, () -> certificates.add(null));
    }

    @Test
    void equalityComparesTheSessionIdContent() {
        var a = new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 2}, List.of());
        var b = new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 2}, List.of());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 3}, List.of()));
        assertNotEquals(a, new TlsInfo("TLSv1.2", SUITE, new byte[] {1, 2}, List.of()));
    }

    @Test
    void absentSessionIdAndChainBecomeEmpty() {
        var info = new TlsInfo("TLSv1.2", SUITE, null, null);

        assertEquals(0, info.sessionId().length);
        assertEquals(List.of(), info.peerCertificates());
    }

    @Test
    void protocolAndCipherSuiteAreRequired() {
        byte[] noSession = new byte[0];
        List<X509Certificate> noChain = List.of();
        assertThrows(NullPointerException.class, () -> new TlsInfo(null, SUITE, noSession, noChain));
        assertThrows(NullPointerException.class, () -> new TlsInfo("TLSv1.3", null, noSession, noChain));
    }

    @Test
    void toStringShowsTheSessionIdInHex() {
        var info = new TlsInfo("TLSv1.3", SUITE, new byte[] {0x0a, (byte) 0xff}, List.of());

        assertTrue(info.toString().contains("sessionId=0aff"), info.toString());
    }
}
