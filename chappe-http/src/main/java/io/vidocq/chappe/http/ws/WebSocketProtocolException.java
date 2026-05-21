package io.vidocq.chappe.http.ws;

import java.io.IOException;

/**
 * Violation du protocole WebSocket — la connexion sera fermée avec le code adapté.
 * Le {@link #closeCode()} sera renvoyé dans la frame Close avant la fermeture TCP.
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
