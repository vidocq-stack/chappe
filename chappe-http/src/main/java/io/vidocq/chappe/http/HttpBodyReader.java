package io.vidocq.chappe.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * Factory pour les InputStreams de lecture du body HTTP.
 */
public final class HttpBodyReader {

    private HttpBodyReader() {}

    /**
     * Crée un InputStream pour un body de taille fixe (Content-Length).
     */
    static InputStream fixedLength(ByteBuffer buffer, ReadableByteChannel channel, long contentLength) {
        return new FixedLengthInputStream(buffer, channel, contentLength);
    }

    /**
     * Crée un InputStream pour un body en chunked transfer encoding.
     */
    static InputStream chunked(ByteBuffer buffer, ReadableByteChannel channel) {
        return new ChunkedInputStream(buffer, channel);
    }

    /**
     * Draine les octets restants du body (pour keep-alive quand le handler
     * n'a pas lu le body).
     */
    static void drain(InputStream bodyStream) throws IOException {
        if (bodyStream == null) return;
        byte[] buf = new byte[4096];
        while (bodyStream.read(buf) != -1) {
            // discard
        }
    }
}
