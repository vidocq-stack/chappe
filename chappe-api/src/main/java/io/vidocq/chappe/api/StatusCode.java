package io.vidocq.chappe.api;

/**
 * Code de statut HTTP (RFC 9110, Section 15).
 * <p>
 * Les codes standard sont pré-cachés en tant que constantes.
 * Les codes personnalisés sont supportés via {@link #of(int, String)}.
 */
public sealed interface StatusCode permits StatusCode.Standard, StatusCode.Custom {

    int code();

    String reason();

    // --- 2xx Success ---
    StatusCode OK = new Standard(200, "OK");
    StatusCode CREATED = new Standard(201, "Created");
    StatusCode ACCEPTED = new Standard(202, "Accepted");
    StatusCode NO_CONTENT = new Standard(204, "No Content");

    // --- 3xx Redirection ---
    StatusCode MOVED_PERMANENTLY = new Standard(301, "Moved Permanently");
    StatusCode FOUND = new Standard(302, "Found");
    StatusCode NOT_MODIFIED = new Standard(304, "Not Modified");
    StatusCode TEMPORARY_REDIRECT = new Standard(307, "Temporary Redirect");
    StatusCode PERMANENT_REDIRECT = new Standard(308, "Permanent Redirect");

    // --- 4xx Client Error ---
    StatusCode BAD_REQUEST = new Standard(400, "Bad Request");
    StatusCode UNAUTHORIZED = new Standard(401, "Unauthorized");
    StatusCode FORBIDDEN = new Standard(403, "Forbidden");
    StatusCode NOT_FOUND = new Standard(404, "Not Found");
    StatusCode METHOD_NOT_ALLOWED = new Standard(405, "Method Not Allowed");
    StatusCode NOT_ACCEPTABLE = new Standard(406, "Not Acceptable");
    StatusCode REQUEST_TIMEOUT = new Standard(408, "Request Timeout");
    StatusCode CONFLICT = new Standard(409, "Conflict");
    StatusCode GONE = new Standard(410, "Gone");
    StatusCode LENGTH_REQUIRED = new Standard(411, "Length Required");
    StatusCode PAYLOAD_TOO_LARGE = new Standard(413, "Content Too Large");
    StatusCode URI_TOO_LONG = new Standard(414, "URI Too Long");
    StatusCode UNSUPPORTED_MEDIA_TYPE = new Standard(415, "Unsupported Media Type");
    StatusCode TOO_MANY_REQUESTS = new Standard(429, "Too Many Requests");

    // --- 5xx Server Error ---
    StatusCode INTERNAL_SERVER_ERROR = new Standard(500, "Internal Server Error");
    StatusCode NOT_IMPLEMENTED = new Standard(501, "Not Implemented");
    StatusCode BAD_GATEWAY = new Standard(502, "Bad Gateway");
    StatusCode SERVICE_UNAVAILABLE = new Standard(503, "Service Unavailable");
    StatusCode GATEWAY_TIMEOUT = new Standard(504, "Gateway Timeout");
    StatusCode HTTP_VERSION_NOT_SUPPORTED = new Standard(505, "HTTP Version Not Supported");

    /**
     * Retourne un {@code StatusCode} pour le code et la raison donnés.
     * Les codes standard sont retournés depuis le cache.
     */
    static StatusCode of(int code, String reason) {
        var cached = StatusCodeCache.get(code);
        return cached != null ? cached : new Custom(code, reason);
    }

    /** Retourne un {@code StatusCode} pour un code connu, avec la raison par défaut. */
    static StatusCode of(int code) {
        var cached = StatusCodeCache.get(code);
        return cached != null ? cached : new Custom(code, "");
    }

    record Standard(int code, String reason) implements StatusCode {}

    record Custom(int code, String reason) implements StatusCode {}
}
