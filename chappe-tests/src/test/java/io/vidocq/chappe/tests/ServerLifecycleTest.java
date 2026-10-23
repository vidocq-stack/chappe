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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.ChappeException;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.Test;

class ServerLifecycleTest {

    @Test
    void startAndStop() {
        var server = Server.builder().port(0).handler(_ -> Response.ok("hello")).build();

        assertFalse(server.isRunning());

        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.port() > 0);

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void ephemeralPort() {
        try (var server = Server.builder().port(0).handler(_ -> Response.ok()).build()) {
            server.start();
            int port = server.port();
            assertTrue(port > 0 && port < 65536, "Port should be valid: " + port);
            assertNotNull(server.localAddress());
            assertEquals(port, server.localAddress().getPort());
            assertEquals(0, server.config().port(), "a wildcard port(0) keeps the configured port 0");
        }
    }

    /**
     * An {@code idleTimeout} too large for an {@code int} of milliseconds (about 24.8 days) must not
     * kill the accept loop (CHAPPE-031): its {@code SO_TIMEOUT} cast overflowed to a negative value,
     * {@code setSoTimeout} threw, and the accept thread died, so the server stopped accepting.
     */
    @Test
    void aVeryLargeIdleTimeoutStillAccepts() throws IOException {
        for (Duration idle : List.of(Duration.ofDays(30), ChronoUnit.FOREVER.getDuration())) {
            try (var server = Server.builder()
                    .host("127.0.0.1")
                    .port(0)
                    .idleTimeout(idle)
                    .handler(_ -> Response.ok("ok"))
                    .build()) {
                server.start();
                String response = exchange(server.port());
                assertTrue(response.startsWith("HTTP/1.1 200"), "idleTimeout " + idle + ": " + response);
            }
        }
    }

    /** A negative {@code idleTimeout} must not kill the accept loop either (CHAPPE-031). */
    @Test
    void aNegativeIdleTimeoutDoesNotKillTheAcceptLoop() {
        try (var server = Server.builder()
                .host("127.0.0.1")
                .port(0)
                .idleTimeout(Duration.ofSeconds(-1))
                .handler(_ -> Response.ok("ok"))
                .build()) {
            server.start();
            int port = server.port();
            // The connection is handled, however briefly: a response or the server's close, never
            // the client's own SO_TIMEOUT behind a dead accept loop.
            assertDoesNotThrow(() -> exchange(port));
        }
    }

    /** Sends one request with {@code Connection: close} and reads to the end; a reset ends it too. */
    private static String exchange(int port) throws IOException {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            try {
                return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            } catch (java.net.SocketTimeoutException e) {
                throw e; // the accept loop is dead: nothing ever handled the connection
            } catch (SocketException _) {
                return ""; // closed by the server
            }
        }
    }

    @Test
    void doubleStartThrows() {
        try (var server = Server.builder().port(0).handler(_ -> Response.ok()).build()) {
            server.start();
            assertThrows(ChappeException.ServerException.class, server::start);
        }
    }

    @Test
    void stopIdempotent() {
        var server = Server.builder().port(0).handler(_ -> Response.ok()).build();
        server.start();
        server.stop();
        // Second stop should not throw
        assertDoesNotThrow(server::stop);
    }

    @Test
    void tryWithResources() {
        Server server;
        try (var s = Server.builder().port(0).handler(_ -> Response.ok()).build()) {
            s.start();
            server = s;
            assertTrue(server.isRunning());
        }
        assertFalse(server.isRunning());
    }

    @Test
    void configPreserved() {
        try (var server = Server.builder()
                .port(0)
                .host("127.0.0.1")
                .backlog(512)
                .maxRequestSize(1024)
                .maxHeaderSize(4096)
                .handler(_ -> Response.ok())
                .build()) {
            var config = server.config();
            assertEquals("127.0.0.1", config.host());
            assertEquals(0, config.port());
            assertEquals(512, config.backlog());
            assertEquals(1024, config.maxRequestSize());
            assertEquals(4096, config.maxHeaderSize());
        }
    }

    @Test
    void builderRequiresHandler() {
        var builder = Server.builder().port(0);
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void portZeroReportsTheBoundPortAndKeepsTheConfig() {
        var server = Server.builder()
                .host("127.0.0.1")
                .port(0)
                .handler(_ -> Response.ok())
                .build();
        assertEquals(0, server.port(), "before start the port is the configured 0");
        server.start();
        try {
            int bound = server.port();
            assertTrue(bound > 0, "bound port: " + bound);
            assertEquals(0, server.config().port(), "the config is immutable and keeps 0");
            assertEquals(bound, server.localAddress().getPort());
        } finally {
            server.stop();
        }

        try (var next = Server.builder()
                .host("127.0.0.1")
                .port(0)
                .handler(_ -> Response.ok())
                .build()) {
            next.start();
            assertTrue(next.port() > 0, "a fresh server binds its own port");
            assertEquals(next.port(), next.localAddress().getPort());
        }
    }

    @Test
    void manyPortZeroServersStartConcurrentlyWithoutError() throws Exception {
        final int threads = 8;
        final int perThread = 25;
        final Set<Integer> livePorts = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    int served = 0;
                    for (int i = 0; i < perThread; i++) {
                        var server = Server.builder()
                                .host("127.0.0.1")
                                .port(0)
                                .handler(_ -> Response.ok("ok"))
                                .build();
                        server.start();
                        try {
                            int port = server.port();
                            assertTrue(port > 0, "bound port: " + port);
                            assertTrue(livePorts.add(port), "port " + port + " is used by two live servers");
                            try {
                                var conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/")
                                        .toURL()
                                        .openConnection();
                                conn.setConnectTimeout(5_000);
                                conn.setReadTimeout(5_000);
                                assertEquals(200, conn.getResponseCode());
                                conn.getInputStream().readAllBytes();
                                conn.disconnect();
                                served++;
                            } finally {
                                livePorts.remove(port);
                            }
                        } finally {
                            server.stop();
                        }
                    }
                    return served;
                }));
            }
            int total = 0;
            for (var f : futures) {
                total += f.get(40, TimeUnit.SECONDS);
            }
            assertEquals(threads * perThread, total);
        } finally {
            pool.shutdownNow();
        }
    }
}
