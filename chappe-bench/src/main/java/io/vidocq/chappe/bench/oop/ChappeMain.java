package io.vidocq.chappe.bench.oop;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

/** Mini main qui démarre Chappe sur le port donné et répond {@code "ok"} sur toutes les routes. */
public final class ChappeMain {
    private ChappeMain() {}

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = Server.builder()
                .port(port)
                .host("0.0.0.0")
                .handler(_ -> Response.ok("ok"))
                .build();
        server.start();
        System.out.println("chappe-jvm listening on :" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "chappe-shutdown"));
        Thread.currentThread().join();
    }
}
