package io.vidocq.chappe.examples;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

/**
 * Demonstration of {@link StaticFileHandler} served from the classpath with the index
 * generated at build time by {@code chappe-static-index-maven-plugin}.
 *
 * <p>The plugin scans {@code src/main/resources/static/**} and writes
 * {@code META-INF/chappe-static-index.properties}. On the first lookup, {@code StaticFileHandler}
 * loads that index and resolves each request in O(1) — without {@code URLConnection.openConnection()}.
 *
 * <p>If the plugin is not enabled, the handler falls back to the classic
 * {@code loader.getResource()} path — zero functional regression.
 */
public final class StaticIndexedExample {

    public static void main(String[] args) throws Exception {
        var router = Router.builder()
                .mount(
                        "/assets",
                        StaticFileHandler.builder()
                                .addClasspath("static")
                                .cacheInMemory(true)
                                .cacheControl("public, max-age=3600")
                                .build())
                .build();

        var server = Server.builder().port(8080).handler(router).build();

        server.start();
        System.out.println("http://localhost:8080/assets/index.html");
        Thread.currentThread().join();
    }

    private StaticIndexedExample() {}
}
