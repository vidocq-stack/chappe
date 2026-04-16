package fr.vidocq.chappe.core;

import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.ServerProvider;

/**
 * Fournisseur de {@link Server.Builder} via {@link java.util.ServiceLoader}.
 */
public final class ChappeServerProvider implements ServerProvider {

    /** Constructeur public requis par ServiceLoader. */
    public ChappeServerProvider() {}

    @Override
    public Server.Builder newBuilder() {
        return new ChappeServerBuilder();
    }
}
