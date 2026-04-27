package io.vidocq.chappe.http.h2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * InputStream alimenté par la queue de DATA frames d'un stream HTTP/2.
 * <p>
 * Bloque sur {@link Http2Stream#takeData()} quand aucune donnée n'est disponible
 * (naturel sur virtual threads).
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

    private boolean ensureBuffer() throws IOException {
        while (current == null || !current.hasRemaining()) {
            if (stream.isEndStreamReceived() && stream.isDataQueueEmpty()) {
                return false;
            }
            try {
                current = stream.takeData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading HTTP/2 body", e);
            }
            if (!current.hasRemaining() && stream.isEndStreamReceived()) {
                return false;
            }
        }
        return true;
    }
}
