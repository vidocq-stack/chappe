package io.vidocq.chappe.http.h2;

import java.io.IOException;

/**
 * HTTP/2 connection exception — carries an {@link Http2ErrorCode}
 * that will be sent in a GOAWAY frame.
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
