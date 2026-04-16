package fr.vidocq.chappe.api;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ServiceLoader;

/**
 * Serveur HTTP — point d'entrée pour démarrer et arrêter le serveur.
 * <p>
 * Implémente {@link AutoCloseable} pour supporter {@code try-with-resources}.
 *
 * <pre>{@code
 * try (var server = Server.builder()
 *         .port(8080)
 *         .handler(myRouter)
 *         .build()) {
 *     server.start();
 *     // Le serveur traite les requêtes...
 * } // stop() appelé automatiquement
 * }</pre>
 */
public interface Server extends AutoCloseable {

    /** Démarre le serveur (bind + accept). Non-bloquant. */
    void start();

    /** Arrête le serveur proprement (drain des connexions actives). */
    void stop();

    /** Équivalent à {@link #stop()}. */
    @Override
    default void close() {
        stop();
    }

    /** {@code true} si le serveur est en cours d'exécution. */
    boolean isRunning();

    /** Port réel sur lequel le serveur écoute (utile avec port 0). */
    int port();

    /** Adresse locale complète du serveur. */
    InetSocketAddress localAddress();

    /** Configuration du serveur. */
    ServerConfig config();

    /** Crée un nouveau builder via {@link ServiceLoader} ({@link ServerProvider}). */
    static Builder builder() {
        return ServiceLoader.load(ServerProvider.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ServerProvider found — add chappe-core to the module path"))
                .newBuilder();
    }

    /** Builder fluide pour configurer et construire un {@link Server}. */
    interface Builder {

        Builder port(int port);

        Builder host(String host);

        Builder handler(Handler handler);

        Builder backlog(int backlog);

        Builder readTimeout(Duration timeout);

        Builder writeTimeout(Duration timeout);

        Builder idleTimeout(Duration timeout);

        Builder maxRequestSize(long bytes);

        Builder maxHeaderSize(int bytes);

        /** Configure TLS avec le {@link SSLContext} donné. */
        Builder tls(SSLContext sslContext);

        /** Protocoles ALPN à négocier (défaut : {@code ["h2", "http/1.1"]}). */
        Builder alpnProtocols(String... protocols);

        /** Délai de drain avant fermeture forcée lors du stop (défaut : 30s). */
        Builder shutdownGracePeriod(Duration duration);

        Server build();
    }
}
