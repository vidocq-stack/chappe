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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ServerConfig.writeTimeout} — bound on a single blocking write, not on
 * response/stream inactivity.
 *
 * <p>Twin of {@link KeepAliveIdleTimeoutTest} (which proves {@code idleTimeout}
 * closes a genuinely idle keep-alive connection). This test proves the other
 * half: {@code writeTimeout} closes a connection whose socket refuses to
 * accept bytes (a stalled/blocked {@code channel.write()}), while a healthy
 * streamed response — bytes flowing, but with a long gap where nothing is
 * being written at all, as with Server-Sent Events between events — must
 * survive a {@code writeTimeout} far shorter than the gap.</p>
 */
class WriteTimeoutTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * A client that never drains its receive buffer eventually makes the
     * server's {@code channel.write()} block (kernel send buffer + TCP
     * window exhausted). With a short {@code writeTimeout}, the server must
     * give up and close the connection instead of blocking forever — so the
     * client only ever receives a truncated prefix of the declared body,
     * never the full length.
     */
    @Test
    void stalledWriteIsBoundedAndConnectionIsClosed() throws IOException {
        byte[] body = new byte[32 * 1024 * 1024]; // 32 MiB — far more than any socket buffer will hold unread
        server = Server.builder()
                .port(0)
                .writeTimeout(Duration.ofMillis(300))
                .idleTimeout(Duration.ofSeconds(30)) // keep the (unrelated) idle timeout out of the way
                .handler(_ -> Response.builder()
                        .status(StatusCode.OK)
                        .header("Content-Type", "application/octet-stream")
                        .body(Body.of(body))
                        .build())
                .build();
        server.start();

        try (var socket = new Socket()) {
            // Small receive buffer: the server exhausts it (plus its own send
            // buffer) quickly, so channel.write() starts blocking well before
            // 32 MiB have gone out.
            socket.setReceiveBufferSize(2048);
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
            socket.setSoTimeout(2000);

            var out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Deliberately do NOT read anything for well over the write
            // timeout, so at least one channel.write() call stalls long
            // enough to be killed by the watchdog.
            sleep(3000);

            // Now drain whatever the server managed to push before giving up.
            // If the write were unbounded (old behavior), the server would
            // still be alive, blocked in write(), and draining would
            // eventually deliver the complete 32 MiB body. With the fix, the
            // server already closed the connection, so we receive a
            // truncated prefix and then EOF.
            long received = 0;
            var in = socket.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    received += n;
                }
            } catch (SocketTimeoutException e) {
                fail("Server never closed the connection — write was not bounded (received " + received
                        + " bytes so far)");
            }

            assertTrue(
                    received < body.length,
                    "Expected a truncated response (server gave up mid-write), but received the full " + received
                            + " bytes");
        }
    }

    /**
     * A long gap between two chunks of a streamed response, with nothing in
     * flight, must not be treated as a stalled write: {@code writeTimeout}
     * only bounds a call to {@code write()} that is actually blocked, and no
     * such call happens while the handler is simply not producing data yet.
     */
    @Test
    void idleGapBetweenChunksSurvivesAShortWriteTimeout() throws IOException {
        long gapMillis = 1200;
        server = Server.builder()
                .port(0)
                .writeTimeout(Duration.ofMillis(300)) // much shorter than the idle gap below
                .idleTimeout(Duration.ofSeconds(30))
                .handler(_ -> {
                    try {
                        var pis = new PipedInputStream(8192);
                        var pos = new PipedOutputStream(pis);
                        Thread.startVirtualThread(() -> {
                            try (pos) {
                                pos.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                                pos.flush();
                                Thread.sleep(gapMillis); // simulates a healthy but quiet SSE stream
                                pos.write("data: second\n\n".getBytes(StandardCharsets.UTF_8));
                            } catch (IOException | InterruptedException _) {
                                // best-effort
                            }
                        });
                        return Response.builder()
                                .status(StatusCode.OK)
                                .header("Content-Type", "text/event-stream")
                                .body(Body.streaming(pis))
                                .build();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        server.start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000); // client-side patience for the artificial gap; unrelated to writeTimeout
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            out.write("GET / HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String headers = readHeaders(in);
            assertTrue(headers.contains("Transfer-Encoding: chunked"), "headers:\n" + headers);

            String decoded = readChunkedBody(in);
            assertEquals(
                    "data: first\n\ndata: second\n\n",
                    decoded,
                    "Both chunks must arrive — the idle gap must not have killed the connection");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String readHeaders(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            int len = sb.length();
            if (len >= 4
                    && sb.charAt(len - 4) == '\r'
                    && sb.charAt(len - 3) == '\n'
                    && sb.charAt(len - 2) == '\r'
                    && sb.charAt(len - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    private String readChunkedBody(InputStream in) throws IOException {
        var result = new StringBuilder();
        while (true) {
            var sizeLine = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                sizeLine.append((char) b);
            }
            String sizeStr = sizeLine.toString().trim();
            if (sizeStr.isEmpty()) continue;
            int semicolon = sizeStr.indexOf(';');
            if (semicolon >= 0) sizeStr = sizeStr.substring(0, semicolon);
            int chunkSize = Integer.parseInt(sizeStr.trim(), 16);
            if (chunkSize == 0) break;
            byte[] data = in.readNBytes(chunkSize);
            result.append(new String(data, StandardCharsets.UTF_8));
            in.read(); // \r
            in.read(); // \n
        }
        return result.toString();
    }
}
