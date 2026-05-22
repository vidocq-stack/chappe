package io.vidocq.chappe.api;

/**
 * Implémentation immutable de {@link Response} — utilisée par le builder API.
 */
record DefaultResponse(StatusCode status, Headers headers, Body body, Headers trailers) implements Response {
    DefaultResponse(StatusCode status, Headers headers, Body body) {
        this(status, headers, body, Headers.empty());
    }
}
