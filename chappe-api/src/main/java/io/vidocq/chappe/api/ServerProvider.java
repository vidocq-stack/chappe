package io.vidocq.chappe.api;

/**
 * SPI pour fournir une implémentation de {@link Server.Builder}.
 * <p>
 * Découvert via {@link java.util.ServiceLoader} par {@link Server#builder()}.
 * L'implémentation est fournie par {@code chappe-core}.
 */
public interface ServerProvider {

    /** Crée un nouveau builder de serveur. */
    Server.Builder newBuilder();
}
