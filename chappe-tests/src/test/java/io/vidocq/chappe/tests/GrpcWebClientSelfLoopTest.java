package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.client.GrpcWebClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Self-loop : un serveur Chappe avec endpoint {@code grpcWeb} ↔ le client
 * {@link GrpcWebClient} (basé sur {@link java.net.http.HttpClient} du JDK).
 * <p>
 * Valide que le wire gRPC-Web fonctionne dans les deux sens avec deux impls
 * indépendantes côté Chappe (transport bas-niveau vs API client haut-niveau).
 */
class GrpcWebClientSelfLoopTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void binaryUnaryEcho() throws Exception {
        var router = Router.builder()
                .grpcWeb("/echo.EchoService/Echo", call -> {
                    byte[] req = call.receive();
                    call.send(req);
                    call.complete(GrpcStatus.OK, "");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();

        var client = GrpcWebClient.builder()
                .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                .httpClient(http1Client())
                .build();
        var resp = client.unary("/echo.EchoService/Echo", "hello".getBytes(StandardCharsets.UTF_8));

        assertTrue(resp.isOk(), "expected OK, got status=" + resp.status() + " msg=" + resp.message());
        assertNotNull(resp.firstMessage());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), resp.firstMessage());
    }

    @Test
    void textUnaryEcho() throws Exception {
        var router = Router.builder()
                .grpcWeb("/svc/Echo", call -> {
                    byte[] req = call.receive();
                    call.send(req);
                    call.complete(GrpcStatus.OK, "");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();

        var client = GrpcWebClient.builder()
                .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                .mode(GrpcWebClient.Mode.TEXT)
                .httpClient(http1Client())
                .build();
        var resp = client.unary("/svc/Echo", "bonjour".getBytes(StandardCharsets.UTF_8));

        assertTrue(resp.isOk());
        assertArrayEquals("bonjour".getBytes(StandardCharsets.UTF_8), resp.firstMessage());
    }

    @Test
    void serverStreamingCollectsAllMessages() throws Exception {
        var router = Router.builder()
                .grpcWeb("/svc/Stream", call -> {
                    call.receive();
                    for (int i = 0; i < 4; i++) {
                        call.send(("chunk-" + i).getBytes(StandardCharsets.UTF_8));
                    }
                    call.complete(GrpcStatus.OK, "");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();

        var client = GrpcWebClient.builder()
                .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                .httpClient(http1Client())
                .build();
        var resp = client.serverStream("/svc/Stream", new byte[] {0});

        assertTrue(resp.isOk());
        assertEquals(4, resp.messages().size());
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(
                    ("chunk-" + i).getBytes(StandardCharsets.UTF_8),
                    resp.messages().get(i));
        }
    }

    @Test
    void handlerErrorPropagatesStatusAndMessage() throws Exception {
        var router = Router.builder()
                .grpcWeb("/svc/Boom", call -> {
                    call.receive();
                    call.complete(GrpcStatus.PERMISSION_DENIED, "nope");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();

        var client = GrpcWebClient.builder()
                .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                .httpClient(http1Client())
                .build();
        var resp = client.unary("/svc/Boom", new byte[] {1});

        assertEquals(GrpcWebClient.Status.PERMISSION_DENIED, resp.status());
        assertEquals("nope", resp.message());
        assertEquals(0, resp.messages().size(), "aucun message en cas d'erreur immédiate");
    }

    /**
     * Chappe supporte HTTP/2 cleartext uniquement via prior-knowledge (preface PRI direct),
     * pas via l'Upgrade {@code h2c} HTTP/1.1. Or {@code HttpClient} JDK sur cleartext
     * {@code http://} tente l'upgrade plutôt que prior-knowledge — la connexion finit
     * alors en HTTP/1.1. gRPC-Web tourne très bien sur HTTP/1.1 (les trailers sont déjà
     * inline dans le body), donc on force HTTP/1.1 explicitement pour les tests
     * self-loop cleartext.
     */
    private static HttpClient http1Client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
