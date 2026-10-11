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
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client certificate issued by a CA, over HTTP/1.1 and over HTTP/2 negotiated by ALPN.
 * {@link io.vidocq.chappe.api.TlsInfo#peerCertificates()} is the chain the client sent, in the
 * order it sent it (leaf first): JSSE does not add the trust anchor the server validated it
 * against, nor drop it when the client sends it. Servlet's
 * {@code jakarta.servlet.request.X509Certificate} relies on that order.
 */
class TlsCertificateChainTest {

    private static final String CA_DN = "CN=chappe-test-ca";
    private static final String LEAF_DN = "CN=chappe-chain-client";

    private static SSLContext serverContext;
    private static SSLContext clientSendingTheChain;
    private static SSLContext clientSendingTheLeafOnly;

    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore caKeys = TlsFixtures.selfSignedCa("ca", CA_DN);
        KeyStore leafKeys = TlsFixtures.issuedBy(caKeys, "ca", "leaf", LEAF_DN);

        // The server trusts the CA only, never the leaf itself.
        serverContext = TlsFixtures.context(serverKeys, TlsFixtures.trustStore(TlsFixtures.certificate(caKeys, "ca")));
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        clientSendingTheChain = TlsFixtures.context(leafKeys, trustServer);
        clientSendingTheLeafOnly = TlsFixtures.contextOfferingLeafOnly(leafKeys, "leaf", trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /** The subjects of the chain, in order, separated by {@code |}; {@code no-tls} without TLS. */
    private static String chain(Request req) {
        return req.tls()
                .map(t -> t.peerCertificates().stream()
                        .map(X509Certificate::getSubjectX500Principal)
                        .map(Object::toString)
                        .collect(Collectors.joining("|")))
                .orElse("no-tls");
    }

    private String body(SSLContext context, HttpClient.Version version) throws Exception {
        server = Server.builder()
                .port(0)
                .tls(serverContext, ClientAuth.NEED)
                .handler(req -> Response.ok(chain(req)))
                .build();
        server.start();
        try (var client = HttpClient.newBuilder()
                .version(version)
                .sslContext(context)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(version, response.version(), "ALPN must select the requested protocol");
            return response.body();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void theChainIsExposedLeafFirst(HttpClient.Version version) throws Exception {
        String[] subjects = body(clientSendingTheChain, version).split("\\|");
        assertEquals(2, subjects.length, "leaf and issuer: " + String.join(" | ", subjects));
        assertEquals(LEAF_DN, subjects[0], "the leaf comes first");
        assertEquals(CA_DN, subjects[1], "its issuer comes second");
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void aLeafSentAloneIsExposedAlone(HttpClient.Version version) throws Exception {
        // The server validates the leaf against the trusted CA, but peerCertificates() is what
        // the client sent: JSSE does not append the trust anchor it found.
        assertEquals(LEAF_DN, body(clientSendingTheLeafOnly, version));
    }
}
