/**
 * Chappe CLI — standalone {@code chappe serve} launcher for serving
 * static sites without an application-specific Java launcher. Configuration via in-house mini-YAML
 * and CLI flags. Zero dependencies outside the JDK.
 */
module io.vidocq.chappe.cli {
    requires io.vidocq.chappe.api;
    requires io.vidocq.chappe.core;
    requires java.net.http;
}
