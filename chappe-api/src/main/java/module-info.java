/**
 * Public API of the Chappe HTTP server.
 * <p>
 * Defines the main interfaces: {@code Server}, {@code Handler},
 * {@code Request}, {@code Response}, {@code Router}.
 */
module io.vidocq.chappe.api {
    requires java.net.http;

    exports io.vidocq.chappe.api;
    exports io.vidocq.chappe.api.client;

    uses io.vidocq.chappe.api.ServerProvider;
}
