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

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-023: the padding of a DATA frame is flow-controlled (RFC 9113 §6.9.1). The client counts it
 * against its windows, so a server that leaves it out credits too little back and a long padded
 * upload stalls.
 */
class Http2PaddedDataFlowControlTest {

    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** Reads the whole body; a failed read answers {@code failed:<exception>}. */
    private Response handle(Request request) {
        try {
            return Response.ok("read:" + request.body().asInputStream().readAllBytes().length);
        } catch (IOException e) {
            return Response.ok("failed:" + e.getClass().getSimpleName());
        }
    }

    @Test
    void aPaddedUploadLargerThanTheWindowCompletes() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            byte[] data = new byte[100];
            int frames = 2_000;
            int padLength = 255;
            int flow = 1 + data.length + padLength; // 356 flow-controlled bytes for 100 data bytes
            int streamWindow = 65_535;
            int connectionWindow = 65_535;
            for (int i = 0; i < frames; i++) {
                while (streamWindow < flow || connectionWindow < flow) {
                    h2.flush();
                    Frame f = h2.read(); // a SocketTimeoutException here means the upload stalled
                    assertNotEquals(TYPE_GOAWAY, f.type());
                    if (f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 1) streamWindow += f.windowIncrement();
                    if (f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 0) connectionWindow += f.windowIncrement();
                }
                h2.paddedData(1, i == frames - 1, data, padLength);
                streamWindow -= flow;
                connectionWindow -= flow;
            }
            h2.flush();
            assertEquals(new Reply("200", "read:200000"), h2.reply(1));
        }
    }
}
