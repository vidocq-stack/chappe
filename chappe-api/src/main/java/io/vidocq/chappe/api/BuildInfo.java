package io.vidocq.chappe.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Métadonnées du build Chappe : version Maven, commit Git, timestamp de build,
 * version Java. Lues une fois au chargement de la classe depuis
 * {@code chappe-build.properties} (généré au build par filtering Maven).
 * <p>
 * Exposé sur chaque réponse via le header HTTP {@code Server} et utile pour
 * tracer précisément le binaire qui tourne en staging/prod.
 *
 * <pre>{@code
 * BuildInfo.serverHeader();
 *   // → "Chappe/0.1.0-SNAPSHOT+ef864e8 (2026-05-09T18:14:52Z)"
 * }</pre>
 */
public final class BuildInfo {

    private static final String VERSION;
    private static final String GIT_COMMIT;
    private static final String BUILD_TIMESTAMP;
    private static final String JAVA_VERSION;
    private static final String SERVER_HEADER;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/chappe-build.properties")) {
            if (in != null) p.load(in);
        } catch (IOException _) {
            // ressource absente ou illisible : on conserve les defaults
        }
        VERSION = p.getProperty("chappe.version", "unknown");
        GIT_COMMIT = p.getProperty("chappe.git.commit", "unknown");
        BUILD_TIMESTAMP = p.getProperty("chappe.build.timestamp", "unknown");
        JAVA_VERSION = p.getProperty("chappe.java.version", System.getProperty("java.version", "unknown"));

        // Format compact à la mode "Server: nginx/1.25.3"
        SERVER_HEADER = "Chappe/" + VERSION + "+" + GIT_COMMIT + " (" + BUILD_TIMESTAMP + ")";
    }

    private BuildInfo() {}

    /** Version Maven (ex: {@code 0.1.0-SNAPSHOT}). */
    public static String version() { return VERSION; }

    /** Short hash du commit HEAD au build (ex: {@code ef864e8}), ou {@code "unknown"}. */
    public static String gitCommit() { return GIT_COMMIT; }

    /** Timestamp ISO-8601 UTC du build (ex: {@code 2026-05-09T18:14:52Z}). */
    public static String buildTimestamp() { return BUILD_TIMESTAMP; }

    /** Version Java utilisée pour compiler. */
    public static String javaVersion() { return JAVA_VERSION; }

    /** Valeur prête à coller dans un header HTTP {@code Server}. */
    public static String serverHeader() { return SERVER_HEADER; }
}
