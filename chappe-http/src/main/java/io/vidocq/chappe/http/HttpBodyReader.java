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
     * (RFC 9112 §7.1.2).
     */
    static InputStream chunked(ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer) {
        return new ChunkedInputStream(buffer, channel, trailersConsumer);
    }

    /**
     * Drains the remaining body bytes (for keep-alive when the handler
     * did not read the body).
     */
    static void drain(InputStream bodyStream) throws IOException {
        if (bodyStream == null) return;
        byte[] buf = new byte[4096];
        while (bodyStream.read(buf) != -1) {
            // discard
        }
    }
}
