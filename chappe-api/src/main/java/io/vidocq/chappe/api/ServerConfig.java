package io.vidocq.chappe.api;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.List;

/**
 * Configuration immutable du serveur HTTP.
 *
 * @param host adresse d'écoute (défaut : {@code "0.0.0.0"})
 * @param port port d'écoute (défaut : {@code 8080}, {@code 0} pour port éphémère)
 * @param backlog taille de la file d'attente TCP (défaut : {@code 1024})
 * @param readTimeout délai max de lecture (défaut : 30s)
 * @param writeTimeout délai max d'écriture (défaut : 30s)
 * @param idleTimeout délai d'inactivité avant fermeture de connexion (défaut : 60s)
 * @param maxRequestSize taille max du corps de requête en octets (défaut : 10 Mo)
 * @param maxHeaderSize taille max des en-têtes en octets (défaut : 8 Ko)
 * @param sslContext contexte SSL/TLS ({@code null} = cleartext)
 * @param alpnProtocols protocoles ALPN négociés (défaut : {@code ["h2", "http/1.1"]})
 * @param shutdownGracePeriod délai de drain avant fermeture forcée (défaut : 30s)
 */
public record ServerConfig(
        String host,
        int port,
        int backlog,
        Duration readTimeout,
        Duration writeTimeout,
        Duration idleTimeout,
        long maxRequestSize,
        int maxHeaderSize,
        SSLContext sslContext,
        List<String> alpnProtocols,
        Duration shutdownGracePeriod
) {

    /** Configuration par défaut (cleartext). */
    public static final ServerConfig DEFAULT = new ServerConfig(
            "0.0.0.0",
            8080,
            1024,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            10L * 1024 * 1024,
            8192,
            null,
            List.of("h2", "http/1.1"),
            Duration.ofSeconds(30)
    );

    /** {@code true} si TLS est activé. */
    public boolean tlsEnabled() {
        return sslContext != null;
    }
}
