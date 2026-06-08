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
 * Cross-implementation gRPC conformance smoke test: a Chappe server is started
 * with a raw-bytes echo endpoint, then {@code grpcurl} is invoked in a
 * subprocess as a real external client. If the returned JSON output
 * matches the sent payload, the HTTP/2 wire protocol + 5-byte framing
 * + trailers are validated from a 100% independent point of view.
 *
 * <p>The {@code echo.proto} proto declares a single {@code EchoMessage}
 * used in request and response (tag 1, type string). On the server side,
 * the raw bytes are echoed without decoding protobuf — grpcurl decodes the
 * response again as {@code EchoMessage}, so every outbound payload must come back
 * identically as the inbound payload.
 *
 * <p>Test skipped if {@code grpcurl} is not in PATH (optional external
 * binary — installable via {@code brew install grpcurl}).
 */
class GrpcurlConformanceTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void grpcurlUnaryEcho() throws Exception {
        assumeTrue(grpcurlAvailable(), "grpcurl missing from PATH (skip — brew install grpcurl)");

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

        assertTrue(exited, "grpcurl did not finish within 15s, output=" + output);
        assertEquals(0, p.exitValue(), "grpcurl non-zero exit code, output=\n" + output);
        // Default grpcurl output format: pretty-printed JSON on stdout.
        // We only verify the payload is intact (server echoed it).
        assertTrue(output.contains("\"message\""), "response missing message field, output=\n" + output);
        assertTrue(output.contains("hello-from-grpcurl"), "response missing intact echoed payload, output=\n" + output);
    }

    @Test
    void grpcurlStatusOnFailingHandler() throws Exception {
        assumeTrue(grpcurlAvailable(), "grpcurl missing from PATH (skip)");

        var router = Router.builder()
                .grpc("/echo.EchoService/Echo", call -> {
                    call.receive();
                    // handler throws without complete() -> transport layer must emit
                    // grpc-status: 13 (INTERNAL) in trailers
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

        assertTrue(exited, "grpcurl did not finish within 15s, output=" + output);
        // grpcurl exits non-zero when grpc-status != 0 and prints the canonical
        // status ("Internal" for code 13).
        assertTrue(p.exitValue() != 0, "grpcurl should exit with error for grpc-status=13, exit=" + p.exitValue());
        assertTrue(
                output.contains("Internal") || output.contains("INTERNAL") || output.contains("Code: Internal"),
                "grpc-status trailers should indicate INTERNAL, output=\n" + output);
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
            if (in == null) throw new IOException("resource /grpc/echo.proto not found on classpath");
            Files.write(proto, in.readAllBytes());
        }
        proto.toFile().deleteOnExit();
        return dir;
    }
}
