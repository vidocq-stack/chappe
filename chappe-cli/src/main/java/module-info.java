/**
 * Chappe CLI — launcher standalone {@code chappe serve} pour servir des sites
 * statiques sans launcher Java applicatif. Configuration via mini-YAML in-house
 * et flags CLI. Zéro dépendance hors JDK.
 */
module io.vidocq.chappe.cli {
    requires io.vidocq.chappe.api;
    requires io.vidocq.chappe.core;
    requires java.net.http;
}
