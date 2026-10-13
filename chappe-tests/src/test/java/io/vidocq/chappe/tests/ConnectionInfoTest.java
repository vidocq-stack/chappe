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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-026: connection metadata ({@code isSecure}, {@code scheme}, addresses, {@code tls()})
 * on every transport path — HTTP/1.1 and HTTP/2 over TLS, h2c, cleartext HTTP/1.1.
 * Before the fix, TLS reported {@code isSecure() == false}, scheme {@code http} and
 * {@code null} addresses, and HTTP/2 (TLS or h2c) had no addresses at all.
 */
class ConnectionInfoTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        // The server asks for no client certificate (ClientAuth.NONE); its trust store is never used.
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /** {@code secure|scheme|remote host|local port|protocol|cipher suite|peer count}. */
    private static String describe(Request req) {
        var remote = req.remoteAddress();
        var local = req.localAddress();
        return String.join(
                "|",
                String.valueOf(req.isSecure()),
                req.scheme(),
                remote == null ? "null" : remote.getAddress().getHostAddress(),
                local == null ? "null" : String.valueOf(local.getPort()),
                req.tls().map(t -> t.protocol()).orElse("no-tls"),
                req.tls().map(t -> t.cipherSuite()).orElse("no-tls"),
                req.tls().map(t -> String.valueOf(t.peerCertificates().size())).orElse("no-tls"));
    }

    /**
     * {@link #describe} plus {@code #client port:session id}: requests on one connection share
     * the client port, and requests sharing one TLS session share the session id.
     */
    private static String describeWithConnection(Request req) {
        var remote = req.remoteAddress();
        return describe(req)
                + "#"
                + (remote == null ? "null" : String.valueOf(remote.getPort()))
                + ":"
                + req.tls().map(t -> HexFormat.of().formatHex(t.sessionId())).orElse("no-tls");
    }

    private void startTls(Handler handler) {
        server = Server.builder().port(0).tls(serverContext).handler(handler).build();
        server.start();
    }

    private static HttpClient tlsClient(HttpClient.Version version) {
        return HttpClient.newBuilder()
                .version(version)
                .sslContext(clientContext)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> send(HttpClient client, String scheme, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertTls(String described) {
        String[] parts = described.split("\\|");
        assertEquals("true", parts[0], "isSecure: " + described);
        assertEquals("https", parts[1], "scheme: " + described);
        assertEquals("127.0.0.1", parts[2], "remote address: " + described);
        assertEquals(String.valueOf(server.port()), parts[3], "local port: " + described);
        assertTrue(parts[4].matches("TLSv1\\.[23]"), "protocol: " + described);
        assertTrue(parts[5].startsWith("TLS_"), "cipher suite: " + described);
        assertEquals("0", parts[6], "no client certificate was asked for: " + described);
    }

    /** Checks the {@link #describe} part and returns the {@code client port:session id} part. */
    private String assertTlsWithConnection(String described) {
        int hash = described.indexOf('#');
        assertTrue(hash > 0, "no connection part: " + described);
        assertTls(described.substring(0, hash));
        String connection = described.substring(hash + 1);
        assertFalse(connection.startsWith("null:"), "client port: " + described);
        assertFalse(connection.endsWith(":"), "empty TLS session id: " + described);
        return connection;
    }

    @Test
    void http11OverTlsReportsTheConnection() throws Exception {
        startTls(req -> Response.ok(describe(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            var response = send(client, "https", "/");
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertTls(response.body());
        }
    }

    @Test
    void http2OverTlsReportsTheConnectionOnEveryStream() throws Exception {
        startTls(req -> Response.ok(describeWithConnection(req)));
        Set<String> connections = new HashSet<>();
        try (var client = tlsClient(HttpClient.Version.HTTP_2)) {
            for (int i = 0; i < 2; i++) { // two streams on one connection
                var response = send(client, "https", "/");
                assertEquals(HttpClient.Version.HTTP_2, response.version(), "ALPN must select h2");
                connections.add(assertTlsWithConnection(response.body()));
            }
        }
        // Same client port and same TLS session id: both streams copied the connection's TlsInfo.
        assertEquals(1, connections.size(), "both streams must report one connection: " + connections);
    }

    @Test
    void tlsKeepAliveRequestsAllReportTheConnection() throws Exception {
        startTls(req -> Response.ok(describeWithConnection(req)));
        Set<String> connections = new HashSet<>();
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            for (int i = 0; i < 3; i++) { // reset() between requests must keep the connection fields
                connections.add(
                        assertTlsWithConnection(send(client, "https", "/").body()));
            }
        }
        // One client port: the three requests really travelled on one kept-alive connection.
        assertEquals(1, connections.size(), "the requests must share one connection: " + connections);
    }

    @Test
    void mountedHandlerSeesTheTlsSession() throws Exception {
        startTls(Router.builder()
                .mount("/app", req -> Response.ok(describe(req)))
                .build());
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            assertTls(send(client, "https", "/app/x").body());
        }
    }

    /** {@code connection id|stream id}, each {@code none} when absent. */
    private static String ids(Request req) {
        return req.connectionId().isPresent()
                ? req.connectionId().getAsLong() + "|"
                        + (req.streamId().isPresent()
                                ? String.valueOf(req.streamId().getAsInt())
                                : "none")
                : "none|none";
    }

    private void startClear(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
    }

    @Test
    void keepAliveRequestsShareTheConnectionId() throws Exception {
        startClear(req -> Response.ok(ids(req)));
        try (var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var bodies = new HashSet<String>();
            for (int i = 0; i < 3; i++) bodies.add(send(client, "http", "/").body());
            assertEquals(1, bodies.size(), "one connection id for every request: " + bodies);
            var parts = bodies.iterator().next().split("\\|");
            assertTrue(Long.parseLong(parts[0]) > 0, "positive: " + bodies);
            assertEquals("none", parts[1], "HTTP/1.1 has no stream id");
        }
    }

    @Test
    void separateConnectionsGetDistinctIds() throws Exception {
        startClear(req -> Response.ok(ids(req)));
        var seen = new HashSet<String>();
        for (int i = 0; i < 2; i++) {
            try (var client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                seen.add(send(client, "http", "/").body().split("\\|")[0]);
            }
        }
        assertEquals(2, seen.size(), "distinct connection ids: " + seen);
    }

    @Test
    void http2StreamsShareTheConnectionIdAndCarryTheirStreamId() throws Exception {
        startTls(req -> Response.ok(ids(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_2)) {
            var connections = new HashSet<String>();
            var streams = new HashSet<Integer>();
            for (int i = 0; i < 3; i++) {
                var response = send(client, "https", "/");
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                var parts = response.body().split("\\|");
                connections.add(parts[0]);
                streams.add(Integer.parseInt(parts[1]));
            }
            assertEquals(1, connections.size(), "one connection id: " + connections);
            assertTrue(Long.parseLong(connections.iterator().next()) > 0);
            assertEquals(3, streams.size(), "distinct stream ids: " + streams);
            streams.forEach(id -> assertTrue(id > 0 && id % 2 == 1, "odd positive: " + id));
        }
    }

    @Test
    void tlsHttp11ReportsAPositiveConnectionId() throws Exception {
        startTls(req -> Response.ok(ids(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            var parts = send(client, "https", "/").body().split("\\|");
            assertTrue(Long.parseLong(parts[0]) > 0, "connection id");
            assertEquals("none", parts[1], "no stream id over HTTP/1.1");
        }
    }

    @Test
    void aMountedHandlerSeesTheIds() throws Exception {
        startTls(Router.builder().mount("/app", req -> Response.ok(ids(req))).build());
        try (var client = tlsClient(HttpClient.Version.HTTP_2)) {
            var parts = send(client, "https", "/app/x").body().split("\\|");
            assertTrue(Long.parseLong(parts[0]) > 0, "connection id through the mount");
            assertTrue(Integer.parseInt(parts[1]) > 0, "stream id through the mount");
        }
    }

    @Test
    void cleartextHttp11HasNoTlsSession() throws Exception {
        server = Server.builder()
                .port(0)
                .handler(req -> Response.ok(describe(req)))
                .build();
        server.start();
        try (var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            assertEquals(
                    "false|http|127.0.0.1|" + server.port() + "|no-tls|no-tls|no-tls",
                    send(client, "http", "/").body());
        }
    }

    @Test
    void h2cPriorKnowledgeReportsTheAddresses() throws Exception {
        var seen = new CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    seen.complete(describe(req) + "|" + req.version().name() + "|" + ids(req));
                    return Response.ok("ok");
                })
                .build();
        server.start();

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, 0x4, 0, 0, new byte[0]); // SETTINGS
            writeFrame(out, 0x1, 0x5, 1, getHeaders()); // HEADERS, END_STREAM | END_HEADERS
            out.flush();

            var described = seen.get(5, TimeUnit.SECONDS);
            assertEquals(
                    "false|http|127.0.0.1|" + server.port() + "|no-tls|no-tls|no-tls|HTTP_2",
                    described.substring(0, described.lastIndexOf("|", described.lastIndexOf("|") - 1)));
            var idParts = described
                    .substring(described.lastIndexOf("|", described.lastIndexOf("|") - 1) + 1)
                    .split("\\|");
            assertTrue(Long.parseLong(idParts[0]) > 0, "connection id: " + described);
            assertEquals("1", idParts[1], "stream id: " + described);
        }
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws Exception {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    /** HPACK literal header fields without indexing, no Huffman (RFC 7541 §6.2.2). */
    private static byte[] getHeaders() {
        var out = new ByteArrayOutputStream();
        literal(out, ":method", "GET");
        literal(out, ":scheme", "http");
        literal(out, ":authority", "127.0.0.1");
        literal(out, ":path", "/");
        return out.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        for (String s : new String[] {name, value}) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
    }
}
