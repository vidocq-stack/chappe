package fr.vidocq.chappe.api;

import java.io.ByteArrayInputStream;
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
}
