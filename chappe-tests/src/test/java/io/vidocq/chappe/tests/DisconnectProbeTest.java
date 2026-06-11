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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Request.onDisconnect} — best-effort client-disconnect probe for
 * long-suspended responses (plaintext HTTP/1.1).
 */
class DisconnectProbeTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void callbackFiresWhenClientDisconnectsDuringSuspendedHandling() throws Exception {
        var disconnected = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    armed.set(req.onDisconnect(disconnected::countDown));
                    // Suspended response: wait for the disconnect (or time out).
                    boolean fired = disconnected.await(5, TimeUnit.SECONDS);
                    return Response.ok(fired ? "DISCONNECTED" : "TIMEOUT");
                })
                .build();
        server.start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream()
                    .write(("GET /wait HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(300); // let the handler arm the probe
        } // client gone (FIN)

        assertTrue(
                disconnected.await(3, TimeUnit.SECONDS), "the disconnect callback should fire after the client closes");
        assertTrue(armed.get(), "the probe should arm on a plaintext body-less request");
    }

    @Test
    void callbackDoesNotFireOnNormalCompletion() throws Exception {
        var fired = new AtomicBoolean();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    req.onDisconnect(() -> fired.set(true));
                    return Response.ok("OK");
                })
                .build();
        server.start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write(("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String resp = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(resp.contains("200"), resp);
        }
        Thread.sleep(300); // give a wrongly-lingering probe a chance to misfire
        assertFalse(fired.get(), "no disconnect callback on a normally-completed request");
    }

    @Test
    void armingIsRefusedWhileTheRequestBodyIsPending() throws Exception {
        var armed = new AtomicBoolean(true);
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    // Body present and not consumed: the probe must refuse to arm
                    // (it shares the parse buffer with the body reader).
                    armed.set(req.onDisconnect(() -> {}));
                    return Response.ok("OK");
                })
                .build();
        server.start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write(("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String resp = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(resp.contains("200"), resp);
        }
        assertFalse(armed.get(), "onDisconnect must return false when a body is pending");
    }

    @Test
    void pipelinedBytesReadByTheProbeAreNotLost() throws Exception {
        var disconnected = new CountDownLatch(1);
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    if (req.path().startsWith("/wait")) {
                        req.onDisconnect(disconnected::countDown);
                        // Short suspension: long enough for the probe to poll (and
                        // swallow the pipelined bytes into the parse buffer).
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Response.ok("FIRST");
                    }
                    return Response.ok("SECOND");
                })
                .build();
        server.start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(("GET /wait HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(150); // probe armed and polling
            // Pipelined second request — the probe will read these bytes into
            // the parse buffer; they must be served after the first response.
            out.write(("GET /second HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String all = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(all.contains("FIRST"), all);
            assertTrue(all.contains("SECOND"), "pipelined request lost by the probe: " + all);
            assertEquals(1, disconnected.getCount(), "no disconnect should have fired");
        }
    }
}
