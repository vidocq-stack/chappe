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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A request body read blocked on another thread and interrupted once the response is written (as
 * Foy's ReadListener pump is): the interrupt closes the channel mid-read. The connection must then
 * close; it must never parse the stale bytes of the read buffer as a next request (CHAPPE-013).
 */
class InterruptedBodyReadTest {

    /** Body bytes: 4 filler bytes ("AAAA"), then a whole request that must never be dispatched. */
    private static final byte[] PART1 = """
            AAAAGET /smuggled HTTP/1.1\r
            Host: localhost\r
            \r
            """.getBytes(StandardCharsets.US_ASCII);

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void anInterruptedBodyReadClosesTheConnectionWithoutParsingStaleBytes() throws Exception {
        var smuggled = new CountDownLatch(1);
        var handlerStarted = new CompletableFuture<Void>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    if (req.path().startsWith("/smuggled")) {
                        smuggled.countDown();
                        return Response.ok("smuggled");
                    }
                    InputStream body = req.body().asInputStream();
                    var partRead = new CountDownLatch(1);
                    Thread reader = Thread.ofVirtual().start(() -> {
                        try {
                            byte[] buf = new byte[PART1.length];
                            int got = 0;
                            while (got < PART1.length) {
                                int n = body.read(buf, got, PART1.length - got);
                                if (n < 0) return;
                                got += n;
                            }
                            partRead.countDown();
                            body.read(new byte[64]); // blocks: the client sends nothing more
                        } catch (IOException _) {
                            // the interrupt closed the channel
                        }
                    });
                    handlerStarted.complete(null);
                    assertTrue(partRead.await(5, TimeUnit.SECONDS));
                    awaitBlocked(reader);
                    // Released once the response is written: interrupt the blocked reader then.
                    return Response.ok(new InterruptingBody(Body.of("ok".getBytes(StandardCharsets.US_ASCII)), reader));
                })
                .build();
        server.start();

        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(("POST /up HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + (PART1.length + 4) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            handlerStarted.get(5, TimeUnit.SECONDS);
            out.write(PART1);
            out.flush();
            String response = readToEnd(socket.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertFalse(response.contains("smuggled"), response);
        }
        // A dispatch of the stale bytes happens right after the release when the bug is present.
        assertFalse(smuggled.await(2, TimeUnit.SECONDS), "the stale buffer was parsed as a request");
    }

    private static void awaitBlocked(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, t.getState(), "the reader never blocked on the channel");
    }

    private static String readToEnd(InputStream in) throws IOException {
        var all = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try {
            int n;
            while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
        } catch (SocketException _) {
            // a reset ends the connection too
        }
        return all.toString(StandardCharsets.US_ASCII);
    }

    /** A body that, once released, interrupts the thread blocked on the request body and joins it. */
    private record InterruptingBody(Body delegate, Thread reader) implements Body {
        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public InputStream asInputStream() {
            return delegate.asInputStream();
        }

        @Override
        public Flow.Publisher<ByteBuffer> asPublisher() {
            return delegate.asPublisher();
        }

        @Override
        public void release() {
            reader.interrupt();
            try {
                reader.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
