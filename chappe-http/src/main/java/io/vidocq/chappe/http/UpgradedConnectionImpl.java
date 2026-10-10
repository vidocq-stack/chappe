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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.UpgradedConnection;

/**
 * Raw byte streams of an HTTP/1.1 connection taken over by a
 * {@link io.vidocq.chappe.api.ConnectionUpgrade}.
 * <p>
 * The bytes the transport had already read past the request head are <em>copied</em> out of the
 * parse buffer at construction: that buffer is pooled and goes back to the pool when the
 * connection thread ends, so the application must never see it. {@link #input()} serves the copy
 * first, then reads the channel. {@link #output()} writes straight to the channel, without
 * buffering and without the server's write timeout (a write stalled by the peer is released by
 * {@link #close()}). Once closed, both streams throw {@link IOException} and never touch the
 * channel again. {@link #close()} closes the connection once and releases {@link #awaitClose()},
 * on which the connection thread parks.
 */
final class UpgradedConnectionImpl implements UpgradedConnection {

    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Closeable closeable;
    private final Request request;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final InputStream input = new Input();
    private final OutputStream output = new Output();
    private volatile boolean closed;

    /** Private copy of the bytes buffered past the request head; guarded by the input lock. */
    private final byte[] leftover;

    private int leftoverPos;

    /**
     * @param leftover the parse buffer in read mode; its remaining bytes are copied and it is
     *     not referenced afterwards
     */
    UpgradedConnectionImpl(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            ByteBuffer leftover,
            Closeable closeable,
            Request request) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.leftover = new byte[leftover.remaining()];
        leftover.get(this.leftover);
        this.closeable = closeable;
        this.request = request;
    }

    @Override
    public InputStream input() {
        return input;
    }

    @Override
    public OutputStream output() {
        return output;
    }

    @Override
    public Request request() {
        return request;
    }

    @Override
    public void close() throws IOException {
        synchronized (closedLatch) {
            if (closed) return;
            closed = true;
        }
        try {
            closeable.close();
        } finally {
            closedLatch.countDown();
        }
    }

    /** Parks until {@link #close()} has been called. */
    void awaitClose() throws InterruptedException {
        closedLatch.await();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Upgraded connection closed");
    }

    private final class Input extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            ensureOpen();
            if (len == 0) return 0;
            if (leftoverPos < leftover.length) {
                int n = Math.min(len, leftover.length - leftoverPos);
                System.arraycopy(leftover, leftoverPos, b, off, n);
                leftoverPos += n;
                return n;
            }
            var dst = ByteBuffer.wrap(b, off, len);
            int n;
            do {
                n = readChannel.read(dst);
            } while (n == 0 && !closed);
            ensureOpen(); // closed while blocked: never report data or EOF from a closed connection
            return n;
        }

        /** Bytes buffered by the transport and not read yet (0 once they are consumed). */
        @Override
        public synchronized int available() throws IOException {
            ensureOpen();
            return leftover.length - leftoverPos;
        }

        @Override
        public void close() {
            // Closing one stream does not close the connection: see UpgradedConnection#close().
        }
    }

    private final class Output extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            var src = ByteBuffer.wrap(b, off, len);
            while (src.hasRemaining()) {
                ensureOpen();
                writeChannel.write(src);
            }
        }

        @Override
        public void flush() throws IOException {
            ensureOpen(); // unbuffered: every write is already on the wire
        }

        @Override
        public void close() {
            // Closing one stream does not close the connection: see UpgradedConnection#close().
        }
    }
}
