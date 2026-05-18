package io.vidocq.chappe.bench.oop;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

/**
 * Variante de {@link ChappeMain} qui sert une {@link Response} pré-construite
 * partagée entre toutes les requêtes (zero-alloc côté response).
 *
 * <p>Permet de mesurer l'impact des allocations
 * {@code Builder → Headers$Entry → DefaultHeaders → DefaultResponse}
 * par requête identifié dans le profil JFR.
 */
public final class ChappeMainCached {
    private ChappeMainCached() {}

    /** Pré-construit une fois — Response est documentée immutable. */
    private static final Response CACHED_OK = Response.ok("ok");

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = Server.builder()
                .port(port)
                .host("0.0.0.0")
                .handler(_ -> CACHED_OK)
                .build();
        server.start();
        System.out.println("chappe-jvm-cached listening on :" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "chappe-shutdown"));
        Thread.currentThread().join();
    }
}
