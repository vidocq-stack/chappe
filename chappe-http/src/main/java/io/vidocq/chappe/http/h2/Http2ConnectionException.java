package io.vidocq.chappe.http.h2;

import java.io.IOException;

/**
 * Exception de connexion HTTP/2 — porte un {@link Http2ErrorCode}
 * qui sera envoyé dans un GOAWAY frame.
 */
public final class Http2ConnectionException extends IOException {

    private final Http2ErrorCode errorCode;

    public Http2ConnectionException(Http2ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public Http2ErrorCode errorCode() {
        return errorCode;
    }
}
