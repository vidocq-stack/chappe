package fr.vidocq.chappe.http;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Headers;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Sérialisation d'une {@link Response} HTTP/1.1 vers un channel.
 * <p>
 * Utilise des fragments pré-encodés pour les status lines courantes
 * et écrit via un buffer de coalescing pour minimiser les syscalls.
 */
public final class HttpResponseWriter {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COLON_SPACE = ": ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENT_LENGTH_PREFIX = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_KEEP_ALIVE = "Connection: keep-alive\r\n".getBytes(StandardCharsets.US_ASCII);

    // Status lines pré-encodées pour les codes courants
    private static final byte[] STATUS_200 = statusLine(200, "OK");
    private static final byte[] STATUS_201 = statusLine(201, "Created");
    private static final byte[] STATUS_204 = statusLine(204, "No Content");
    private static final byte[] STATUS_301 = statusLine(301, "Moved Permanently");
    private static final byte[] STATUS_302 = statusLine(302, "Found");
    private static final byte[] STATUS_304 = statusLine(304, "Not Modified");
    private static final byte[] STATUS_400 = statusLine(400, "Bad Request");
    private static final byte[] STATUS_401 = statusLine(401, "Unauthorized");
    private static final byte[] STATUS_403 = statusLine(403, "Forbidden");
    private static final byte[] STATUS_404 = statusLine(404, "Not Found");
    private static final byte[] STATUS_405 = statusLine(405, "Method Not Allowed");
    private static final byte[] STATUS_500 = statusLine(500, "Internal Server Error");
    private static final byte[] STATUS_502 = statusLine(502, "Bad Gateway");
    private static final byte[] STATUS_503 = statusLine(503, "Service Unavailable");

    /**
     * Écrit une réponse complète vers le channel.
     *
     * @param response  la réponse à écrire
     * @param buffer    le buffer d'écriture (propriété de la connexion)
     * @param channel   le channel socket
     * @param keepAlive si true, ajoute Connection: keep-alive
     */
    public void write(Response response, ByteBuffer buffer, WritableByteChannel channel,
                      boolean keepAlive) throws IOException {
        buffer.clear();

        // Status line
        putStatusLine(response.status(), buffer, channel);

        // Headers de la réponse
        for (var entry : response.headers()) {
            putBytes(entry.name().getBytes(StandardCharsets.US_ASCII), buffer, channel);
            putBytes(COLON_SPACE, buffer, channel);
            putBytes(entry.value().getBytes(StandardCharsets.US_ASCII), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Content-Length si connu et pas déjà défini
        Body body = response.body();
        long contentLength = body.contentLength();
        if (contentLength >= 0 && !response.headers().contains("Content-Length")) {
            putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
            putAsciiLong(contentLength, buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Connection header
        putBytes(keepAlive ? CONNECTION_KEEP_ALIVE : CONNECTION_CLOSE, buffer, channel);

        // Fin des headers
        putBytes(CRLF, buffer, channel);

        // Flush les headers
        flush(buffer, channel);

        // Body
        writeBody(body, buffer, channel);
    }

    /**
     * Écrit une réponse d'erreur minimale (quand le dispatch échoue).
     */
    public void writeError(StatusCode status, String message, ByteBuffer buffer,
                           WritableByteChannel channel) throws IOException {
        buffer.clear();

        putStatusLine(status, buffer, channel);

        byte[] bodyBytes = message.getBytes(StandardCharsets.UTF_8);

        putBytes("Content-Type: text/plain; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII), buffer, channel);
        putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
        putAsciiLong(bodyBytes.length, buffer, channel);
        putBytes(CRLF, buffer, channel);
        putBytes(CONNECTION_CLOSE, buffer, channel);
        putBytes(CRLF, buffer, channel);

        putBytes(bodyBytes, buffer, channel);

        flush(buffer, channel);
    }

    // --- Helpers internes ---

    private void putStatusLine(StatusCode status, ByteBuffer buffer,
                               WritableByteChannel channel) throws IOException {
        byte[] preEncoded = switch (status.code()) {
            case 200 -> STATUS_200;
            case 201 -> STATUS_201;
            case 204 -> STATUS_204;
            case 301 -> STATUS_301;
            case 302 -> STATUS_302;
            case 304 -> STATUS_304;
            case 400 -> STATUS_400;
            case 401 -> STATUS_401;
            case 403 -> STATUS_403;
            case 404 -> STATUS_404;
            case 405 -> STATUS_405;
            case 500 -> STATUS_500;
            case 502 -> STATUS_502;
            case 503 -> STATUS_503;
            default -> null;
        };

        if (preEncoded != null) {
            putBytes(preEncoded, buffer, channel);
        } else {
            putBytes(("HTTP/1.1 " + status.code() + " " + status.reason() + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII), buffer, channel);
        }
    }

    private void writeBody(Body body, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        long contentLength = body.contentLength();
        if (contentLength == 0) return;

        try (InputStream in = body.asInputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                int off = 0;
                while (off < read) {
                    int space = buffer.remaining();
                    if (space == 0) {
                        flush(buffer, channel);
                        space = buffer.remaining();
                    }
                    int toPut = Math.min(read - off, space);
                    buffer.put(chunk, off, toPut);
                    off += toPut;
                }
            }
        }
        flush(buffer, channel);
    }

    private void putBytes(byte[] data, ByteBuffer buffer,
                          WritableByteChannel channel) throws IOException {
        int off = 0;
        while (off < data.length) {
            int space = buffer.remaining();
            if (space == 0) {
                flush(buffer, channel);
                space = buffer.remaining();
            }
            int toPut = Math.min(data.length - off, space);
            buffer.put(data, off, toPut);
            off += toPut;
        }
    }

    private void putAsciiLong(long value, ByteBuffer buffer,
                              WritableByteChannel channel) throws IOException {
        if (value == 0) {
            if (!buffer.hasRemaining()) flush(buffer, channel);
            buffer.put((byte) '0');
            return;
        }

        // Max 20 digits pour un long
        byte[] digits = new byte[20];
        int pos = digits.length;
        long v = value;
        while (v > 0) {
            digits[--pos] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        putBytes(digits, pos, digits.length - pos, buffer, channel);
    }

    private void putBytes(byte[] data, int off, int len, ByteBuffer buffer,
                          WritableByteChannel channel) throws IOException {
        int end = off + len;
        while (off < end) {
            int space = buffer.remaining();
            if (space == 0) {
                flush(buffer, channel);
                space = buffer.remaining();
            }
            int toPut = Math.min(end - off, space);
            buffer.put(data, off, toPut);
            off += toPut;
        }
    }

    private void flush(ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    private static byte[] statusLine(int code, String reason) {
        return ("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
