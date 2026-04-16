/**
 * Moteur serveur HTTP Chappe — virtual threads, lifecycle, configuration.
 */
module fr.vidocq.chappe.core {
    requires fr.vidocq.chappe.api;
    requires fr.vidocq.chappe.http;

    exports fr.vidocq.chappe.core;

    provides fr.vidocq.chappe.api.ServerProvider
            with fr.vidocq.chappe.core.ChappeServerProvider;
}
