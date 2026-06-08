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
package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/2 flow control conformance tests (RFC 9113, Section 5.2).
 * <p>
 * Flow control operates at both the stream and connection level.
 * The default initial window size is 65,535 bytes. For larger responses,
 * WINDOW_UPDATE frames must be exchanged.
 */
class Http2FlowControlTest {

    private static final int LARGE_RESPONSE_SIZE = 200 * 1024; // 200 KB

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/large", _ -> {
                    // Generate a 200 KB response — exceeds default flow control window (64 KB)
                    var data = "A".repeat(LARGE_RESPONSE_SIZE);
                    return Response.ok(data);
                })
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();

        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * RFC 9113 Section 5.2: A 200 KB response exceeds the default initial window
     * size (65,535 bytes). The server and client must exchange WINDOW_UPDATE frames
     * to deliver the complete response.
     * <p>
     * Using HttpClient which handles WINDOW_UPDATE automatically — we verify
     * the full response is received correctly.
     */
    @Test
    void largeResponseFlowControl() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/large"))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                LARGE_RESPONSE_SIZE,
                response.body().length(),
                "Full 200 KB response should be received (requires WINDOW_UPDATE flow control)");
        // Verify content integrity
        assertTrue(response.body().chars().allMatch(c -> c == 'A'), "All bytes should be 'A'");
    }
}
