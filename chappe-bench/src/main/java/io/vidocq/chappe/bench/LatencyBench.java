package io.vidocq.chappe.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Latency distribution benchmark using SampleTime mode.
 * Reports p50 / p99 / p999 latency in microseconds for a minimal GET response.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class LatencyBench {

    private Server server;
    private HttpClient client;
    private HttpRequest request;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        server = Server.builder().port(0).handler(req -> Response.ok("ok")).build();
        server.start();

        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/"))
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
    public HttpResponse<String> getLatency() throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(LatencyBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
