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
package io.vidocq.chappe.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.ServerConfig;

import org.junit.jupiter.api.Test;

/**
 * The wait for a next keep-alive request costs one channel read. A blocking read on a virtual
 * thread already tries the socket without blocking before it parks, so a non-blocking probe
 * read ahead of it, with its two switches of the blocking mode, only added system calls.
 */
class KeepAliveReadsTest {

    private static final int REQUESTS = 20;

    @Test
    void aKeepAliveRequestCostsOneReadAndNoBlockingModeSwitch() throws Exception {
        try (var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (var client = new Socket(
                            InetAddress.getLoopbackAddress(), server.socket().getLocalPort());
                    var accepted = new CountingChannel(server.accept())) {
                client.setSoTimeout(5000);
                var http = new HttpConnection(accepted, _ -> Response.ok("pong"), ServerConfig.DEFAULT);
                Thread connection = Thread.ofVirtual().start(http::run);
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                // Sequential exchanges: the next request is never there before the response is
                // read, so every wait for it finds the socket empty.
                for (int i = 0; i < REQUESTS; i++) {
                    out.write("GET /ping HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    assertEquals("pong", readOneResponse(in));
                }
                client.shutdownOutput(); // EOF: the connection ends its loop
                connection.join(5000);

                // One read per request, plus the end of stream and some slack for a request split
                // in two segments; the probe read made it two per request.
                assertTrue(
                        accepted.reads.get() <= REQUESTS + 3,
                        "reads for " + REQUESTS + " keep-alive requests: " + accepted.reads.get());
                assertEquals(0, accepted.nonBlockingSwitches.get(), "switches to non-blocking mode");
            }
        }
    }

    /** Reads one response with a Content-Length body and returns that body. */
    private static String readOneResponse(InputStream in) throws IOException {
        var head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        if (b == -1) throw new IOException("Connection closed before the response head");
        String headers = head.toString().toLowerCase(Locale.ROOT);
        int idx = headers.indexOf("content-length:");
        int length = idx < 0
                ? 0
                : Integer.parseInt(headers.substring(idx + 15, headers.indexOf("\r\n", idx))
                        .trim());
        return new String(in.readNBytes(length), StandardCharsets.US_ASCII);
    }

    /** A socket channel that counts the reads and the switches to non-blocking mode of the one it wraps. */
    private static final class CountingChannel extends SocketChannel {
        private final SocketChannel delegate;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger nonBlockingSwitches = new AtomicInteger();

        CountingChannel(SocketChannel delegate) {
            super(SelectorProvider.provider());
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            reads.incrementAndGet();
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            reads.incrementAndGet();
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        protected void implConfigureBlocking(boolean block) throws IOException {
            if (!block) nonBlockingSwitches.incrementAndGet();
            delegate.configureBlocking(block);
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
            delegate.close();
        }

        @Override
        public SocketChannel bind(SocketAddress local) throws IOException {
            delegate.bind(local);
            return this;
        }

        @Override
        public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
            delegate.setOption(name, value);
            return this;
        }

        @Override
        public <T> T getOption(SocketOption<T> name) throws IOException {
            return delegate.getOption(name);
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return delegate.supportedOptions();
        }

        @Override
        public SocketChannel shutdownInput() throws IOException {
            delegate.shutdownInput();
            return this;
        }

        @Override
        public SocketChannel shutdownOutput() throws IOException {
            delegate.shutdownOutput();
            return this;
        }

        @Override
        public Socket socket() {
            return delegate.socket();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isConnectionPending() {
            return delegate.isConnectionPending();
        }

        @Override
        public boolean connect(SocketAddress remote) throws IOException {
            return delegate.connect(remote);
        }

        @Override
        public boolean finishConnect() throws IOException {
            return delegate.finishConnect();
        }

        @Override
        public SocketAddress getRemoteAddress() throws IOException {
            return delegate.getRemoteAddress();
        }

        @Override
        public SocketAddress getLocalAddress() throws IOException {
            return delegate.getLocalAddress();
        }
    }
}
