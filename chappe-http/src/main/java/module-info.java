/**
 * Implémentation des protocoles HTTP/1.1 (RFC 9112) et HTTP/2 (RFC 9113).
 */
module fr.vidocq.chappe.http {
    requires fr.vidocq.chappe.api;

    exports fr.vidocq.chappe.http;
    exports fr.vidocq.chappe.http.h2;
}
