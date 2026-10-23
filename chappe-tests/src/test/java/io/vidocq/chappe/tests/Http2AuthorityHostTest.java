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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An HTTP/2 request carries its authority in {@code :authority}, not in a {@code host} field (RFC
 * 9113 §8.3.1): the server exposes it as the {@code host} header and in {@code uri()}, as over
 * HTTP/1.1 (CHAPPE-047).
 */
class Http2AuthorityHostTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    void theAuthorityIsTheHostHeaderAndTheUriAuthority() throws Exception {
        server = Server.builder()
                .port(0)
                .tls(serverContext)
                .handler(req -> Response.ok(
                        req.headers().firstOrNull("host") + "|" + req.uri().getAuthority()))
                .build();
        server.start();
        String authority = "127.0.0.1:" + server.port();

        try (var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(clientContext)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            var request = HttpRequest.newBuilder(URI.create("https://" + authority + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version(), "ALPN must select h2");
            assertEquals(authority + "|" + authority, response.body());
        }
    }
}
