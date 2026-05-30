package io.vidocq.chappe.api;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

/**
 * HTTP body (request or response).
 * <p>
 * Two access modes:
 * <ul>
 *   <li>{@link #asInputStream()} — blocking read, natural with virtual threads</li>
 *   <li>{@link #asPublisher()} — reactive streaming for HTTP/2 and large payloads</li>
 * </ul>
 */
public interface Body {

    /**
     * Content length in bytes, or {@code -1} if unknown
     * (chunked transfer, streaming).
     */
    long contentLength();

    /** Blocking access to the body as an InputStream. */
    InputStream asInputStream();

    /** Reactive access to the body as a Publisher of ByteBuffers. */
    Flow.Publisher<ByteBuffer> asPublisher();

    /** Empty body (singleton). */
    static Body empty() {
        return EmptyBody.INSTANCE;
    }

    /** Body from a byte array. */
    static Body of(byte[] bytes) {
        if (bytes.length == 0) {
            return empty();
        }
        return new ByteArrayBody(bytes);
    }

    /** Body from a string with the specified encoding. */
    static Body of(String text, Charset charset) {
        return of(text.getBytes(charset));
    }

    /** Body from a UTF-8 string. */
    static Body of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /** Body from an InputStream with a known length. */
    static Body of(InputStream stream, long contentLength) {
        return new InputStreamBody(stream, contentLength);
    }

    /** Body from an InputStream of unknown length. */
    static Body of(InputStream stream) {
        return of(stream, -1);
    }

    /** Body backed by a file. Zero-copy via FileChannel.transferTo when possible. */
    static Body ofFile(java.nio.file.Path path) {
        return new FileBody(path, 0, -1);
    }

    /** Body backed by a file range (for HTTP Range support). */
    static Body ofFile(java.nio.file.Path path, long offset, long length) {
        return new FileBody(path, offset, length);
    }

    /** Body from a writer callback. Invoked lazily on a virtual thread. Content-Length unknown (chunked). */
    static Body ofOutputStream(java.util.function.Consumer<java.io.OutputStream> writer) {
        return new OutputStreamBody(writer);
    }

    /**
     * Body streamed from an InputStream of unknown length.
     * <p>
     * Chappe sends this body using chunked transfer (HTTP/1.1) or successive DATA frames
     * (HTTP/2), without buffering the entire content. EOF on the stream closes the response.
     * <p>
     * Typical SSE usage with a PipedInputStream:
     * <pre>{@code
     *   var pis = new PipedInputStream(8192);
     *   var pos = new PipedOutputStream(pis);
     *   Thread.startVirtualThread(() -> { /* write SSE events to pos *\/ });
     *   return Response.builder()
     *       .header("Content-Type", "text/event-stream")
     *       .body(Body.streaming(pis))
     *       .build();
     * }</pre>
     */
    static Body streaming(InputStream in) {
        return of(in, -1);
    }
}
