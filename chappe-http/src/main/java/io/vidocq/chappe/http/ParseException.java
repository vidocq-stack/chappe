package io.vidocq.chappe.http;

import io.vidocq.chappe.api.StatusCode;

/**
 * Exception thrown while parsing a malformed HTTP request.
 * <p>
 * Carries the appropriate {@link StatusCode} for the error response
 * (400, 413, 414, 431…).
 */
public final class ParseException extends Exception {

    private final StatusCode statusCode;

    public ParseException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP status code to return to the client. */
    public StatusCode statusCode() {
        return statusCode;
    }
}
