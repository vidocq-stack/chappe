package io.vidocq.chappe.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Chappe build metadata: Maven version, Git commit, build timestamp,
 * Java version. Loaded once when the class is initialized from
 * {@code chappe-build.properties} (generated at build time by Maven filtering).
 * <p>
 * Exposed on every response via the HTTP {@code Server} header and useful for
 * precisely tracking the binary running in staging/prod.
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
            // Missing or unreadable resource: keep default values.
        }
        VERSION = p.getProperty("chappe.version", "unknown");
        GIT_COMMIT = p.getProperty("chappe.git.commit", "unknown");
        BUILD_TIMESTAMP = p.getProperty("chappe.build.timestamp", "unknown");
        JAVA_VERSION = p.getProperty("chappe.java.version", System.getProperty("java.version", "unknown"));

        // Compact format similar to "Server: nginx/1.25.3"
        SERVER_HEADER = "Chappe/" + VERSION + "+" + GIT_COMMIT + " (" + BUILD_TIMESTAMP + ")";
    }

    private BuildInfo() {}

    /** Maven version (e.g. {@code 0.1.0-SNAPSHOT}). */
    public static String version() {
        return VERSION;
    }

    /** Short hash of the HEAD commit at build time (e.g. {@code ef864e8}), or {@code "unknown"}. */
    public static String gitCommit() {
        return GIT_COMMIT;
    }

    /** ISO-8601 UTC build timestamp (e.g. {@code 2026-05-09T18:14:52Z}). */
    public static String buildTimestamp() {
        return BUILD_TIMESTAMP;
    }

    /** Java version used for compilation. */
    public static String javaVersion() {
        return JAVA_VERSION;
    }

    /** Value ready to paste into an HTTP {@code Server} header. */
    public static String serverHeader() {
        return SERVER_HEADER;
    }
}
