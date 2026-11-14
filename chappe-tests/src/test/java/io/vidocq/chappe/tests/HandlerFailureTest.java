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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.tests.log.CapturingLoggerFinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A handler that fails is answered 500 and logged, whatever it throws: an exception, a class that does not link (a
 * bean class the build did not process), a failed assertion, a recursion too deep. It never leaves the request
 * without a response, nor the failure on the standard error of a dying thread (CHAPPE-054).
 */
class HandlerFailureTest {

    private Server server;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .get("/ok", _ -> Response.ok("still serving"))
                .get("/exception", _ -> {
                    throw new IllegalStateException("handler-exception");
                })
                .get("/linkage", _ -> {
                    throw new NoSuchMethodError("handler-linkage");
                })
                .get("/assertion", _ -> {
                    throw new AssertionError("handler-assertion");
                })
                .get("/recursion", _ -> {
                    throw new StackOverflowError("handler-recursion");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @ParameterizedTest(name = "{0} over {1}")
    @CsvSource({
        "/exception, HTTP_1_1, handler-exception",
        "/linkage, HTTP_1_1, handler-linkage",
        "/assertion, HTTP_1_1, handler-assertion",
        "/recursion, HTTP_1_1, handler-recursion",
        "/exception, HTTP_2, handler-exception",
        "/linkage, HTTP_2, handler-linkage",
        "/assertion, HTTP_2, handler-assertion",
        "/recursion, HTTP_2, handler-recursion",
    })
    void aFailingHandlerIsAnswered500AndLogged(String path, HttpClient.Version version, String message)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(version).build()) {
            HttpResponse<String> failed = get(client, path);
            assertEquals(500, failed.statusCode());

            HttpResponse<String> next = get(client, "/ok");
            assertEquals(200, next.statusCode(), "the server stops answering after " + path);
            assertEquals("still serving", next.body());
        }
        boolean logged = CapturingLoggerFinder.RECORDS.stream()
                .anyMatch(r -> r.level() == System.Logger.Level.WARNING
                        && r.thrown() != null
                        && message.equals(r.thrown().getMessage())
                        && r.message().contains(path));
        assertTrue(
                logged,
                () -> "the failure of " + path + " must be logged at WARNING; warnings="
                        + CapturingLoggerFinder.RECORDS.stream()
                                .filter(r -> r.level() == System.Logger.Level.WARNING)
                                .toList());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
