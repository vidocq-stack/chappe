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
 * Throughput benchmark for HTTP/2 multiplexed GET requests.
 * Measures requests per second for a minimal "ok" response over HTTP/2.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class Http2ThroughputBench {

    private Server server;
    private HttpClient client;
    private HttpRequest request;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        server = Server.builder()
                .port(0)
                .handler(req -> Response.ok("ok"))
                .build();
        server.start();

        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + "/"))
                .GET()
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            client.close();
        }
    }

    @Benchmark
    public HttpResponse<String> smallGetHttp2() throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(Http2ThroughputBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
