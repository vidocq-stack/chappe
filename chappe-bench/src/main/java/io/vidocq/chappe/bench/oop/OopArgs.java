package io.vidocq.chappe.bench.oop;

/** Minimal argument parser shared by all mini mains of the OOP shootout. */
final class OopArgs {
    private OopArgs() {}

    /** {@code --port=N} ou variable d'env {@code PORT}, sinon defaut. */
    static int port(String[] args, int defaultPort) {
        for (String a : args) {
            if (a.startsWith("--port=")) return Integer.parseInt(a.substring(7));
        }
        String env = System.getenv("PORT");
        return env != null ? Integer.parseInt(env) : defaultPort;
    }
}
