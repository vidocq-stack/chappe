package io.vidocq.chappe.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end integration tests for the {@code chappe serve} subcommand. */
class ServeCommandTest {

    private Server server;
    private final HttpClient client =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void servesIndexFromYamlConfig(@TempDir Path tmp) throws Exception {
        Path docroot = Files.createDirectory(tmp.resolve("site"));
        Files.writeString(docroot.resolve("index.html"), "<h1>chappe</h1>");
        Files.writeString(docroot.resolve("style.css"), "body{margin:0}");

        Path yml = tmp.resolve("config.yml");
        Files.writeString(yml, """
                server:
                  port: 0
                static:
                  root: %s
                  cache-control: "max-age=60"
                headers:
                  always:
                    X-Content-Type-Options: nosniff
                """.formatted(docroot));

        CliArgs args = CliArgs.parse(new String[] {"serve", "--config", yml.toString()});
        ChappeConfig yaml = ConfigLoader.load(yml);
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, yaml);
        server = ServeCommand.start(eff);

        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("chappe"));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("max-age=60", r.headers().firstValue("Cache-Control").orElse(null));

        HttpResponse<String> css = get("/style.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("margin:0"));
    }

    @Test
    void cliFlagsOverrideYaml(@TempDir Path tmp) throws Exception {
        Path doc1 = Files.createDirectory(tmp.resolve("a"));
        Files.writeString(doc1.resolve("index.html"), "from-a");
        Path doc2 = Files.createDirectory(tmp.resolve("b"));
        Files.writeString(doc2.resolve("index.html"), "from-b");

        Path yml = tmp.resolve("c.yml");
        Files.writeString(yml, "static:\n  root: " + doc1 + "\n");

        CliArgs args = CliArgs.parse(
                new String[] {"serve", "--config", yml.toString(), "--root", doc2.toString(), "--port", "0"});
        ChappeConfig yaml = ConfigLoader.load(yml);
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, yaml);
        server = ServeCommand.start(eff);

        assertEquals("from-b", get("/").body());
    }

    @Test
    void notFoundFileFromConfig(@TempDir Path tmp) throws Exception {
        Path docroot = Files.createDirectory(tmp.resolve("site"));
        Files.writeString(docroot.resolve("index.html"), "home");
        Files.writeString(docroot.resolve("404.html"), "<h1>oops</h1>");

        Path yml = tmp.resolve("config.yml");
        Files.writeString(yml, """
                server:
                  port: 0
                static:
                  root: %s
                  fallback: /404.html
                """.formatted(docroot));

        CliArgs args = CliArgs.parse(new String[] {"serve", "--config", yml.toString()});
        ChappeConfig yaml = ConfigLoader.load(yml);
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, yaml);
        server = ServeCommand.start(eff);

        HttpResponse<String> r = get("/no-such-thing");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("oops"));
    }

    @Test
    void stagingHeaderAbsentWhenEnvNotSet(@TempDir Path tmp) throws Exception {
        Path docroot = Files.createDirectory(tmp.resolve("site"));
        Files.writeString(docroot.resolve("index.html"), "home");

        ChappeConfig yaml = ChappeConfig.from(io.vidocq.chappe.cli.yaml.YamlReader.parse("""
                static:
                  root: %s
                headers:
                  staging:
                    X-Robots-Tag: "noindex, nofollow"
                """.formatted(docroot)));
        CliArgs args = CliArgs.parse(new String[] {"serve", "--port", "0"});
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, yaml);
        server = ServeCommand.start(eff);

        HttpResponse<String> r = get("/");
        // STAGING env var is not set in the test JVM
        assertNull(r.headers().firstValue("X-Robots-Tag").orElse(null));
    }

    @Test
    void cliHeaderFlagInjectsHeader(@TempDir Path tmp) throws Exception {
        Path docroot = Files.createDirectory(tmp.resolve("site"));
        Files.writeString(docroot.resolve("index.html"), "home");

        CliArgs args = CliArgs.parse(
                new String[] {"serve", "--root", docroot.toString(), "--port", "0", "--header", "X-Foo=bar"});
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, ChappeConfig.EMPTY);
        server = ServeCommand.start(eff);

        HttpResponse<String> r = get("/");
        assertEquals("bar", r.headers().firstValue("X-Foo").orElse(null));
    }

    @Test
    void rootMissingRaisesAtStart(@TempDir Path tmp) {
        CliArgs args = CliArgs.parse(new String[] {"serve", "--port", "0"});
        ServeCommand.Effective eff = ServeCommand.Effective.resolve(args, ChappeConfig.EMPTY);
        try {
            server = ServeCommand.start(eff);
            throw new AssertionError("expected start() to fail without root");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("root"));
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.port() + path);
        HttpRequest req = HttpRequest.newBuilder().uri(uri).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unused")
    private static Map<String, String> envOverride() {
        return Map.of(); // placeholder for future env override hook
    }
}
