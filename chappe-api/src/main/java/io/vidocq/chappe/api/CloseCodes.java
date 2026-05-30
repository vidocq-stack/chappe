package io.vidocq.chappe.api;

/**
 * WebSocket close codes — RFC 6455 §7.4.
 * <p>
 * Range 0–999 is reserved, 1000–2999 is specified by the RFC, 3000–3999 is reserved for
 * library and framework registries, and 4000–4999 is available to applications.
 */
public final class CloseCodes {

    /** Normal closure — the purpose for which the connection was established has been fulfilled. */
    public static final int NORMAL_CLOSURE = 1000;

    /** The endpoint is going away (server shutting down, browser tab closing). */
    public static final int GOING_AWAY = 1001;

    /** Protocol error. */
    public static final int PROTOCOL_ERROR = 1002;

    /** Unsupported data type (for example, a text endpoint receiving binary data). */
    public static final int UNSUPPORTED_DATA = 1003;

    /** No code received — reserved, MUST NOT be sent in a Close frame. */
    public static final int NO_STATUS_RCVD = 1005;

    /** Abnormal closure — reserved, MUST NOT be sent in a Close frame. */
    public static final int ABNORMAL_CLOSURE = 1006;

    /** Payload invalid for its type (for example invalid UTF-8 in a TEXT frame). */
    public static final int INVALID_PAYLOAD_DATA = 1007;

    /** Generic policy violation. */
    public static final int POLICY_VIOLATION = 1008;

    /** Message too large to be processed. */
    public static final int MESSAGE_TOO_BIG = 1009;

    /** Expected extension(s) missing on the server side. */
    public static final int MANDATORY_EXTENSION = 1010;

    /** Unexpected condition preventing the server from responding. */
    public static final int INTERNAL_ERROR = 1011;

    /** Service is restarting. */
    public static final int SERVICE_RESTART = 1012;

    /** Service overloaded, try again later. */
    public static final int TRY_AGAIN_LATER = 1013;

    /** Invalid gateway. */
    public static final int BAD_GATEWAY = 1014;

    /** TLS handshake failure — reserved, MUST NOT be sent in a Close frame. */
    public static final int TLS_HANDSHAKE = 1015;

    private CloseCodes() {}

    /**
     * Indicates whether the code is valid as a Close frame status sent on the wire.
     * Codes 1005, 1006, and 1015 are reserved for local use and MUST NOT appear
     * on the network (RFC 6455 §7.4.1).
     */
    public static boolean isValidOnWire(int code) {
        if (code < 1000 || code > 4999) return false;
        if (code == NO_STATUS_RCVD || code == ABNORMAL_CLOSURE || code == TLS_HANDSHAKE) return false;
        // 1016-2999 reserved but not used
        if (code >= 1016 && code <= 2999) return false;
        return true;
    }
}
