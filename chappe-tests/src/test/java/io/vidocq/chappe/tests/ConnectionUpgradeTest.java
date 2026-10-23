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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vidocq.chappe.api.ConnectionUpgrade;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.UpgradeHandler;
import io.vidocq.chappe.api.UpgradedConnection;
import io.vidocq.chappe.http.h2.HpackDecoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Generic HTTP/1.1 connection upgrade ({@link ConnectionUpgrade}): the transport writes the
 * application's status line and headers, then hands the raw byte streams to an
 * {@link io.vidocq.chappe.api.UpgradeHandler}. HTTP/2 refuses the mechanism (RFC 9113 §8.6).
 */
@Timeout(30)
class ConnectionUpgradeTest {

    private static final Headers ECHO_HEADERS = Headers.builder()
            .add("Upgrade", "echo")
            .add("Connection", "Upgrade")
            .build();

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void upgradeWritesStatusAndHeadersThenHandsOverTheStreams() throws Exception {
        var handedRequestPath = new AtomicReference<String>();
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, conn -> {
            handedRequestPath.set(conn.request().path());
            var reader = new BufferedReader(new InputStreamReader(conn.input(), StandardCharsets.US_ASCII));
            OutputStream out = conn.output();
            String line;
            while ((line = reader.readLine()) != null && !line.equals("bye")) {
                out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            conn.close();
        }));

