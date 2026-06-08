/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * Throughput benchmark for HTTP/1.1 keep-alive GET requests.
 * Measures requests per second for a minimal "ok" response.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class Http11ThroughputBench {

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
    public HttpResponse<String> smallGetKeepAlive() throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(Http11ThroughputBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
