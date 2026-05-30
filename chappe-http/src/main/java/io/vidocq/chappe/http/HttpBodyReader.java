package io.vidocq.chappe.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

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
     * Creates an InputStream for a chunked-transfer-encoded body.
     */
    static InputStream chunked(ByteBuffer buffer, ReadableByteChannel channel) {
        return new ChunkedInputStream(buffer, channel);
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
