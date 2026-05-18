package io.vidocq.chappe.bench.oop;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public final class VertxMain {
    private VertxMain() {}

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer()
                .requestHandler(req -> req.response()
                        .putHeader("content-type", "text/plain")
                        .end("ok"));
        server.listen(port, "0.0.0.0")
                .toCompletionStage().toCompletableFuture().get();
        System.out.println("vertx listening on :" + server.actualPort());
        Thread.currentThread().join();
    }
}
