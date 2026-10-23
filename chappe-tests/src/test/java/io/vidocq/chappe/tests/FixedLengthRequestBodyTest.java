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
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FixedLengthRequestBodyTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** A connection closed before Content-Length bytes arrived is a read failure, not the end of the body (CHAPPE-012). */
    @Test
    void aBodyCutShortOfItsContentLengthFailsTheRead() throws Exception {
        var outcome = new CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    try {
                        outcome.complete("body:"
                                + new String(req.body().asInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                    } catch (IOException _) {
                        outcome.complete("IOException");
                    }
                    return Response.ok("done");
                })
                .build();
        server.start();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            var out = socket.getOutputStream();
            out.write("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nHello"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.shutdownOutput();
            assertEquals("IOException", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    /** The single-byte read reports the same failure. */
    @Test
    void aSingleByteReadPastTheCutFails() throws Exception {
        var outcome = new CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    var in = req.body().asInputStream();
                    var sb = new StringBuilder();
                    try {
                        int b;
                        while ((b = in.read()) != -1) sb.append((char) b);
                        outcome.complete("body:" + sb);
                    } catch (IOException _) {
                        outcome.complete("IOException");
                    }
                    return Response.ok("done");
                })
                .build();
        server.start();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            var out = socket.getOutputStream();
            out.write("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nHel"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.shutdownOutput();
            assertEquals("IOException", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    /** A zero-length read returns 0 without touching the connection (InputStream contract). */
    @Test
    void aZeroLengthReadReturnsZeroWithoutRefilling() throws Exception {
        var outcome = new CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    try {
                        outcome.complete("read:" + req.body().asInputStream().read(new byte[4], 0, 0));
                    } catch (IOException _) {
                        outcome.complete("IOException");
                    }
                    return Response.ok("done");
                })
                .build();
        server.start();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            var out = socket.getOutputStream();
            // No body byte yet, then the end of input: a refill would fail the read.
            out.write("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.shutdownOutput();
            assertEquals("read:0", outcome.get(5, TimeUnit.SECONDS));
        }
    }
}
