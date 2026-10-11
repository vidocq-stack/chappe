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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.ServerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * TLS client authentication per listener ({@link ClientAuth}), over HTTP/1.1 and over HTTP/2
 * negotiated by ALPN. The handler answers with the subject of the client certificate it sees
 * ({@code none} when the chain is empty).
 */
class TlsClientAuthTest {

    private static final String CLIENT_DN = "CN=chappe-client";

    private static SSLContext serverContext;
    private static SSLContext clientWithCertificate;
    private static SSLContext clientWithoutCertificate;
    private static SSLContext untrustedClient;

    private final AtomicInteger handled = new AtomicInteger();
    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore clientKeys = TlsFixtures.selfSigned("client", CLIENT_DN, null);
        KeyStore strangerKeys = TlsFixtures.selfSigned("stranger", "CN=stranger", null);
        X509Certificate serverCertificate = TlsFixtures.certificate(serverKeys, "server");

        // The server trusts the client certificate only, not the stranger's.
        serverContext =
                TlsFixtures.context(serverKeys, TlsFixtures.trustStore(TlsFixtures.certificate(clientKeys, "client")));
        KeyStore trustServer = TlsFixtures.trustStore(serverCertificate);
        clientWithCertificate = TlsFixtures.context(clientKeys, trustServer);
        clientWithoutCertificate = TlsFixtures.context(null, trustServer);
        untrustedClient = TlsFixtures.contextAlwaysOffering(strangerKeys, "stranger", trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private static String peer(Request req) {
        return req.tls()
                .map(t -> t.peerCertificates().isEmpty()
                        ? "none"
                        : t.peerCertificates()
                                .getFirst()
                                .getSubjectX500Principal()
                                .getName())
                .orElse("no-tls");
    }

    private void start(ClientAuth clientAuth) {
        server = Server.builder()
                .port(0)
                .tls(serverContext, clientAuth)
                .handler(req -> {
                    handled.incrementAndGet();
                    return Response.ok(peer(req));
                })
                .build();
        server.start();
    }

    private static HttpClient client(SSLContext context, HttpClient.Version version) {
        return HttpClient.newBuilder()
                .version(version)
                .sslContext(context)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> get(HttpClient client) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String body(SSLContext context, HttpClient.Version version) throws Exception {
        try (var client = client(context, version)) {
            var response = get(client);
            assertEquals(200, response.statusCode());
            assertEquals(version, response.version(), "ALPN must select the requested protocol");
            return response.body();
        }
    }

    /** The connection is refused: an I/O failure that is not the client's own timeout, and no handler call. */
    private void assertRejected(SSLContext context, HttpClient.Version version) {
        try (var client = client(context, version)) {
            var failure = assertThrows(IOException.class, () -> get(client));
            assertFalse(
                    failure instanceof HttpTimeoutException,
                    "the server must close the connection, not leave the client waiting: " + failure);
        }
        assertEquals(0, handled.get(), "the handler must not run for a rejected client");
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needRejectsAClientWithoutCertificate(HttpClient.Version version) {
        start(ClientAuth.NEED);
        assertRejected(clientWithoutCertificate, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needRejectsAnUntrustedClientCertificate(HttpClient.Version version) {
        start(ClientAuth.NEED);
        assertRejected(untrustedClient, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantRejectsAnUntrustedClientCertificate(HttpClient.Version version) {
        // WANT makes the certificate optional, not unchecked: JSSE fails the handshake when the
        // client sends one the trust managers reject (ruling R-2, documented in the reference).
        start(ClientAuth.WANT);
        assertRejected(untrustedClient, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needAcceptsATrustedClientAndExposesItsChain(HttpClient.Version version) throws Exception {
        start(ClientAuth.NEED);
        assertEquals(CLIENT_DN, body(clientWithCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantAcceptsAClientWithoutCertificate(HttpClient.Version version) throws Exception {
        start(ClientAuth.WANT);
        assertEquals("none", body(clientWithoutCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantExposesTheClientChain(HttpClient.Version version) throws Exception {
        start(ClientAuth.WANT);
        assertEquals(CLIENT_DN, body(clientWithCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void noneNeverAsksForTheClientCertificate(HttpClient.Version version) throws Exception {
        start(ClientAuth.NONE);
        assertEquals("none", body(clientWithCertificate, version));
    }

    @Test
    void wantSharesTheChainAcrossHttp2Streams() throws Exception {
        start(ClientAuth.WANT);
        try (var client = client(clientWithCertificate, HttpClient.Version.HTTP_2)) {
            for (int i = 0; i < 3; i++) {
                var response = get(client);
                assertEquals(HttpClient.Version.HTTP_2, response.version(), "stream " + i + " must be HTTP/2");
                assertEquals(CLIENT_DN, response.body(), "stream " + i);
            }
        }
    }

    @Test
    void tlsWithoutClientAuthMeansNone() {
        server = Server.builder()
                .port(0)
                .tls(serverContext)
                .handler(_ -> Response.ok())
                .build();
        var config = server.config();
        assertEquals(ClientAuth.NONE, config.clientAuth());
        assertEquals(ClientAuth.NONE, ServerConfig.DEFAULT.clientAuth());
    }

    @Test
    void clientAuthWithoutSslContextFailsAtBuild() {
        for (ClientAuth mode : new ClientAuth[] {ClientAuth.WANT, ClientAuth.NEED}) {
            var builder = Server.builder().port(0).tls(null, mode).handler(_ -> Response.ok());
            assertThrows(IllegalArgumentException.class, builder::build, mode.name());
        }
    }

    @Test
    void nullClientAuthIsRejected() {
        assertThrows(NullPointerException.class, () -> Server.builder().tls(serverContext, null));
    }
}
