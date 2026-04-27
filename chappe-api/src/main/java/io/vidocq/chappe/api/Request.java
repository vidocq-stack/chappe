package io.vidocq.chappe.api;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Requête HTTP — vue en lecture seule exposée aux {@link Handler handlers}.
 * <p>
 * Les implémentations (dans {@code chappe-http}) peuvent réutiliser
 * et recycler les instances pour minimiser les allocations.
 */
public interface Request {

    /** Méthode HTTP (GET, POST, …). */
    HttpMethod method();

    /** URI complète de la requête. */
    URI uri();

    /** Chemin de la requête (sans query string). */
    String path();

    /** Query string brute, ou {@code null} si absente. */
    String query();

    /** Version du protocole HTTP. */
    HttpVersion version();

    /** En-têtes de la requête. */
    Headers headers();

    /** Corps de la requête. */
    Body body();

    /** Raccourci : première valeur de l'en-tête {@code name}. */
    default Optional<String> header(String name) {
        return headers().first(name);
    }

    /**
     * Paramètres de chemin capturés par le routeur (ex. {@code {id} → "42"}).
     * Vide si le routeur n'a pas matché de paramètres.
     */
    Map<String, String> pathParams();

    /**
     * Paramètres de la query string.
     * En cas de clé dupliquée, seule la dernière valeur est conservée.
     */
    Map<String, String> queryParams();

    /** Raccourci : valeur d'un paramètre de query string. */
    default Optional<String> queryParam(String name) {
        return Optional.ofNullable(queryParams().get(name));
    }

    /** Context path set by mount(), empty string "" by default. */
    default String contextPath() { return ""; }

    /** Path after context stripping. For unmounted handlers, equals path(). */
    default String pathInfo() { return path(); }

    /** Per-request mutable attribute (for Servlet/JAX-RS state sharing). */
    default Object attribute(String key) { return null; }

    /** Sets a per-request attribute. Returns this for chaining. */
    default Request attribute(String key, Object value) { return this; }

    /** Remote (client) socket address. */
    default java.net.InetSocketAddress remoteAddress() { return null; }

    /** Local (server) socket address. */
    default java.net.InetSocketAddress localAddress() { return null; }

    /** True if the connection is TLS-secured. */
    default boolean isSecure() { return false; }

    /** URI scheme: "http" or "https". */
    default String scheme() { return isSecure() ? "https" : "http"; }
}
