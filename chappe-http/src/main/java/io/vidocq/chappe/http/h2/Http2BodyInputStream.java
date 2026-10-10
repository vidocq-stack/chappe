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
package io.vidocq.chappe.http.h2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * InputStream fed by the DATA frame queue of an HTTP/2 stream.
 * <p>
 * Blocks on {@link Http2Stream#takeData()} when no data is available
 * (natural on virtual threads).
 */
final class Http2BodyInputStream extends InputStream {

    private final Http2Stream stream;
    private ByteBuffer current;

    Http2BodyInputStream(Http2Stream stream) {
        this.stream = stream;
    }

    @Override
    public int read() throws IOException {
        if (!ensureBuffer()) return -1;
        return current.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!ensureBuffer()) return -1;
        int toRead = Math.min(len, current.remaining());
        current.get(b, off, toRead);
        return toRead;
    }

    @Override
    public int available() {
        return current != null ? current.remaining() : 0;
    }

    /**
     * Makes bytes available, or returns {@code false} at a clean end of the body (END_STREAM).
     * A stream reset by the peer, or cut by the end of its connection, before its END_STREAM
     * fails the read: a truncated body must never pass for a complete one.
     */
    private boolean ensureBuffer() throws IOException {
        while (current == null || !current.hasRemaining()) {
            if (stream.isDataQueueEmpty()) {
                if (stream.isEndStreamReceived()) return false;
                if (stream.isCancelled()) throw streamReset();
            }
            try {
                current = stream.takeData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading HTTP/2 body", e);
            }
            if (!current.hasRemaining()) {
                if (stream.isEndStreamReceived()) return false;
                if (stream.isCancelled()) throw streamReset();
            }
        }
        return true;
    }

    private IOException streamReset() {
        return new IOException("HTTP/2 stream " + stream.streamId() + " reset before the end of its request body");
    }
}
