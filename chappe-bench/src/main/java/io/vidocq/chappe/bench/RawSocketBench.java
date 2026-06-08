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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmark with a raw-socket client — measures the server's pure throughput
 * without HttpClient overhead (async framework, connection pool, TLS negotiation).
 * <p>
 * Reused keep-alive TCP connection, sending/receiving raw bytes.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@SuppressWarnings("AddressSelection") // Bench localhost
public class RawSocketBench {

    private static final byte[] GET_REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private Server server;
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private byte[] readBuf;
    private int port;

    @Setup(Level.Trial)
    public void setupServer() {
        server = Server.builder().port(0).handler(_ -> Response.ok("ok")).build();
        server.start();
        port = server.port();
        readBuf = new byte[4096];
    }

    @Setup(Level.Iteration)
    public void setupSocket() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        out = socket.getOutputStream();
        in = socket.getInputStream();
    }

    @TearDown(Level.Iteration)
    public void tearDownSocket() throws IOException {
        if (socket != null) socket.close();
    }

    @TearDown(Level.Trial)
    public void tearDownServer() {
        if (server != null) server.stop();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public int throughputKeepAlive() throws IOException {
        out.write(GET_REQUEST);
        out.flush();
        return drainResponse();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public int latencyKeepAlive() throws IOException {
        out.write(GET_REQUEST);
        out.flush();
        return drainResponse();
    }

    /**
     * Reads the full HTTP response from the keep-alive socket.
     * Parses Content-Length to know how much to read.
     */
    private int drainResponse() throws IOException {
        int totalRead = 0;
        int headerEnd = -1;
        int contentLength = -1;

        // Read headers
        while (headerEnd < 0) {
            int n = in.read(readBuf, totalRead, readBuf.length - totalRead);
            if (n < 0) throw new IOException("Connection closed");
            totalRead += n;

            // Look for \r\n\r\n
            for (int i = Math.max(0, totalRead - n - 3); i <= totalRead - 4; i++) {
                if (readBuf[i] == '\r' && readBuf[i + 1] == '\n' && readBuf[i + 2] == '\r' && readBuf[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
        }

        // Parse Content-Length
        var headers = new String(readBuf, 0, headerEnd, StandardCharsets.US_ASCII);
        int clIdx = headers.indexOf("Content-Length: ");
        if (clIdx >= 0) {
            int clEnd = headers.indexOf("\r\n", clIdx);
            contentLength = Integer.parseInt(headers.substring(clIdx + 16, clEnd));
        }

        // Read remaining body
        if (contentLength > 0) {
            int bodyRead = totalRead - headerEnd;
            while (bodyRead < contentLength) {
                int n = in.read(readBuf, 0, Math.min(readBuf.length, contentLength - bodyRead));
                if (n < 0) break;
                bodyRead += n;
            }
            return headerEnd + contentLength;
        }

        return totalRead;
    }

    public static void main(String[] args) throws RunnerException {
        var opt = new OptionsBuilder()
                .include(RawSocketBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
