/**
 * API publique du serveur HTTP Chappe.
 * <p>
 * Définit les interfaces principales : {@code Server}, {@code Handler},
 * {@code Request}, {@code Response}, {@code Router}.
 */
module fr.vidocq.chappe.api {
    exports fr.vidocq.chappe.api;

    uses fr.vidocq.chappe.api.ServerProvider;
}
