package io.vidocq.chappe.http.ws;

import java.io.IOException;

/**
 * WebSocket protocol violation — the connection will be closed with the appropriate code.
 * The {@link #closeCode()} will be sent in the Close frame before TCP shutdown.
 */
public final class WebSocketProtocolException extends IOException {

    private final int closeCode;

    public WebSocketProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }

    public int closeCode() {
        return closeCode;
    }
}
