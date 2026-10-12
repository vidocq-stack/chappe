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

import java.net.HttpURLConnection;
import java.net.URI;
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
        assertThrows(IllegalStateException.class, () -> Server.builder().port(0).build());
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
