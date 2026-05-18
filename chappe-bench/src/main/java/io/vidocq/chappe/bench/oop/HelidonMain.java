package io.vidocq.chappe.bench.oop;

import io.helidon.webserver.WebServer;

public final class HelidonMain {
    private HelidonMain() {}

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        WebServer server = WebServer.builder()
                .host("0.0.0.0")
                .port(port)
                .routing(r -> r.get("/", (req, res) -> res.send("ok")))
                .build()
                .start();
        System.out.println("helidon listening on :" + server.port());
        Thread.currentThread().join();
    }
}
