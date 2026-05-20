package io.vidocq.chappe.api;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Versions du protocole HTTP supportées.
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

    /** Format tel qu'il apparaît sur le réseau (ex. {@code "HTTP/1.1"}). */
    public String wireFormat() {
        return wire;
    }

    /**
     * Résout une version HTTP à partir de sa représentation wire.
     *
     * @param wire le format réseau (ex. {@code "HTTP/1.1"})
     * @return la constante correspondante
     * @throws IllegalArgumentException si la version est inconnue
     */
    public static HttpVersion of(String wire) {
        var v = LOOKUP.get(wire);
        if (v == null) {
            throw new IllegalArgumentException("Unknown HTTP version: " + wire);
        }
        return v;
    }
}
