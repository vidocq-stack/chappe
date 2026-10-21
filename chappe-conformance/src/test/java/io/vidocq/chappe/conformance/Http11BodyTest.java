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

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * HTTP/1.1 conformance tests for message bodies.
 * RFC 9112, Sections 6-7.
 */
class Http11BodyTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .post("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .get("/nobody", _ -> Response.ok("no-body-expected"))
                .build();

        server = Server.builder().port(0).handler(router).build();
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
    void fixedLengthBody() {
        String body = "Hello, Chappe! This is a fixed-length body.";
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n"
                        + "Content-Length: "
                        + body.length() + "\r\n" + "\r\n"
                        + body);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(body, RawHttp.extractBody(response));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodies")
    void requestBody(String name, String request, String expectedBody) {
        String response = RawHttp.sendAndReceive(port, request);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(expectedBody, RawHttp.extractBody(response));
    }

    static Stream<Arguments> bodies() {
        return Stream.of(
                // Transfer-Encoding: chunked with multiple chunks
                // Format: <size-hex>\r\n<data>\r\n ... 0\r\n\r\n
                Arguments.of("chunked body", """
                        POST /echo HTTP/1.1\r
                        Host: localhost\r
                        Transfer-Encoding: chunked\r
                        \r
                        5\r
                        Hello\r
                        7\r
                        , World\r
                        0\r
                        \r
                        """, "Hello, World"),
                // RFC 9112 Section 7.1.1: chunk extensions should be ignored
                // Format: <size>;ext=val\r\n<data>\r\n
                Arguments.of("chunk extensions", """
                        POST /echo HTTP/1.1\r
                        Host: localhost\r
                        Transfer-Encoding: chunked\r
                        \r
                        5;ext=val\r
                        Hello\r
                        0\r
                        \r
                        """, "Hello"),
                // Immediately terminated chunked body: 0\r\n\r\n
                Arguments.of("empty chunked body", """
                        POST /echo HTTP/1.1\r
                        Host: localhost\r
                        Transfer-Encoding: chunked\r
                        \r
                        0\r
                        \r
                        """, ""),
                // GET request with no Content-Length or Transfer-Encoding: the handler answers without reading a body
                Arguments.of("no body", """
                        GET /nobody HTTP/1.1\r
                        Host: localhost\r
                        \r
                        """, "no-body-expected"));
    }
}
