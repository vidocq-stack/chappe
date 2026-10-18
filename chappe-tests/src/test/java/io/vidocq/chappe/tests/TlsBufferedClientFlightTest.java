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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * chappe#24: with a client certificate, a client's last handshake flight can be several records. The server's
 * handshake stopped unwrapping when the engine asked for a delegated task, with the other records still buffered,
 * then blocked on the socket: a client that had sent everything and waited for the server waited forever.
 *
 * <p>This client sends its last flight in one write and sends no request.
 */
class TlsBufferedClientFlightTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext;
    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore clientKeys = TlsFixtures.selfSigned("client", "CN=chappe-client", null);
        X509Certificate serverCertificate = TlsFixtures.certificate(serverKeys, "server");
        serverContext =
                TlsFixtures.context(serverKeys, TlsFixtures.trustStore(TlsFixtures.certificate(clientKeys, "client")));
        clientContext = TlsFixtures.context(clientKeys, TlsFixtures.trustStore(serverCertificate));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /**
     * TLS 1.2: the client's last flight is three records ({@code Certificate}, {@code ClientKeyExchange} and
     * {@code CertificateVerify} in one, then {@code ChangeCipherSpec}, then {@code Finished}), and the client waits for
     * the server's {@code Finished} before it sends anything: the handshake completes only if the server unwraps the
     * records it already holds. TLS 1.3: the JDK client sends its last flight as one record (measured), so it checks
     * that nothing regressed; the server's {@code NewSessionTicket} shows it finished.
     */
    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void the_server_finishes_a_handshake_whose_last_client_flight_came_in_one_write(String protocol) throws Exception {
        server = Server.builder()
                .port(0)
                .tls(serverContext, ClientAuth.NEED)
                .handler(_ -> Response.ok("unused"))
                .build();
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5_000);
            SSLEngine engine = clientContext.createSSLEngine("127.0.0.1", server.port());
            engine.setUseClientMode(true);
            engine.setEnabledProtocols(new String[] {protocol});
            try {
                handshake(engine, socket.getInputStream(), socket.getOutputStream());
            } catch (SocketTimeoutException stuck) {
                fail("the server never finished the handshake: no Finished within 5 s");
            }

            if (protocol.equals("TLSv1.3")) {
                int first;
                try {
                    first = socket.getInputStream().read();
                } catch (SocketTimeoutException stuck) {
                    first = -2;
                }
                assertTrue(first >= 0, "the server never finished the handshake: no NewSessionTicket within 5 s");
            }
        }
    }

    /**
     * A client handshake that buffers the records of one flight and writes them at once, when the engine turns to
     * reading or is done.
     */
    private static void handshake(SSLEngine engine, InputStream in, OutputStream out) throws IOException {
        var session = engine.getSession();
        ByteBuffer netIn = ByteBuffer.allocate(session.getPacketBufferSize() * 4);
        ByteBuffer netOut = ByteBuffer.allocate(session.getPacketBufferSize());
        ByteBuffer app = ByteBuffer.allocate(session.getApplicationBufferSize());
        var flight = new ByteArrayOutputStream();
        byte[] chunk = new byte[session.getPacketBufferSize()];

        engine.beginHandshake();
        HandshakeStatus status = engine.getHandshakeStatus();
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            switch (status) {
                case NEED_WRAP -> {
                    netOut.clear();
                    SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), netOut);
                    flight.write(netOut.array(), 0, netOut.position());
                    status = result.getHandshakeStatus();
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) task.run();
                    status = engine.getHandshakeStatus();
                }
                case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                    send(flight, out);
                    netIn.flip();
                    app.clear();
                    SSLEngineResult result = engine.unwrap(netIn, app);
                    netIn.compact();
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        int n = in.read(chunk);
                        if (n < 0) throw new IOException("the server closed the connection during the handshake");
                        netIn.put(chunk, 0, n);
                    }
                    status = result.getHandshakeStatus();
                }
                default -> throw new IOException("unexpected handshake status " + status);
            }
        }
        send(flight, out);
    }

    private static void send(ByteArrayOutputStream flight, OutputStream out) throws IOException {
        if (flight.size() == 0) return;
        out.write(flight.toByteArray());
        out.flush();
        flight.reset();
    }
}
