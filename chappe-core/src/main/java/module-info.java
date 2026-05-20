/**
 * Moteur serveur HTTP Chappe — virtual threads, lifecycle, configuration.
 */
module io.vidocq.chappe.core {
    requires io.vidocq.chappe.api;
    requires io.vidocq.chappe.http;

    exports io.vidocq.chappe.core;

    provides io.vidocq.chappe.api.ServerProvider with
            io.vidocq.chappe.core.ChappeServerProvider;
}
