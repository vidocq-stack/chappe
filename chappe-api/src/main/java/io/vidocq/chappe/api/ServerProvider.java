package io.vidocq.chappe.api;

/**
 * SPI for providing a {@link Server.Builder} implementation.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} by {@link Server#builder()}.
 * The implementation is provided by {@code chappe-core}.
 */
public interface ServerProvider {

    /** Creates a new server builder. */
    Server.Builder newBuilder();
}
