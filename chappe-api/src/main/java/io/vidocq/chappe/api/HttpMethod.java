package io.vidocq.chappe.api;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standard HTTP methods defined by RFC 9110.
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

    private static final Map<String, HttpMethod> LOOKUP = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(m -> m.name().toUpperCase(Locale.ROOT), m -> m));

    /**
     * Resolves an HTTP method from its textual representation.
     * Comparison is case-insensitive.
     *
     * @param method the method name (for example {@code "GET"})
     * @return the matching constant
     * @throws IllegalArgumentException if the method is unknown
     */
    public static HttpMethod of(String method) {
        var m = LOOKUP.get(method.toUpperCase(Locale.ROOT));
        if (m == null) {
            throw new IllegalArgumentException("Unknown HTTP method: " + method);
        }
        return m;
    }
}
