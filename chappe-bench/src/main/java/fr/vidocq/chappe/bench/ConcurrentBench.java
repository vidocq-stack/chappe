package fr.vidocq.chappe.bench;

import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark concurrent — N threads envoient des requêtes en parallèle
 * via des sockets raw distinctes. Mesure le throughput total du serveur.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@Threads(8)
public class ConcurrentBench {

    private static final byte[] GET_REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private Server server;
    private int port;

    /** Per-thread state : chaque thread a sa propre socket. */
    @State(Scope.Thread)
    public static class ThreadState {
        Socket socket;
        OutputStream out;
        InputStream in;
        byte[] readBuf = new byte[4096];

        @Setup(Level.Iteration)
        public void setup(ConcurrentBench bench) throws IOException {
            if (socket != null && !socket.isClosed()) socket.close();
            socket = new Socket("127.0.0.1", bench.port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        @TearDown(Level.Iteration)
        public void tearDown() throws IOException {
            if (socket != null) socket.close();
        }
    }

    @Setup(Level.Trial)
    public void setupServer() {
        server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok("ok"))
                .build();
        server.start();
        port = server.port();
    }

    @TearDown(Level.Trial)
    public void tearDownServer() {
        if (server != null) server.stop();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public int concurrentThroughput(ThreadState ts) throws IOException {
        ts.out.write(GET_REQUEST);
        ts.out.flush();
        return drainResponse(ts);
    }

    private int drainResponse(ThreadState ts) throws IOException {
        int totalRead = 0;
        int headerEnd = -1;
        int contentLength = -1;

        while (headerEnd < 0) {
            int n = ts.in.read(ts.readBuf, totalRead, ts.readBuf.length - totalRead);
            if (n < 0) throw new IOException("Connection closed");
            totalRead += n;

            for (int i = Math.max(0, totalRead - n - 3); i <= totalRead - 4; i++) {
                if (ts.readBuf[i] == '\r' && ts.readBuf[i + 1] == '\n'
                        && ts.readBuf[i + 2] == '\r' && ts.readBuf[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
        }

        var headers = new String(ts.readBuf, 0, headerEnd, StandardCharsets.US_ASCII);
        int clIdx = headers.indexOf("Content-Length: ");
        if (clIdx >= 0) {
            int clEnd = headers.indexOf("\r\n", clIdx);
            contentLength = Integer.parseInt(headers.substring(clIdx + 16, clEnd));
        }

        if (contentLength > 0) {
            int bodyRead = totalRead - headerEnd;
            while (bodyRead < contentLength) {
                int n = ts.in.read(ts.readBuf, 0, Math.min(ts.readBuf.length, contentLength - bodyRead));
                if (n < 0) break;
                bodyRead += n;
            }
            return headerEnd + contentLength;
        }
        return totalRead;
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(ConcurrentBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .threads(8)
                .build();
        new Runner(opt).run();
    }
}
