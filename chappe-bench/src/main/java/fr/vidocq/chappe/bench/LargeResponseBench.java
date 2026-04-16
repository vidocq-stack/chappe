package fr.vidocq.chappe.bench;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

/**
 * Throughput benchmark for large (1 MB) response bodies.
 * Tests both HTTP/1.1 and HTTP/2 to compare protocol overhead at high payload sizes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class LargeResponseBench {

    private static final int PAYLOAD_SIZE = 1024 * 1024; // 1 MB

    private Server server;
    private HttpClient http11Client;
    private HttpClient http2Client;
    private HttpRequest http11Request;
    private HttpRequest http2Request;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String largeBody = "x".repeat(PAYLOAD_SIZE);

        server = Server.builder()
                .port(0)
                .handler(req -> Response.ok(largeBody))
                .build();
        server.start();

        http11Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        http2Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        String url = "http://localhost:" + server.port() + "/";

        http11Request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Connection", "keep-alive")
                .build();

        http2Request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (http11Client != null) {
            http11Client.close();
        }
        if (http2Client != null) {
            http2Client.close();
        }
    }

    @Benchmark
    public HttpResponse<byte[]> largeResponseHttp11() throws Exception {
        return http11Client.send(http11Request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Benchmark
    public HttpResponse<byte[]> largeResponseHttp2() throws Exception {
        return http2Client.send(http2Request, HttpResponse.BodyHandlers.ofByteArray());
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(LargeResponseBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
