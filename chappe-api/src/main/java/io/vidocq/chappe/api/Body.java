package io.vidocq.chappe.api;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

/**
 * Corps d'un message HTTP (requête ou réponse).
 * <p>
 * Deux modes d'accès :
 * <ul>
 *   <li>{@link #asInputStream()} — lecture bloquante, naturelle avec les virtual threads</li>
 *   <li>{@link #asPublisher()} — streaming réactif pour HTTP/2 et les gros payloads</li>
 * </ul>
 */
public interface Body {

    /**
     * Longueur du contenu en octets, ou {@code -1} si inconnue
     * (chunked transfer, streaming).
     */
    long contentLength();

    /** Accès bloquant au corps sous forme d'InputStream. */
    InputStream asInputStream();

    /** Accès réactif au corps sous forme de Publisher de ByteBuffer. */
    Flow.Publisher<ByteBuffer> asPublisher();

    /** Corps vide (singleton). */
    static Body empty() {
        return EmptyBody.INSTANCE;
    }

    /** Corps à partir d'un tableau d'octets. */
    static Body of(byte[] bytes) {
        if (bytes.length == 0) {
            return empty();
        }
        return new ByteArrayBody(bytes);
    }

    /** Corps à partir d'une chaîne avec l'encodage spécifié. */
    static Body of(String text, Charset charset) {
        return of(text.getBytes(charset));
    }

    /** Corps à partir d'une chaîne UTF-8. */
    static Body of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /** Corps à partir d'un InputStream avec longueur connue. */
    static Body of(InputStream stream, long contentLength) {
        return new InputStreamBody(stream, contentLength);
    }

    /** Corps à partir d'un InputStream de longueur inconnue. */
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
     * Corps streamé depuis un InputStream de longueur inconnue.
     * <p>
     * Chappe envoie ce body en chunked transfer (HTTP/1.1) ou en DATA frames successives
     * (HTTP/2), sans bufferiser l'intégralité. EOF sur le stream ferme la réponse.
     * <p>
     * Usage SSE typique avec un PipedInputStream :
     * <pre>{@code
     *   var pis = new PipedInputStream(8192);
     *   var pos = new PipedOutputStream(pis);
     *   Thread.startVirtualThread(() -> { /* écrire les events SSE dans pos *\/ });
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
