package io.vidocq.chappe.api;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Méthodes HTTP standard définies par la RFC 9110.
 */
public enum HttpMethod {

    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    CONNECT,
    OPTIONS,
    TRACE,
    PATCH;

    private static final Map<String, HttpMethod> LOOKUP =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(
                    m -> m.name().toUpperCase(), m -> m));

    /**
     * Résout une méthode HTTP à partir de sa représentation textuelle.
     * La comparaison est insensible à la casse.
     *
     * @param method le nom de la méthode (ex. {@code "GET"})
     * @return la constante correspondante
     * @throws IllegalArgumentException si la méthode est inconnue
     */
    public static HttpMethod of(String method) {
        var m = LOOKUP.get(method.toUpperCase());
        if (m == null) {
            throw new IllegalArgumentException("Unknown HTTP method: " + method);
        }
        return m;
    }
}
