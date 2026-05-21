/**
 * Implémentation des protocoles HTTP/1.1 (RFC 9112) et HTTP/2 (RFC 9113).
 */
module io.vidocq.chappe.http {
    requires io.vidocq.chappe.api;

    exports io.vidocq.chappe.http;
    exports io.vidocq.chappe.http.h2;
    exports io.vidocq.chappe.http.ws;
}
