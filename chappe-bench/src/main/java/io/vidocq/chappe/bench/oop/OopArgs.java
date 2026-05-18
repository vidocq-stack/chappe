package io.vidocq.chappe.bench.oop;

/** Parseur d'args minimaliste partagé par toutes les mini-mains du shootout OOP. */
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