        try (var socket = connect()) {
            // Head and the first upgraded bytes in ONE write: "hello\n" sits in the transport's
            // read buffer when the handler takes over.
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\nhello\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            String head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), head);
            assertTrue(head.contains("\r\nUpgrade: echo\r\n"), head);
            assertTrue(head.contains("\r\nConnection: Upgrade\r\n"), head);
            assertFalse(head.contains("Content-Length"), head);
            assertFalse(head.contains("Transfer-Encoding"), head);

            assertEquals("hello", readLine(in));

            socket.getOutputStream().write("bye\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(-1, in.read(), "connection must close after the handler closed it");
        }
        assertEquals("/u", handedRequestPath.get());
    }

    @Test
    void bufferedBytesAfterAHeadWithoutBodyLengthComeFirst() throws Exception {
        // Servlet TCK upgradeTest shape: POST with Content-type but neither Content-Length nor
        // Transfer-Encoding, raw "Hello" right behind the head.
        var received = new AtomicReference<String>();
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, Headers.of("Upgrade", "YES"), conn -> {
            received.set(new String(conn.input().readNBytes(10), StandardCharsets.US_ASCII));
            conn.output().write("done".getBytes(StandardCharsets.US_ASCII));
            conn.output().flush();
            conn.close();
        }));

        try (var socket = connect()) {
            var out = socket.getOutputStream();
            out.write("""
                    POST /TestServlet HTTP/1.1\r
                    Host: h\r
                    Upgrade: YES\r
                    Connection: Upgrade\r
                    Content-type: application/x-www-form-urlencoded\r
                    \r
                    Hello""".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            out.write("World".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals("done", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertEquals("HelloWorld", received.get());
    }

    @Test
    void connectionStaysOpenAfterOnUpgradeReturns() throws Exception {
        var stored = new AtomicReference<UpgradedConnection>();
        var returned = new CountDownLatch(1);
        var writerFailure = new AtomicReference<Throwable>();
        var writerDone = new CountDownLatch(1);
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, conn -> {
            stored.set(conn);
            Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(returned.await(5, TimeUnit.SECONDS), "onUpgrade did not return");
                    Thread.sleep(200);
                    conn.output().write("late\n".getBytes(StandardCharsets.US_ASCII));
                    conn.output().flush();
                    conn.close();
                } catch (Throwable t) {
                    writerFailure.set(t);
                    try {
                        conn.close();
                    } catch (IOException _) {
                        // best-effort, the failure is reported below
                    }
                } finally {
                    writerDone.countDown();
                }
            });
            // onUpgrade returns immediately; the connection must stay open.
            returned.countDown();
        }));

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 "));
            String late = readLine(in);
            assertTrue(writerDone.await(5, TimeUnit.SECONDS), "writer thread did not finish");
            if (writerFailure.get() != null) {
                throw new AssertionError("writer thread failed", writerFailure.get());
            }
            assertEquals("late", late);
            assertEquals(-1, in.read());
        }
        assertNotNull(stored.get());
    }

    @Test
    void streamsThrowOnceClosedAndNeverServeBufferedBytes() throws Exception {
        var readResult = new AtomicReference<Object>();
        var writeResult = new AtomicReference<Object>();
        var availableResult = new AtomicReference<Object>();
        var done = new CountDownLatch(1);
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, conn -> {
            try {
                conn.close();
                readResult.set(outcome(() -> conn.input().read(new byte[16])));
                availableResult.set(outcome(() -> conn.input().available()));
                writeResult.set(outcome(() -> {
                    conn.output().write('x');
                    return 1;
                }));
            } finally {
                done.countDown();
            }
        }));

        try (var socket = connect()) {
            // "abc" lands in the transport's (pooled) read buffer together with the head.
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\nabc"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 "));
            assertEquals(-1, in.read());
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, readResult.get(), "read after close must throw");
        assertInstanceOf(IOException.class, availableResult.get(), "available after close must throw");
        assertInstanceOf(IOException.class, writeResult.get(), "write after close must throw");
    }

    @Test
    void closeWakesAWriterStalledByAPeerThatDoesNotRead() throws Exception {
        var stored = new AtomicReference<UpgradedConnection>();
        var written = new java.util.concurrent.atomic.AtomicLong();
        var writerEnd = new AtomicReference<Throwable>();
        var writerDone = new CountDownLatch(1);
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, conn -> {
            stored.set(conn);
            Thread.ofVirtual().start(() -> {
                byte[] chunk = new byte[64 * 1024];
                try {
                    while (true) {
                        conn.output().write(chunk);
                        written.addAndGet(chunk.length);
                    }
                } catch (Throwable t) {
                    writerEnd.set(t);
                } finally {
                    writerDone.countDown();
                }
            });
        }));

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 101 "));
            // Stop reading: wait until the writer is stuck in a blocking write.
            awaitStalled(written);
            assertEquals(1, writerDone.getCount(), "writer must be blocked, not finished");

            long t0 = System.nanoTime();
            stored.get().close();
            long closeMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(closeMs < 2000, "close() took " + closeMs + " ms");
            assertTrue(writerDone.await(2, TimeUnit.SECONDS), "close() must wake the stalled writer");
            assertInstanceOf(IOException.class, writerEnd.get());
        }
    }

    @Test
    void serverStopReleasesAParkedUpgradedConnectionPromptly() throws Exception {
        var upgraded = new CountDownLatch(1);
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, _ -> upgraded.countDown()));

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 "));
            assertTrue(upgraded.await(5, TimeUnit.SECONDS));

            long t0 = System.nanoTime();
            server.stop();
            server = null;
            long stopMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(stopMs < 5000, "stop() took " + stopMs + " ms");
            assertEquals(-1, in.read(), "server stop must close the upgraded connection");
        }
    }

    /** Waits until {@code written} stops growing for 300 ms (the writer is blocked). */
    static void awaitStalled(java.util.concurrent.atomic.AtomicLong written) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long last = -1;
        while (System.nanoTime() < deadline) {
            long now = written.get();
            if (now == last && now > 0) return;
            last = now;
            Thread.sleep(300);
        }
        fail("writer never stalled (written=" + written.get() + ")");
    }

    @FunctionalInterface
    private interface IoCall {
        Object call() throws IOException;
    }

    /** The call's result, or the exception it threw. */
    private static Object outcome(IoCall call) {
        try {
            return call.call();
        } catch (IOException e) {
            return e;
        }
    }

    @Test
    void exceptionInOnUpgradeClosesTheConnection() throws Exception {
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, _ -> {
            throw new IllegalStateException("boom");
        }));

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 "));
            assertEquals(-1, in.read(), "a failing handler must close the connection");
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var secondClose = new AtomicReference<Throwable>();
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, conn -> {
            conn.close();
            try {
                conn.close();
            } catch (Throwable t) {
                secondClose.set(t);
            }
        }));

        try (var socket = connect()) {
            socket.getOutputStream()
                    .write("GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertTrue(readHead(in).startsWith("HTTP/1.1 101 "));
            assertEquals(-1, in.read());
        }
        assertNull(secondClose.get());
    }

    @Test
    void framingHeadersAreNotWritten() {
        var headers = Headers.builder()
                .add("Upgrade", "echo")
                .add("Content-Length", "12")
                .add("Transfer-Encoding", "chunked")
                .build();
        var upgrade = new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, headers, UpgradedConnection::close);
        assertFalse(upgrade.headers().contains("Content-Length"));
        assertFalse(upgrade.headers().contains("Transfer-Encoding"));
        assertEquals("echo", upgrade.headers().firstOrNull("Upgrade"));
        assertEquals(0, upgrade.body().contentLength());
    }

    @Test
    void invalidHeadersAreRejectedAtConstruction() {
        // The arguments are prepared here: only the constructor is expected to throw.
        var injectedValue = Headers.of("Upgrade", "x\r\nSet-Cookie: a=b");
        var badName = Headers.of("Bad Name", "v");
        var wideValue = Headers.of("X-Wide", "Ā");
        var injectedReason = StatusCode.of(299, "OK\r\nX-Injected: 1");
        var noHeaders = Headers.empty();
        UpgradeHandler noop = _ -> {};

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, injectedValue, noop));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, badName, noop));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, wideValue, noop));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionUpgrade(injectedReason, noHeaders, noop));
        assertThrows(
                NullPointerException.class,
                () -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, noHeaders, null));
    }

    @Test
    void http2RefusesUpgrade() throws Exception {
        start(_ -> new ConnectionUpgrade(StatusCode.SWITCHING_PROTOCOLS, ECHO_HEADERS, UpgradedConnection::close));

        try (var socket = connect()) {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, 0x4, 0, 0, new byte[0]); // SETTINGS
            var block = new ByteArrayOutputStream();
            literal(block, ":method", "GET");
            literal(block, ":scheme", "http");
            literal(block, ":authority", "127.0.0.1");
            literal(block, ":path", "/u");
            writeFrame(out, 0x1, 0x4 | 0x1, 1, block.toByteArray()); // HEADERS, END_HEADERS|END_STREAM
            out.flush();

            var decoder = new HpackDecoder(4096, 64 * 1024);
            while (true) {
                int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                int type = in.readUnsignedByte();
                in.readUnsignedByte(); // flags
                int streamId = in.readInt() & 0x7FFFFFFF;
                byte[] payload = in.readNBytes(len);
                if (type == 0x4 && streamId == 0) {
                    writeFrame(out, 0x4, 0x1, 0, new byte[0]); // SETTINGS ACK
                    out.flush();
                    continue;
                }
                if (type == 0x1 && streamId == 1) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    decoder.decode(ByteBuffer.wrap(payload), headers::put);
                    assertEquals("505", headers.get(":status"));
                    return;
                }
            }
        }
    }

    // --- helpers ---

    private void start(Handler handler) {
        server = Server.builder().port(0).handler(handler).build();
        server.start();
        port = server.port();
    }

    private Socket connect() throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
        socket.setSoTimeout(5000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    /** Reads the response head byte by byte (so no upgraded byte is consumed) up to CRLFCRLF. */
    private static String readHead(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
                return sb.toString();
            }
        }
        throw new IOException("EOF before end of head: " + sb);
    }

    private static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type);
        out.write(flags);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    /** HPACK literal header field without indexing, new name, no Huffman (RFC 7541 §6.2.2). */
    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        for (String s : new String[] {name, value}) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(bytes.length); // all strings here are < 127 bytes
            out.write(bytes, 0, bytes.length);
        }
    }
}
