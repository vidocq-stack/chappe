package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke de conformité gRPC cross-implémentation : on monte un serveur Chappe
 * avec un endpoint echo bytes-bruts, puis on invoque {@code grpcurl} en
 * sous-processus comme un vrai client externe. Si la sortie JSON renvoyée
 * correspond au payload envoyé, on valide le wire HTTP/2 + framing 5 octets
 * + trailers d'un point de vue 100% indépendant.
 *
 * <p>Le proto {@code echo.proto} déclare un seul message {@code EchoMessage}
 * utilisé en request et en response (tag 1, type string). Côté serveur on
 * fait un echo des bytes bruts sans décoder protobuf — grpcurl re-décode la
 * réponse comme {@code EchoMessage}, donc tout payload-aller doit ressortir
 * à l'identique côté payload-retour.
 *
 * <p>Test skippé si {@code grpcurl} n'est pas dans le PATH (binaire externe
 * optionnel — installable via {@code brew install grpcurl}).
 */
class GrpcurlConformanceTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void grpcurlUnaryEcho() throws Exception {
        assumeTrue(grpcurlAvailable(), "grpcurl absent du PATH (skip — brew install grpcurl)");

        var router = Router.builder()
                .grpc("/echo.EchoService/Echo", call -> {
                    byte[] req = call.receive();
                    call.send(req);
                    call.complete(GrpcStatus.OK, "");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        int port = server.port();

        Path protoDir = extractProtoToTempDir();

        var pb = new ProcessBuilder(
                "grpcurl",
                "-plaintext",
                "-d",
                "{\"message\":\"hello-from-grpcurl\"}",
                "-import-path",
                protoDir.toString(),
                "-proto",
                "echo.proto",
                "127.0.0.1:" + port,
                "echo.EchoService/Echo");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean exited = p.waitFor(15, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "grpcurl n'a pas terminé en 15s, output=" + output);
        assertEquals(0, p.exitValue(), "grpcurl exit code non-zéro, output=\n" + output);
        // grpcurl format de sortie par défaut : JSON pretty-printed sur stdout.
        // On vérifie juste que le payload est intact (le serveur a echo).
        assertTrue(output.contains("\"message\""), "réponse sans champ message, output=\n" + output);
        assertTrue(output.contains("hello-from-grpcurl"), "réponse sans payload echo intact, output=\n" + output);
    }

    @Test
    void grpcurlStatusOnFailingHandler() throws Exception {
        assumeTrue(grpcurlAvailable(), "grpcurl absent du PATH (skip)");

        var router = Router.builder()
                .grpc("/echo.EchoService/Echo", call -> {
                    call.receive();
                    // handler qui throw sans complete -> couche transport doit émettre
                    // grpc-status: 13 (INTERNAL) dans les trailers
                    throw new RuntimeException("boom");
                })
                .build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        int port = server.port();

        Path protoDir = extractProtoToTempDir();

        var pb = new ProcessBuilder(
                "grpcurl",
                "-plaintext",
                "-d",
                "{\"message\":\"x\"}",
                "-import-path",
                protoDir.toString(),
                "-proto",
                "echo.proto",
                "127.0.0.1:" + port,
                "echo.EchoService/Echo");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean exited = p.waitFor(15, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "grpcurl n'a pas terminé en 15s, output=" + output);
        // grpcurl exit non-zéro quand grpc-status != 0 ; il affiche le statut
        // canonique ("Internal" pour code 13).
        assertTrue(p.exitValue() != 0, "grpcurl devrait sortir en erreur sur grpc-status=13, exit=" + p.exitValue());
        assertTrue(
                output.contains("Internal") || output.contains("INTERNAL") || output.contains("Code: Internal"),
                "trailers grpc-status devraient indiquer INTERNAL, output=\n" + output);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean grpcurlAvailable() {
        try {
            Process p = new ProcessBuilder("grpcurl", "-version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Path extractProtoToTempDir() throws IOException {
        Path dir = Files.createTempDirectory("chappe-grpc-conf-");
        dir.toFile().deleteOnExit();
        Path proto = dir.resolve("echo.proto");
        try (InputStream in = GrpcurlConformanceTest.class.getResourceAsStream("/grpc/echo.proto")) {
            if (in == null) throw new IOException("resource /grpc/echo.proto introuvable dans le classpath");
            Files.write(proto, in.readAllBytes());
        }
        proto.toFile().deleteOnExit();
        return dir;
    }
}
