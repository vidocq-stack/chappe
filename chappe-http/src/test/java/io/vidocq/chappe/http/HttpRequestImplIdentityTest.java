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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** CHAPPE-035: presence rules of {@code connectionId()} and {@code streamId()}. */
class HttpRequestImplIdentityTest {

    @Test
    void anUnknownConnectionIdIsEmpty() {
        var request = new HttpRequestImpl();
        assertTrue(request.connectionId().isEmpty(), "before any connection info");
        request.initConnectionInfo(new ConnectionInfo(0, null, null, null));
        assertTrue(request.connectionId().isEmpty(), "id 0 means unknown");
        assertTrue(request.streamId().isEmpty());
    }

    @Test
    void aPositiveConnectionIdIsReportedAndSurvivesReset() {
        var request = new HttpRequestImpl();
        request.initConnectionInfo(new ConnectionInfo(7, null, null, null));
        request.initStreamId(3);
        request.reset();
        assertEquals(7, request.connectionId().getAsLong());
        assertEquals(3, request.streamId().getAsInt());
    }
}
