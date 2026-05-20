package io.vidocq.chappe.examples;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

/**
 * Démonstration de {@link StaticFileHandler} servi depuis le classpath avec l'index
 * généré au build par {@code chappe-static-index-maven-plugin}.
 *
 * <p>Le plugin scanne {@code src/main/resources/static/**} et écrit
 * {@code META-INF/chappe-static-index.properties}. Au premier lookup, {@code StaticFileHandler}
 * charge cet index et résout chaque requête en O(1) — sans {@code URLConnection.openConnection()}.
 *
 * <p>Si le plugin n'est pas activé, le handler retombe sur le chemin classique
 * {@code loader.getResource()} — zéro régression fonctionnelle.
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
