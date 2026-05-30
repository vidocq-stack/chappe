/**
 * Implementation of the HTTP/1.1 (RFC 9112) and HTTP/2 (RFC 9113) protocols.
 */
module io.vidocq.chappe.http {
    requires io.vidocq.chappe.api;

    exports io.vidocq.chappe.http;
    exports io.vidocq.chappe.http.grpc;
    exports io.vidocq.chappe.http.h2;
    exports io.vidocq.chappe.http.ws;
}
