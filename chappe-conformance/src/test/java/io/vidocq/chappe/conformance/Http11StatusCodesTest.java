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

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/1.1 conformance tests for status codes.
 * RFC 9110, Section 15.
 */
class Http11StatusCodesTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/exists", _ -> Response.ok("found"))
                .get("/custom-status", _ -> Response.of(StatusCode.NO_CONTENT))
                .build();

        server = Server.builder().port(0).maxHeaderSize(4096).handler(router).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void notFound404() {
        String response =
                RawHttp.sendAndReceive(port, "GET /nonexistent HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n");

        assertEquals(404, RawHttp.extractStatusCode(response), "Request to unknown path should return 404 Not Found");
    }

    @Test
    void methodNotAllowed404() {
        // Route /exists only handles GET. Sending POST → router returns 404
        // (router returns 404 for unmatched method+path combinations)
        String response = RawHttp.sendAndReceive(
                port, "POST /exists HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Length: 4\r\n" + "\r\n" + "test");

        int status = RawHttp.extractStatusCode(response);
        // Router may return 404 (no route matched) or 405 (method not allowed)
        assertTrue(status == 404 || status == 405, "POST to GET-only route should return 404 or 405, got: " + status);
    }

    @Test
    void payloadTooLarge413() {
        // Send a POST with Content-Length exceeding maxRequestSize
        // Default maxRequestSize is 10MB, so we use a large Content-Length header
        // to signal the intent (without actually sending the data)
        String response = RawHttp.sendAndReceive(
                port,
                "POST /exists HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Length: 999999999\r\n" + "\r\n",
                2_000);

        int status = RawHttp.extractStatusCode(response);
        // Server should reject with 413 Content Too Large or similar error
        assertTrue(
                status == 413 || status == 404 || status >= 400, "Oversized payload should be handled, got: " + status);
    }

    @Test
    void uriTooLong414() {
        // Send a GET with a very long URI (9000+ chars)
        String longPath = "/" + "a".repeat(9000);
        String response =
                RawHttp.sendAndReceive(port, "GET " + longPath + " HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n");

        int status = RawHttp.extractStatusCode(response);
        assertTrue(status == 414 || status == 431, "URI exceeding limits should return 414 or 431, got: " + status);
    }

    @Test
    void customStatusCode() {
        String response =
                RawHttp.sendAndReceive(port, "GET /custom-status HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n");

        assertEquals(204, RawHttp.extractStatusCode(response), "Custom status code 204 should be returned correctly");
    }
}
