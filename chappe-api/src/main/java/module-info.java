/**
 * API publique du serveur HTTP Chappe.
 * <p>
 * Définit les interfaces principales : {@code Server}, {@code Handler},
 * {@code Request}, {@code Response}, {@code Router}.
 */
module io.vidocq.chappe.api {
    requires java.net.http;

    exports io.vidocq.chappe.api;
    exports io.vidocq.chappe.api.client;

    uses io.vidocq.chappe.api.ServerProvider;
}
