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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Handshake hardening of {@code SslHandler}, driven with a raw {@link SSLSocket} so the TLS
 * version is pinned and the client's own exceptions are visible:
 * <ul>
 *   <li>a TLS 1.2 client-initiated renegotiation is refused (the connection closes), so the
 *       {@code TlsInfo} read at the handshake cannot go stale (ruling R-1);</li>
 *   <li>a handshake the server rejects sends its TLS alert before the close, so the client
 *       learns why (CHAPPE-027, ruling R-3).</li>
 * </ul>
 */
class TlsHandshakeHardeningTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext; // presents no certificate, trusts the server

    private final AtomicInteger handled = new AtomicInteger();
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

    private void start(ClientAuth clientAuth) {
        server = Server.builder()
                .port(0)
                .tls(serverContext, clientAuth)
                .handler(_ -> {
                    handled.incrementAndGet();
                    return Response.ok("ok");
                })
                .build();
        server.start();
    }

    private SSLSocket connect(String protocol) throws IOException {
        var socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", server.port());
        socket.setSoTimeout(5000);
        var params = socket.getSSLParameters();
        params.setProtocols(new String[] {protocol});
        params.setApplicationProtocols(new String[] {"http/1.1"});
        socket.setSSLParameters(params);
        return socket;
    }

    /** One keep-alive GET; returns the response up to its {@code ok} body, or what was read before EOF. */
    private static String exchange(SSLSocket socket) throws IOException {
        var out = socket.getOutputStream();
        out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(US_ASCII));
        out.flush();
        var in = socket.getInputStream();
        var text = new StringBuilder();
        int b;
        while (!text.toString().endsWith("\r\n\r\nok") && (b = in.read()) != -1) {
            text.append((char) b);
        }
        return text.toString();
    }

    @Test
    void tls12KeepAliveWithoutRenegotiationStillWorks() throws Exception {
        start(ClientAuth.NONE);
        try (var socket = connect("TLSv1.2")) {
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"));
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"), "the renegotiation check must not fire on data");
        }
        assertEquals(2, handled.get());
    }

    @Test
    void tls12ClientInitiatedRenegotiationIsRefused() throws Exception {
        start(ClientAuth.NONE);
        try (var socket = connect("TLSv1.2")) {
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"));
            String second;
            try {
                socket.startHandshake(); // a new handshake on an established TLS 1.2 session = renegotiation
                second = exchange(socket);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the server must close the connection, not stall", e);
            } catch (IOException e) {
                second = "failed: " + e;
            }
            assertFalse(second.startsWith("HTTP/1.1 200"), "renegotiation must be refused, got: " + second);
        }
        assertEquals(1, handled.get(), "no request may be served after a renegotiation attempt");
    }

    @Test
    void tls13KeyUpdateDoesNotCloseTheConnection() throws Exception {
        start(ClientAuth.NONE);
        try (var socket = connect("TLSv1.3")) {
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"));
            // On an established TLS 1.3 connection the JDK sends a KeyUpdate (update_requested):
            // a post-handshake message, not a renegotiation, so it must keep the connection open.
            socket.startHandshake();
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"), "a TLS 1.3 KeyUpdate must not be refused");
            assertFalse(socket.isClosed());
        }
        assertEquals(2, handled.get());
    }

    @Test
    void tls12NeedRejectionSendsTheAlert() throws Exception {
        start(ClientAuth.NEED);
        try (var socket = connect("TLSv1.2")) {
            var failure = assertThrows(SSLHandshakeException.class, socket::startHandshake);
            assertTrue(
                    String.valueOf(failure.getMessage()).contains("Received fatal alert"),
                    "the client must get the server's alert, not a bare close: " + failure);
        }
        assertEquals(0, handled.get());
    }

    @Test
    void tls13NeedRejectionSendsTheAlert() throws Exception {
        start(ClientAuth.NEED);
        try (var socket = connect("TLSv1.3")) {
            // Under TLS 1.3 the client's side of the handshake completes before the server checks
            // the certificate, so the alert arrives on the first read.
            var failure = assertThrows(SSLException.class, () -> {
                socket.startHandshake();
                socket.getInputStream().read();
            });
            assertTrue(
                    String.valueOf(failure.getMessage()).contains("certificate_required"),
                    "the client must get the server's alert, not a bare close: " + failure);
        }
        assertEquals(0, handled.get());
    }
}
