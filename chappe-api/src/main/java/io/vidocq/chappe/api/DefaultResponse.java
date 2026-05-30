package io.vidocq.chappe.api;

/**
 * Immutable implementation of {@link Response} — used by the builder API.
 */
record DefaultResponse(StatusCode status, Headers headers, Body body, Headers trailers) implements Response {
    DefaultResponse(StatusCode status, Headers headers, Body body) {
        this(status, headers, body, Headers.empty());
    }
}
