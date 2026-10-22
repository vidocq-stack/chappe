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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.chappe.api.client.GrpcWebClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * gRPC-Web over HTTP/1.1 under a Turkish default locale, where {@code "ID".toLowerCase()} gives a
 * dotless {@code ı}: the content type and the trailer names are matched and written the same way
 * whatever the default locale of the JVM (CHAPPE-040).
 */
class GrpcWebLocaleTest {

    private Locale previousDefault;
    private Server server;

    @BeforeEach
    void useATurkishDefaultLocale() {
        previousDefault = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) server.stop();
        } finally {
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    void anUppercaseContentTypeIsAcceptedUnderATurkishDefaultLocale() throws Exception {
        start(Router.builder()
                .grpcWeb("/svc/Echo", call -> {
                    call.send(call.receive());
                    call.complete(GrpcStatus.OK, "");
                })
                .build());

        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/svc/Echo"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "APPLICATION/GRPC-WEB")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {0, 0, 0, 0, 0}))
                .build();
        var response = http1Client().send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
    }

    @Test
    void trailerNamesAreSentInLowercaseUnderATurkishDefaultLocale() throws Exception {
        start(Router.builder()
                .grpcWeb("/svc/Trace", call -> {
                    call.receive();
                    call.addTrailer("X-Trace-ID", "1");
                    call.complete(GrpcStatus.OK, "");
                })
                .build());

        var response = client().unary("/svc/Trace", new byte[] {1});

        assertEquals("1", response.trailers().get("x-trace-id"), () -> "trailers: " + response.trailers());
    }

    @Test
    void theClientReadsUppercaseTrailerNamesUnderATurkishDefaultLocale() throws Exception {
        var body = new ByteArrayOutputStream();
        byte[] trailers = "grpc-status:0\r\nX-Trace-ID:1\r\n".getBytes(StandardCharsets.US_ASCII);
        body.write(new byte[] {(byte) 0x80, 0, 0, 0, (byte) trailers.length});
        body.write(trailers);
        start(Router.builder()
                .post("/svc/Raw", _ -> Response.builder()
                        .status(StatusCode.OK)
                        .header("content-type", "application/grpc-web")
                        .body(Body.of(body.toByteArray()))
                        .build())
                .build());

        var response = client().unary("/svc/Raw", new byte[] {1});

        assertEquals("1", response.trailers().get("x-trace-id"), () -> "trailers: " + response.trailers());
    }

    private void start(Router router) {
        server = Server.builder().port(0).handler(router).build();
        server.start();
    }

    private GrpcWebClient client() {
        return GrpcWebClient.builder()
                .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                .httpClient(http1Client())
                .build();
    }

    /** Cleartext HTTP/2 needs prior knowledge, which the JDK client does not do: stay on HTTP/1.1. */
    private static HttpClient http1Client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
