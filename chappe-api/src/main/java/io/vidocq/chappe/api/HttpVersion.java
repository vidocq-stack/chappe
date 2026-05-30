package io.vidocq.chappe.api;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Supported HTTP protocol versions.
 */
public enum HttpVersion {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2");

    private static final Map<String, HttpVersion> LOOKUP =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(HttpVersion::wireFormat, v -> v));

    private final String wire;

    HttpVersion(String wire) {
        this.wire = wire;
    }

    /** Format as it appears on the wire (for example {@code "HTTP/1.1"}). */
    public String wireFormat() {
        return wire;
    }

    /**
     * Resolves an HTTP version from its wire representation.
     *
     * @param wire the wire format (for example {@code "HTTP/1.1"})
     * @return the matching constant
     * @throws IllegalArgumentException if the version is unknown
     */
    public static HttpVersion of(String wire) {
        var v = LOOKUP.get(wire);
        if (v == null) {
            throw new IllegalArgumentException("Unknown HTTP version: " + wire);
        }
        return v;
    }
}
