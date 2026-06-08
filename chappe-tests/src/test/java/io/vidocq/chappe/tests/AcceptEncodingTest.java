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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.chappe.api.AcceptEncoding;

import org.junit.jupiter.api.Test;

class AcceptEncodingTest {

    @Test
    void nullHeaderAcceptsIdentityOnly() {
        assertTrue(AcceptEncoding.accepts(null, "identity"));
        assertFalse(AcceptEncoding.accepts(null, "gzip"));
    }

    @Test
    void simpleGzipAccepted() {
        assertTrue(AcceptEncoding.accepts("gzip", "gzip"));
        assertTrue(AcceptEncoding.accepts("gzip, deflate", "gzip"));
        assertTrue(AcceptEncoding.accepts("br, gzip", "br"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(AcceptEncoding.accepts("GZIP", "gzip"));
        assertTrue(AcceptEncoding.accepts("Gzip,Deflate", "gzip"));
    }

    @Test
    void qZeroDisablesEncoding() {
        assertFalse(AcceptEncoding.accepts("gzip;q=0", "gzip"));
        assertFalse(AcceptEncoding.accepts("gzip;q=0.0", "gzip"));
    }

    @Test
    void qPositiveAcceptsEncoding() {
        assertTrue(AcceptEncoding.accepts("gzip;q=0.5", "gzip"));
        assertTrue(AcceptEncoding.accepts("gzip;q=1.0", "gzip"));
    }

    @Test
    void wildcardAcceptsAnyEncoding() {
        assertTrue(AcceptEncoding.accepts("*", "gzip"));
        assertTrue(AcceptEncoding.accepts("*", "br"));
        assertTrue(AcceptEncoding.accepts("identity, *", "br"));
    }

    @Test
    void wildcardWithQZeroSkipsAll() {
        assertFalse(AcceptEncoding.accepts("*;q=0", "gzip"));
    }

    @Test
    void specificEntryOverridesWildcard() {
        assertFalse(AcceptEncoding.accepts("*, gzip;q=0", "gzip"));
        assertTrue(AcceptEncoding.accepts("*;q=0, gzip", "gzip"));
    }

    @Test
    void identityAllowedByDefault() {
        assertTrue(AcceptEncoding.accepts("gzip", "identity"));
    }

    @Test
    void identityQZeroDisablesIdentity() {
        assertFalse(AcceptEncoding.accepts("gzip, identity;q=0", "identity"));
    }

    @Test
    void whitespaceTolerated() {
        assertTrue(AcceptEncoding.accepts(" gzip , deflate ;q=0.5 ", "gzip"));
        assertTrue(AcceptEncoding.accepts(" gzip ; q = 0.5 ", "gzip"));
    }

    @Test
    void firstParseReturnsAllEntries() {
        var entries = AcceptEncoding.parse("gzip;q=0.8, br;q=1.0, deflate");
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("gzip") && e.qvalue() == 0.8));
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("br") && e.qvalue() == 1.0));
    }
}
