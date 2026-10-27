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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.BuildInfo;
import io.vidocq.chappe.api.FileBody;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.internal.HeaderFields;

/**
 * Serialisation of an HTTP/1.1 {@link Response} to a channel.
 * <p>
 * Optimised to minimise allocations and syscalls:
 * <ul>
 *   <li>Headers written directly char-by-char (no intermediate byte[])</li>
 *   <li>Headers + body coalesced into a single write when possible</li>
 *   <li>Body read buffer reused (no per-response allocation)</li>
 *   <li>Content-Length written directly into the buffer (no byte[20])</li>
 * </ul>
 */
public final class HttpResponseWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte COLON = ':';
    private static final byte SPACE = ' ';
    private static final byte[] CONTENT_LENGTH_PREFIX = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTION_KEEP_ALIVE =
            "Connection: keep-alive\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRANSFER_ENCODING_CHUNKED =
            "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHUNK_TERMINATOR = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LAST_CHUNK = "0\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATE_PREFIX = "Date: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENT_TYPE_TEXT =
            "Content-Type: text/plain; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII);
    // The Server header names the product alone by default, so that a public server does not tell which
    // version to aim at, and adds the version and build only when the server exposes its build info
    // (ServerConfig.exposeBuildInfo).
    private static final byte[] SERVER_DISCREET_LINE = "Server: Chappe\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_BUILD_LINE =
            ("Server: " + BuildInfo.serverHeader() + "\r\n").getBytes(StandardCharsets.US_ASCII);
    // X-Chappe-Build, written only with the build: the Server value under a non-standard name that reverse
    // proxies (NPM/openresty) usually do not rewrite, to identify the binary behind a proxy that sets its
    // own Server header.
    private static final byte[] X_CHAPPE_BUILD_LINE =
            ("X-Chappe-Build: " + BuildInfo.serverHeader() + "\r\n").getBytes(StandardCharsets.US_ASCII);

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    // Date header cache (1 second) — array published atomically via volatile reference,
    // never mutated after assignment: thread-safe immutable-object pattern.
    private static volatile long lastDateSecond;
    private static volatile byte[] cachedDateValue;

    // Pre-encoded status lines
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

    // Reused buffer for body reads (avoids new byte[8192] per response)
    private final byte[] bodyChunk = new byte[8192];

    // Buffer for putAsciiLong (avoids new byte[20] per Content-Length)
    private final byte[] digitsBuf = new byte[20];

    // The identity of this writer's responses: the Server line, the X-Chappe-Build line or null, and the
    // pre-encoded head of the fast path that holds them.
    private final byte[] serverLine;
    private final byte[] buildLine;
    private final byte[] fast200Prefix;

    // Bound for a single blocking channel.write() call within the current
    // write()/writeError() invocation — set at entry, read by flush(). This
    // writer is owned by exactly one HttpConnection and used single-threaded,
    // one request at a time, so a plain instance field (not a per-call
    // parameter threaded through every private helper) is safe.
    private Duration writeTimeout = Duration.ZERO;

    // The connection's watchdog, which bounds each channel.write() without a thread per write; null
    // for a writer used outside a connection, whose writes are bounded by BoundedWrites.
    private ConnectionWatchdog watchdog;

    // Whether a flush happened since the status line of the current write() was started: an
    // invalid header found after that can no longer be answered with a 500 (CHAPPE-008).
    private boolean headFlushed;

    // Fast path: pre-encoded headers for 200 OK keep-alive (without Date, without body), without the build
    // or with it
    private static final byte[] FAST_200_KA_PREFIX_DISCREET = """
            HTTP/1.1 200 OK\r
            Connection: keep-alive\r
            Content-Type: text/plain; charset=utf-8\r
            Server: Chappe\r
            """.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FAST_200_KA_PREFIX_BUILD = ("HTTP/1.1 200 OK\r\n"
                    + "Connection: keep-alive\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Server: " + BuildInfo.serverHeader() + "\r\n"
                    + "X-Chappe-Build: " + BuildInfo.serverHeader() + "\r\n")
            .getBytes(StandardCharsets.US_ASCII);

    /** A writer whose responses name Chappe without its version. */
    public HttpResponseWriter() {
        this(false);
    }

    /**
     * A writer whose responses name Chappe, with its version and build in {@code Server} and in
     * {@code X-Chappe-Build} when {@code exposeBuildInfo} is {@code true} ({@code ServerConfig.exposeBuildInfo}).
     */
    public HttpResponseWriter(boolean exposeBuildInfo) {
        this.serverLine = exposeBuildInfo ? SERVER_BUILD_LINE : SERVER_DISCREET_LINE;
        this.buildLine = exposeBuildInfo ? X_CHAPPE_BUILD_LINE : null;
        this.fast200Prefix = exposeBuildInfo ? FAST_200_KA_PREFIX_BUILD : FAST_200_KA_PREFIX_DISCREET;
    }

    /** Bounds the writes of this writer by the connection's watchdog, instead of a thread per write. */
    void boundWritesBy(ConnectionWatchdog watchdog) {
        this.watchdog = watchdog;
    }

    public void write(
            Response response,
            ByteBuffer buffer,
            WritableByteChannel channel,
            boolean keepAlive,
            HttpMethod method,
            Duration writeTimeout)
            throws IOException {
        this.writeTimeout = writeTimeout;
        buffer.clear();

        int statusCode = response.status().code();
        boolean suppressBody = (method == HttpMethod.HEAD) || statusCode == 204 || statusCode == 304;

        // ═══ FAST PATH: 200 OK, keep-alive, known body, text/plain Content-Type ═══
        // The pre-encoded prefix includes Content-Type: text/plain; charset=utf-8 — enable it
        // only if the response has exactly that header (typical Response.ok(String) case).
        // Rejecting headerCount==0 avoids lying to clients with a fake Content-Type.
        Body body = response.body();
        long contentLength = body.contentLength();
        if (statusCode == 200
                && keepAlive
                && !suppressBody
                && contentLength >= 0
                && contentLength <= 8192
                && response.headers().size() == 1
                && "text/plain; charset=utf-8".equals(response.headers().firstOrNull("Content-Type"))) {
            writeFastPath(body, contentLength, buffer, channel);
            return;
        }

        // Status line. Each field is read once, checked, then written from that same read: a
        // custom Response or Headers cannot hand back other text after the check (CHAPPE-008).
        StatusCode status = response.status();
        int code = status.code();
        String reason = status.reason();
        checkStatusLine(code, reason);
        headFlushed = false;
        putStatusLine(code, reason, buffer, channel);

        Headers headers = response.headers();
        putHeaders(headers, buffer, channel);
        boolean chunked = putFraming(headers, contentLength, suppressBody, keepAlive, buffer, channel);

        // Connection header
        putBytes(keepAlive ? CONNECTION_KEEP_ALIVE : CONNECTION_CLOSE, buffer, channel);

        // End of headers
        putBytes(CRLF, buffer, channel);

        // *** NO intermediate flush here — coalesce headers + body ***

        // Body
        if (!suppressBody) {
            if (chunked) {
                writeBodyChunked(response, body, buffer, channel);
            } else {
                writeBody(body, buffer, channel);
            }
        }

        // Single final flush (headers + body in one write if everything fits in the buffer)
        if (buffer.position() > 0) {
            flush(buffer, channel);
        }
    }

    /**
     * Writes the application's headers, then {@code Date}, {@code Server} and {@code X-Chappe-Build}
     * unless the application set them.
     */
    private void putHeaders(Headers headers, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        // Headers — direct write without allocating byte[]
        for (var entry : headers) {
            String name = entry.name();
            String value = entry.value();
            if (!HeaderFields.isValid(name, value)) {
                refuseHeader(name);
            }
            putAsciiString(name, buffer, channel);
            putByte(COLON, buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(value, buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Date header
        if (!headers.contains("Date")) {
            putBytes(DATE_PREFIX, buffer, channel);
            putBytes(getDateValue(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }

        // Server header — unless the app set its own identity
        if (!headers.contains("Server")) {
            putBytes(serverLine, buffer, channel);
        }
        // X-Chappe-Build, when the build is exposed: non-standard header that survives behind reverse
        // proxies that rewrite the Server header (NPM, openresty, oauth2-proxy).
        if (buildLine != null && !headers.contains("X-Chappe-Build")) {
            putBytes(buildLine, buffer, channel);
        }
    }

    /**
     * Writes {@code Content-Length} or {@code Transfer-Encoding: chunked} unless the application set
     * it; a body of unknown length is chunked on a keep-alive connection only.
     *
     * @return whether the body must be written chunked
     */
    private boolean putFraming(
            Headers headers,
            long contentLength,
            boolean suppressBody,
            boolean keepAlive,
            ByteBuffer buffer,
            WritableByteChannel channel)
            throws IOException {
        if (contentLength >= 0 && !headers.contains("Content-Length")) {
            putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
            putAsciiLong(contentLength, buffer, channel);
            putBytes(CRLF, buffer, channel);
        } else if (contentLength < 0 && !suppressBody && keepAlive && !headers.contains("Transfer-Encoding")) {
            putBytes(TRANSFER_ENCODING_CHUNKED, buffer, channel);
            return true;
        }
        return false;
    }

    /**
     * Fast path for 200 OK keep-alive with a small body and no custom headers.
     * Everything is written in a single channel.write() — 1 syscall.
     */
    private void writeFastPath(Body body, long contentLength, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        // Status + Connection
        putBytes(fast200Prefix, buffer, channel);
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
        // Inline body (everything fits in the 16K buffer)
        if (contentLength > 0) {
            try (InputStream in = body.asInputStream()) {
                boolean flushPerRead = isLiveStream(body, in);
                int read;
                while ((read = in.read(bodyChunk)) != -1) {
                    putBytes(bodyChunk, 0, read, buffer, channel);
                    if (flushPerRead && read > 0 && mayBlock(in)) {
                        flush(buffer, channel);
                    }
                }
            }
        }
        // Single flush
        flush(buffer, channel);
    }

    public void writeError(
            StatusCode status, String message, ByteBuffer buffer, WritableByteChannel channel, Duration writeTimeout)
            throws IOException {
        this.writeTimeout = writeTimeout;
        buffer.clear();
        putStatusLine(status, buffer, channel);

        byte[] bodyBytes = message.getBytes(StandardCharsets.UTF_8);
        putBytes(CONTENT_TYPE_TEXT, buffer, channel);
        putBytes(serverLine, buffer, channel);
        if (buildLine != null) {
            putBytes(buildLine, buffer, channel);
        }
        putBytes(CONTENT_LENGTH_PREFIX, buffer, channel);
        putAsciiLong(bodyBytes.length, buffer, channel);
        putBytes(CRLF, buffer, channel);
        putBytes(CONNECTION_CLOSE, buffer, channel);
        putBytes(CRLF, buffer, channel);
        putBytes(bodyBytes, buffer, channel);

        flush(buffer, channel);
    }

    /**
     * Writes the head of a connection-upgrade response — status line, the given headers, the
     * blank line — and flushes it. No {@code Date}, {@code Server}, {@code Connection} or
     * framing header is added. A header whose name is not a token or whose value is not a valid
     * field value is skipped, as for trailers ({@code ConnectionUpgrade} already rejects them).
     */
    public void writeUpgradeHead(
            StatusCode status, Headers headers, ByteBuffer buffer, WritableByteChannel channel, Duration writeTimeout)
            throws IOException {
        this.writeTimeout = writeTimeout;
        buffer.clear();
        putStatusLine(status, buffer, channel);
        for (var entry : headers) {
            if (!HeaderFields.isValid(entry.name(), entry.value())) {
                continue;
            }
            putAsciiString(entry.name(), buffer, channel);
            putByte(COLON, buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(entry.value(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }
        putBytes(CRLF, buffer, channel);
        flush(buffer, channel);
    }

    // --- Status line ---

    private void putStatusLine(StatusCode status, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        putStatusLine(status.code(), status.reason(), buffer, channel);
    }

    private void putStatusLine(int code, String reason, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        byte[] preEncoded = preEncodedStatusLine(code);
        if (preEncoded != null) {
            putBytes(preEncoded, buffer, channel);
        } else {
            putAsciiString("HTTP/1.1 ", buffer, channel);
            putAsciiLong(code, buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(reason, buffer, channel);
            putBytes(CRLF, buffer, channel);
        }
    }

    /** The pre-encoded status line of a common status code, or {@code null}. */
    private static byte[] preEncodedStatusLine(int code) {
        return switch (code) {
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
    }

    /**
     * Refuses a custom status line that cannot be written as is (CHAPPE-008): a code outside
     * {@code 100-999}, or a reason phrase holding CR, LF, NUL, another control or a char above
     * {@code U+00FF}. Called before anything is buffered, so the connection can still answer 500.
     */
    private static void checkStatusLine(int code, String reason) {
        if (preEncodedStatusLine(code) == null && (code < 100 || code > 999 || !HeaderFields.isFieldValue(reason))) {
            throw new InvalidResponseHeadException("Invalid status line for status " + code);
        }
    }

    /**
     * A header that cannot be written as is (CHAPPE-008): a name that is not an RFC 9110 token, or
     * a value holding CR, LF, NUL, another control or a char above {@code U+00FF}. The builder
     * already refuses such fields; this catches a response built without it. Nothing of the
     * invalid field is written. While no byte of the head has left the buffer, it throws an
     * {@link InvalidResponseHeadException} and the connection answers 500 instead; once part of a
     * very large head was flushed, it throws an {@link IOException} and the connection is dropped.
     * The message names the field and never echoes its value.
     */
    private void refuseHeader(String name) throws IOException {
        String message = HeaderFields.isToken(name)
                ? "Invalid value for response header " + name
                : "Invalid response header name: " + HeaderFields.printable(name);
        if (!headFlushed) {
            throw new InvalidResponseHeadException(message);
        }
        throw new IOException(message + " (head partly sent)");
    }

    // --- Body writers ---

    private void writeBodyChunked(Response response, Body body, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
        if (body.contentLength() != 0) {
            try (InputStream in = body.asInputStream()) {
                int read;
                while ((read = in.read(bodyChunk)) != -1) {
                    putAsciiHex(read, buffer, channel);
                    putBytes(CRLF, buffer, channel);
                    putBytes(bodyChunk, 0, read, buffer, channel);
                    putBytes(CRLF, buffer, channel);
                    // Flush after each chunk so SSE events arrive without delay
                    flush(buffer, channel);
                }
            }
        }
        // Response.trailers() is queried only now, once the body is fully consumed.
        writeLastChunk(response.trailers(), buffer, channel);
    }

    /**
     * Writes the last chunk and the trailer section (RFC 9112 §7.1.2):
     * {@code 0\r\n}, one {@code name: value\r\n} line per trailer value, then
     * the final {@code \r\n}.
     * <p>
     * A trailer is skipped unless its name is a non-empty RFC 9110 §5.6.2
     * token and its value contains only field-vchar ({@code 0x21-0x7E}),
     * obs-text ({@code 0x80-0xFF}), SP and HTAB (RFC 9110 §5.5). Any char
     * above {@code U+00FF} is rejected too, since it cannot be written as a
     * single byte and truncating it could forge CR/LF. A trailer can therefore
     * neither inject a field nor break the framing.
     * <p>
     * Which names may appear in a trailer section (RFC 9110 §6.5.1 forbids
     * framing, routing and similar fields such as {@code Content-Length} or
     * {@code Transfer-Encoding}) is left to the caller.
     */
    private void writeLastChunk(Headers trailers, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        if (trailers.isEmpty()) {
            putBytes(CHUNK_TERMINATOR, buffer, channel);
            return;
        }
        putBytes(LAST_CHUNK, buffer, channel);
        for (var entry : trailers) {
            if (!HeaderFields.isValid(entry.name(), entry.value())) {
                continue;
            }
            putAsciiString(entry.name(), buffer, channel);
            putByte(COLON, buffer, channel);
            putByte(SPACE, buffer, channel);
            putAsciiString(entry.value(), buffer, channel);
            putBytes(CRLF, buffer, channel);
        }
        putBytes(CRLF, buffer, channel);
    }

    /**
     * Whether a known-length body may be produced live, so its reads must be
     * checked for flushing (see {@link #mayBlock(InputStream)}). In-memory
     * bodies ({@code Body.of(byte[]|String)}, or any body over a
     * {@link ByteArrayInputStream}) and file bodies never are: they keep the
     * coalesced writes without even querying the stream.
     */
    private static boolean isLiveStream(Body body, InputStream in) {
        return !(body instanceof FileBody) && !(in instanceof ByteArrayInputStream);
    }

    /**
     * Whether the next read of a live body could block. The buffered bytes
     * are then flushed, so the client receives them while the producer is
     * still running. A stream with bytes ready ({@code available() > 0}), such
     * as a classpath or jar resource, keeps filling the write buffer and is
     * written in full-buffer writes.
     */
    private static boolean mayBlock(InputStream in) throws IOException {
        return in.available() <= 0;
    }

    private void writeBody(Body body, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        if (body.contentLength() == 0) return;

        // Zero-copy fast path for file bodies on raw SocketChannel
        if (body instanceof FileBody fb) {
            // Flush any buffered data first
            if (buffer.position() > 0) {
                flush(buffer, channel);
            }
            if (channel instanceof java.nio.channels.SocketChannel) {
                transferFile(fb, channel);
                return;
            }
            // For non-SocketChannel (TLS), fall through to InputStream path
        }

        // InputStream-based path. Bytes are coalesced into full write buffers,
        // except that a live stream is flushed whenever its next read could
        // block, so a partial body reaches the client while the producer is
        // still running (e.g. Servlet flushBuffer() with a declared
        // Content-Length).
        try (InputStream in = body.asInputStream()) {
            boolean flushPerRead = isLiveStream(body, in);
            int read;
            while ((read = in.read(bodyChunk)) != -1) {
                putBytes(bodyChunk, 0, read, buffer, channel);
                if (flushPerRead && read > 0 && mayBlock(in)) {
                    flush(buffer, channel);
                }
            }
        }
    }

    /** Sends a file body with {@code sendfile(2)}: zero copy, raw {@code SocketChannel} only. */
    @SuppressWarnings("ThreadPriorityCheck") // Thread.yield() = workaround JDK-8264762 (sendfile retry)
    private static void transferFile(FileBody fb, WritableByteChannel channel) throws IOException {
        try (var fc = java.nio.channels.FileChannel.open(fb.path(), java.nio.file.StandardOpenOption.READ)) {
            long remaining = fb.contentLength();
            long position = fb.offset();
            long transferred = 0;
            while (remaining > 0 && transferred >= 0) {
                transferred = fc.transferTo(position, remaining, channel);
                if (transferred == 0) {
                    // Kernel SO_SNDBUF saturated: sendfile(2) can return 0
                    // on a blocking SocketChannel (cf. JDK-8264762). Yield
                    // and retry — do NOT stop, or the response is truncated.
                    Thread.yield();
                } else if (transferred > 0) {
                    position += transferred;
                    remaining -= transferred;
                }
            }
        }
    }

    // --- Optimized write primitives ---

    /**
     * Writes a String directly into the buffer as ISO-8859-1, char by char — zero allocation.
     * Strict: a char above {@code U+00FF} has no one-byte form and is refused, never truncated
     * (its low byte could be CR or LF, CHAPPE-008). Callers validate first, so this is the last
     * line of defence.
     */
    private void putAsciiString(String s, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c > 0xFF) {
                throw new IOException("Refusing to write a char above U+00FF in a response head");
            }
            if (!buffer.hasRemaining()) flush(buffer, channel);
            buffer.put((byte) c);
        }
    }

    /** Writes a single byte. */
    private void putByte(byte b, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        if (!buffer.hasRemaining()) flush(buffer, channel);
        buffer.put(b);
    }

    /** Writes a long as decimal ASCII directly into the buffer — zero allocation. */
    private void putAsciiLong(long value, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
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

    /** Writes an int in ASCII hex — for chunked transfer. */
    private void putAsciiHex(int value, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
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

    private void putBytes(byte[] data, ByteBuffer buffer, WritableByteChannel channel) throws IOException {
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

    private void putBytes(byte[] data, int off, int len, ByteBuffer buffer, WritableByteChannel channel)
            throws IOException {
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

    /**
     * Flushes the buffer, bounding each individual {@code channel.write()}
     * call by {@link #writeTimeout} — how long the socket may refuse to
     * accept bytes we are trying to send. This bounds a single blocking
     * write, never the response as a whole: a streamed response with long
     * idle gaps between flushes (e.g. Server-Sent Events) is unaffected,
     * since {@code write} is only called — and only bounded — while there is
     * something to send.
     */
    private void flush(ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        headFlushed = true;
        buffer.flip();
        if (watchdog != null) {
            long timeoutNanos = ConnectionWatchdog.nanos(writeTimeout);
            while (buffer.hasRemaining()) {
                watchdog.write(channel, buffer, timeoutNanos);
            }
        } else {
            while (buffer.hasRemaining()) {
                BoundedWrites.writeWithTimeout(channel, channel, buffer, writeTimeout);
            }
        }
        buffer.clear();
    }

    private static byte[] getDateValue() {
        long nowSecond = System.currentTimeMillis() / 1000;
        if (nowSecond != lastDateSecond || cachedDateValue == null) {
            lastDateSecond = nowSecond;
            // ZonedDateTime.format + getBytes remains the bottleneck here.
            // Cache the final value.
            cachedDateValue =
                    ZonedDateTime.now(ZoneOffset.UTC).format(IMF_FIXDATE).getBytes(StandardCharsets.US_ASCII);
        }
        return cachedDateValue;
    }

    private static byte[] statusLine(int code, String reason) {
        return ("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
