package io.vidocq.chappe.bench;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark complet du serveur Chappe — exécutable directement via {@code main()}.
 * <p>
 * Mesure throughput et latence en utilisant des raw sockets (zéro overhead client)
 * et HttpClient (mesure réaliste).
 */
public class ChappeBenchmark {

    private static final byte[] GET_REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int WARMUP_SECONDS = 3;
    private static final int MEASURE_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║          Chappe HTTP Server — Benchmark Suite           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        var server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok("ok"))
                .build();
        server.start();
        int port = server.port();

        try {
            // 1. Raw Socket — Single Thread
            System.out.println("─── Raw Socket — Single Thread (keep-alive) ───");
            benchRawSocket(port, 1);

            // 2. Raw Socket — 4 threads
            System.out.println("─── Raw Socket — 4 Threads Concurrent ───");
            benchRawSocket(port, 4);

            // 3. Raw Socket — 8 threads
            System.out.println("─── Raw Socket — 8 Threads Concurrent ───");
            benchRawSocket(port, 8);

            // 4. Raw Socket — 16 threads
            System.out.println("─── Raw Socket — 16 Threads Concurrent ───");
            benchRawSocket(port, 16);

            // 5. HttpClient HTTP/1.1
            System.out.println("─── HttpClient HTTP/1.1 (keep-alive) ───");
            benchHttpClient(port, HttpClient.Version.HTTP_1_1);

            // 6. HttpClient HTTP/2
            System.out.println("─── HttpClient HTTP/2 (h2c) ───");
            benchHttpClient(port, HttpClient.Version.HTTP_2);

            // 7. Latence raw socket
            System.out.println("─── Latency Distribution (raw socket) ───");
            benchLatency(port);

            // 8. Large response
            System.out.println("─── Large Response (1 MB body) ───");
            benchLargeResponse(port);

        } finally {
            server.stop();
        }
    }

    // ── Raw Socket Throughput ───────────────────────────────────

    static void benchRawSocket(int port, int threads) throws Exception {
        var totalOps = new AtomicLong();
        var errors = new AtomicInteger();
        var running = new boolean[]{true};

        // Warmup
        System.out.printf("  Warmup %ds...", WARMUP_SECONDS);
        var warmupLatch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try { runRawSocket(port, running, totalOps, errors); }
                catch (Exception _) {}
                warmupLatch.countDown();
            });
        }
        Thread.sleep(WARMUP_SECONDS * 1000L);
        running[0] = false;
        warmupLatch.await();
        System.out.println(" done");

        // Reset
        totalOps.set(0);
        errors.set(0);
        running[0] = true;

        // Measure
        System.out.printf("  Measure %ds...", MEASURE_SECONDS);
        var latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try { runRawSocket(port, running, totalOps, errors); }
                catch (Exception _) {}
                latch.countDown();
            });
        }
        Thread.sleep(MEASURE_SECONDS * 1000L);
        running[0] = false;
        latch.await();

        long ops = totalOps.get();
        double rps = (double) ops / MEASURE_SECONDS;
        System.out.printf(" done%n");
        System.out.printf("  → %,.0f req/s  (%,d requests in %ds, %d errors)%n%n",
                rps, ops, MEASURE_SECONDS, errors.get());
    }

    static void runRawSocket(int port, boolean[] running, AtomicLong ops, AtomicInteger errors)
            throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();
            var buf = new byte[4096];

            while (running[0]) {
                try {
                    out.write(GET_REQUEST);
                    out.flush();
                    drainResponse(in, buf);
                    ops.incrementAndGet();
                } catch (IOException e) {
                    errors.incrementAndGet();
                    return;
                }
            }
        }
    }

    // ── HttpClient Throughput ───────────────────────────────────

    static void benchHttpClient(int port, HttpClient.Version version) throws Exception {
        var client = HttpClient.newBuilder().version(version).build();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/"))
                .GET().build();

        // Warmup
        System.out.printf("  Warmup %ds...", WARMUP_SECONDS);
        long warmEnd = System.nanoTime() + WARMUP_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < warmEnd) {
            client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        System.out.println(" done");

        // Measure
        System.out.printf("  Measure %ds...", MEASURE_SECONDS);
        long count = 0;
        long end = System.nanoTime() + MEASURE_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < end) {
            client.send(request, HttpResponse.BodyHandlers.ofString());
            count++;
        }
        double rps = (double) count / MEASURE_SECONDS;
        System.out.printf(" done%n");
        System.out.printf("  → %,.0f req/s  (%,d requests in %ds)%n%n", rps, count, MEASURE_SECONDS);
        client.close();
    }

    // ── Latency ────────────────────────────────────────────────

    static void benchLatency(int port) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();
            var buf = new byte[4096];

            // Warmup
            for (int i = 0; i < 5000; i++) {
                out.write(GET_REQUEST);
                out.flush();
                drainResponse(in, buf);
            }

            // Measure
            int samples = 50_000;
            long[] latencies = new long[samples];
            for (int i = 0; i < samples; i++) {
                long start = System.nanoTime();
                out.write(GET_REQUEST);
                out.flush();
                drainResponse(in, buf);
                latencies[i] = System.nanoTime() - start;
            }

            Arrays.sort(latencies);
            System.out.printf("  %,d samples:%n", samples);
            System.out.printf("  → p50  = %,.1f µs%n", latencies[(int) (samples * 0.50)] / 1000.0);
            System.out.printf("  → p90  = %,.1f µs%n", latencies[(int) (samples * 0.90)] / 1000.0);
            System.out.printf("  → p99  = %,.1f µs%n", latencies[(int) (samples * 0.99)] / 1000.0);
            System.out.printf("  → p999 = %,.1f µs%n", latencies[(int) (samples * 0.999)] / 1000.0);
            System.out.printf("  → min  = %,.1f µs%n", latencies[0] / 1000.0);
            System.out.printf("  → max  = %,.1f µs%n%n", latencies[samples - 1] / 1000.0);
        }
    }

    // ── Large Response ─────────────────────────────────────────

    static void benchLargeResponse(int port) throws Exception {
        // Stop and restart with large body handler
        // Can't swap handler, so use a separate server
        var largeBody = "x".repeat(1_000_000);
        var largeServer = Server.builder()
                .port(0)
                .handler(_ -> Response.ok(largeBody))
                .build();
        largeServer.start();
        int largePort = largeServer.port();

        try {
            var client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1).build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + largePort + "/"))
                    .GET().build();

            // Warmup
            for (int i = 0; i < 100; i++) {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            }

            // Measure
            System.out.printf("  Measure %ds...", MEASURE_SECONDS);
            long count = 0;
            long end = System.nanoTime() + MEASURE_SECONDS * 1_000_000_000L;
            while (System.nanoTime() < end) {
                client.send(request, HttpResponse.BodyHandlers.ofString());
                count++;
            }
            double rps = (double) count / MEASURE_SECONDS;
            double gbps = rps * 1_000_000 / (1024 * 1024 * 1024.0);
            System.out.printf(" done%n");
            System.out.printf("  → %,.0f req/s  (%.2f GB/s throughput, 1 MB body)%n%n",
                    rps, gbps);
            client.close();
        } finally {
            largeServer.stop();
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    static void drainResponse(InputStream in, byte[] buf) throws IOException {
        int totalRead = 0;
        int headerEnd = -1;
        int contentLength = -1;

        while (headerEnd < 0) {
            int n = in.read(buf, totalRead, buf.length - totalRead);
            if (n < 0) throw new IOException("Connection closed");
            totalRead += n;
            for (int i = Math.max(0, totalRead - n - 3); i <= totalRead - 4; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n'
                        && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
        }

        var headers = new String(buf, 0, headerEnd, StandardCharsets.US_ASCII);
        int clIdx = headers.indexOf("Content-Length: ");
        if (clIdx >= 0) {
            int clEnd = headers.indexOf("\r\n", clIdx);
            contentLength = Integer.parseInt(headers.substring(clIdx + 16, clEnd));
        }

        if (contentLength > 0) {
            int bodyRead = totalRead - headerEnd;
            while (bodyRead < contentLength) {
                int n = in.read(buf, 0, Math.min(buf.length, contentLength - bodyRead));
                if (n < 0) break;
                bodyRead += n;
            }
        }
    }
}
