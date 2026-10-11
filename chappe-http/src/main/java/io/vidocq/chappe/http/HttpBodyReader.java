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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;

import io.vidocq.chappe.api.Headers;

/**
 * Factory for InputStreams used to read the HTTP body.
 */
public final class HttpBodyReader {

    private HttpBodyReader() {}

    /**
     * Creates an InputStream for a fixed-size body (Content-Length).
     */
    static InputStream fixedLength(ByteBuffer buffer, ReadableByteChannel channel, long contentLength) {
        return new FixedLengthInputStream(buffer, channel, contentLength);
    }

    /**
     * Creates an InputStream for a chunked-transfer-encoded body. Trailer
     * fields after the terminal chunk are delivered to {@code trailersConsumer}
     * (RFC 9112 §7.1.2). A body announcing more than {@code maxBodySize} bytes
     * fails the read with {@link io.vidocq.chappe.api.RequestBodyTooLargeException}.
     */
    static InputStream chunked(
            ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer, long maxBodySize) {
        return new ChunkedInputStream(buffer, channel, trailersConsumer, maxBodySize);
    }

    /**
     * Reads and discards what is left of a request body, at most {@code maxBytes} bytes on the wire:
     * a handler that did not read its body leaves the rest on the connection, ahead of the next
     * request. A chunked body counts its framing and trailers too, so endless framing cannot outlast
     * the budget (CHAPPE-021).
     *
     * @return {@code true} when the body ended within the budget; {@code false} when more than
     *     {@code maxBytes} bytes were left, and the caller closes the connection instead (CHAPPE-021)
     * @throws IOException when the body cannot be read (bad framing, a body cut short, an earlier
     *     failed read): the caller closes the connection too
     */
    static boolean drain(InputStream bodyStream, long maxBytes) throws IOException {
        if (bodyStream instanceof RequestBodyInputStream body) body.limitRemainingWireBytes(maxBytes);
        byte[] buf = new byte[4096];
        long left = maxBytes;
        try {
            while (true) {
                // One byte past the budget tells a body longer than the budget from one that ends at it.
                int len = left >= buf.length ? buf.length : (int) left + 1;
                int n = bodyStream.read(buf, 0, len);
                if (n == -1) return true;
                left -= n;
                if (left < 0) return false;
            }
        } catch (RequestBodyInputStream.DrainBudgetExceededException _) {
            return false;
        }
    }
}
