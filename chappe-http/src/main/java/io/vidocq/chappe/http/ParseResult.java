package io.vidocq.chappe.http;

/**
 * Result of a parsing attempt.
 */
public enum ParseResult {

    /** Request fully parsed, ready for dispatch. */
    COMPLETE,

    /** The remote peer closed the connection. */
    CONNECTION_CLOSED
}
