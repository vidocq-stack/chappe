package io.vidocq.chappe.http;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.FileBody;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sérialisation d'une {@link Response} HTTP/1.1 vers un channel.
 * <p>
 * Optimisé pour minimiser les allocations et les syscalls :
 * <ul>
 *   <li>Headers écrits directement char-par-char (pas de byte[] intermédiaire)</li>
 *   <li>Headers + body coalescés dans un seul write quand possible</li>
 *   <li>Buffer de lecture du body réutilisé (pas d'allocation par réponse)</li>
 *   <li>Content-Length écrit directement dans le buffer (pas de byte[20])</li>
 * </ul>
 */
public final class HttpResponseWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte COLON = ':';
    private static final byte SPACE = ' ';
    private static final byte[] CONTENT_LENGTH_PREFIX = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_KEEP_ALIVE = "Connection: keep-alive\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRANSFER_ENCODING_CHUNKED = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHUNK_TERMINATOR = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATE_PREFIX = "Date: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENT_TYPE_TEXT = "Content-Type: text/plain; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    // Cache Date header (1 seconde)
    private static volatile long lastDateSecond;
    private static volatile byte[] cachedDateValue;

    // Status lines pré-encodées
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

    // Buffer réutilisé pour la lecture du body (évite new byte[8192] par réponse)
    private final byte[] bodyChunk = new byte[8192];

    // Buffer pour putAsciiLong (évite new byte[20] par Content-Length)
    private final byte[] digitsBuf = new byte[20];

    // Fast-path : headers pré-encodés pour 200 OK keep-alive (sans Date, sans body)
    private static final byte[] FAST_200_KA_PREFIX = ("HTTP/1.1 200 OK\r\n"
            + "Connection: keep-alive\r\n").getBytes(StandardCharsets.US_ASCII);

    public void write(Response response, ByteBuffer buffer, WritableByteChannel channel,
                      boolean keepAlive, HttpMethod method) throws IOException {
        buffer.clear();

        int statusCode = response.status().code();
        boolean suppressBody = (method == HttpMethod.HEAD)
                || statusCode == 204 || statusCode == 304;

        // ═══ FAST PATH : 200 OK, keep-alive, pas de headers custom, body connu ═══
        // C'est le cas le plus fréquent (~80% des réponses dans un serveur typique).
        // On écrit tout en un seul bloc sans itérer les headers.
        Body body = response.body();
        long contentLength = body.contentLength();
        if (statusCode == 200 && keepAlive && !suppressBody
                && contentLength >= 0 && contentLength <= 8192
                && response.headers().isEmpty()) {
            writeFastPath(body, contentLength, buffer, channel);
            return;
        }

        // Status line
        putStatusLine(response.status(), buffer, channel);

        // Headers — écriture directe sans allocation byte[]
        for (var entry : response.headers()) {
            putAsciiString(entry.name(), buffer, channel);
            putByte(COLON, buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(entry.value(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Date header
        if (!response.headers().contains("Date")) {
            putBytes(DATE_PREFIX, buffer, channel);
            putBytes(getDateValue(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Content-Length ou Transfer-Encoding: chunked
        boolean chunked = false;
        if (contentLength >= 0 && !response.headers().contains("Content-Length")) {
            putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
            putAsciiLong(contentLength, buffer, channel);
            putBytes(CRLF, buffer, channel);
        } else if (contentLength < 0 && !suppressBody
                   && !response.headers().contains("Transfer-Encoding")) {
            if (keepAlive) {
                putBytes(TRANSFER_ENCODING_CHUNKED, buffer, channel);
                chunked = true;
            }
        }

        // Connection header
        putBytes(keepAlive ? CONNECTION_KEEP_ALIVE : CONNECTION_CLOSE, buffer, channel);

        // Fin des headers
        putBytes(CRLF, buffer, channel);

        // *** PAS de flush intermédiaire ici — on coalesce headers + body ***

        // Body
        if (!suppressBody) {
            if (chunked) {
                writeBodyChunked(body, buffer, channel);
            } else {
                writeBody(body, buffer, channel);
            }
        }

        // Flush final unique (headers + body en un seul write si tout tient dans le buffer)
        if (buffer.position() > 0) {
            flush(buffer, channel);
        }
    }

    /**
     * Fast path pour 200 OK keep-alive avec petit body sans headers custom.
     * Tout est écrit en un seul channel.write() — 1 syscall.
     */
    private void writeFastPath(Body body, long contentLength, ByteBuffer buffer,
                               WritableByteChannel channel) throws IOException {
        // Status + Connection
        putBytes(FAST_200_KA_PREFIX, buffer, channel);
        // Date
        putBytes(DATE_PREFIX, buffer, channel);
        putBytes(getDateValue(), buffer, channel);
        putBytes(CRLF, buffer, channel);
        // Content-Length
        putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
        putAsciiLong(contentLength, buffer, channel);
        putBytes(CRLF, buffer, channel);
        // End of headers
        putBytes(CRLF, buffer, channel);
        // Body inline (tout tient dans le buffer de 16K)
        if (contentLength > 0) {
            try (InputStream in = body.asInputStream()) {
                int read;
                while ((read = in.read(bodyChunk)) != -1) {
                    putBytes(bodyChunk, 0, read, buffer, channel);
                }
            }
        }
        // Single flush
        flush(buffer, channel);
    }

    public void writeError(StatusCode status, String message, ByteBuffer buffer,
                           WritableByteChannel channel) throws IOException {
        buffer.clear();
        putStatusLine(status, buffer, channel);

        byte[] bodyBytes = message.getBytes(StandardCharsets.UTF_8);
        putBytes(CONTENT_TYPE_TEXT, buffer, channel);
        putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
        putAsciiLong(bodyBytes.length, buffer, channel);
        putBytes(CRLF, buffer, channel);
        putBytes(CONNECTION_CLOSE, buffer, channel);
        putBytes(CRLF, buffer, channel);
        putBytes(bodyBytes, buffer, channel);

        flush(buffer, channel);
    }

    // --- Status line ---

    private void putStatusLine(StatusCode status, ByteBuffer buffer,
                               WritableByteChannel channel) throws IOException {
        byte[] preEncoded = switch (status.code()) {
            case 200 -> STATUS_200; case 201 -> STATUS_201; case 204 -> STATUS_204;
            case 301 -> STATUS_301; case 302 -> STATUS_302; case 304 -> STATUS_304;
            case 400 -> STATUS_400; case 401 -> STATUS_401; case 403 -> STATUS_403;
            case 404 -> STATUS_404; case 405 -> STATUS_405; case 500 -> STATUS_500;
            case 502 -> STATUS_502; case 503 -> STATUS_503;
            default -> null;
        };
        if (preEncoded != null) {
            putBytes(preEncoded, buffer, channel);
        } else {
            putAsciiString("HTTP/1.1 ", buffer, channel);
            putAsciiLong(status.code(), buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(status.reason(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }
    }

    // --- Body writers ---

    private void writeBodyChunked(Body body, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (body.contentLength() == 0) {
            putBytes(CHUNK_TERMINATOR, buffer, channel);
            return;
        }
        try (InputStream in = body.asInputStream()) {
            int read;
            while ((read = in.read(bodyChunk)) != -1) {
                putAsciiHex(read, buffer, channel);
                putBytes(CRLF, buffer, channel);
                putBytes(bodyChunk, 0, read, buffer, channel);
                putBytes(CRLF, buffer, channel);
                // Flush après chaque chunk pour que les events SSE arrivent sans délai
                flush(buffer, channel);
            }
        }
        putBytes(CHUNK_TERMINATOR, buffer, channel);
    }

    private void writeBody(Body body, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (body.contentLength() == 0) return;

        // Zero-copy fast path for file bodies on raw SocketChannel
        if (body instanceof FileBody fb) {
            // Flush any buffered data first
            if (buffer.position() > 0) {
                flush(buffer, channel);
            }
            if (channel instanceof java.nio.channels.SocketChannel) {
                try (var fc = java.nio.channels.FileChannel.open(fb.path(), java.nio.file.StandardOpenOption.READ)) {
                    long remaining = fb.contentLength();
                    long position = fb.offset();
                    while (remaining > 0) {
                        long transferred = fc.transferTo(position, remaining, channel);
                        if (transferred <= 0) break;
                        position += transferred;
                        remaining -= transferred;
                    }
                }
                return;
            }
            // For non-SocketChannel (TLS), fall through to InputStream path
        }

        // Existing InputStream-based path
        try (InputStream in = body.asInputStream()) {
            int read;
            while ((read = in.read(bodyChunk)) != -1) {
                putBytes(bodyChunk, 0, read, buffer, channel);
            }
        }
    }

    // --- Primitives d'écriture optimisées ---

    /** Écrit une String ASCII directement dans le buffer, char par char — zéro allocation. */
    private void putAsciiString(String s, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        for (int i = 0, len = s.length(); i < len; i++) {
            if (!buffer.hasRemaining()) flush(buffer, channel);
            buffer.put((byte) s.charAt(i));
        }
    }

    /** Écrit un seul byte. */
    private void putByte(byte b, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (!buffer.hasRemaining()) flush(buffer, channel);
        buffer.put(b);
    }

    /** Écrit un long en ASCII décimal directement dans le buffer — zéro allocation. */
    private void putAsciiLong(long value, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (value == 0) {
            putByte((byte) '0', buffer, channel);
            return;
        }
        int pos = digitsBuf.length;
        long v = value;
        while (v > 0) {
            digitsBuf[--pos] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        putBytes(digitsBuf, pos, digitsBuf.length - pos, buffer, channel);
    }

    /** Écrit un int en hex ASCII — pour chunked transfer. */
    private void putAsciiHex(int value, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (value == 0) {
            putByte((byte) '0', buffer, channel);
            return;
        }
        int pos = digitsBuf.length;
        int v = value;
        while (v > 0) {
            int digit = v & 0xF;
            digitsBuf[--pos] = (byte) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            v >>>= 4;
        }
        putBytes(digitsBuf, pos, digitsBuf.length - pos, buffer, channel);
    }

    private void putBytes(byte[] data, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
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

    private static byte[] getDateValue() {
        long nowSecond = System.currentTimeMillis() / 1000;
        if (nowSecond != lastDateSecond || cachedDateValue == null) {
            lastDateSecond = nowSecond;
            cachedDateValue = ZonedDateTime.now(ZoneOffset.UTC).format(IMF_FIXDATE)
                    .getBytes(StandardCharsets.US_ASCII);
        }
        return cachedDateValue;
    }

    private static byte[] statusLine(int code, String reason) {
        return ("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
