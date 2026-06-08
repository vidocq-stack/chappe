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
package io.vidocq.chappe.api.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Java <b>gRPC-Web</b> client, zero external dependencies (based on the JDK's {@link HttpClient}).
 * <p>
 * Why gRPC-Web instead of standard gRPC? {@code java.net.http.HttpClient} does not provide
 * access to HTTP/2 trailers (documented upstream), while {@code grpc-status} is carried
 * precisely in a trailer. gRPC-Web serializes trailers inline in the response body
 * (DATA frame with {@code 0x80} prefix), so they can be read from HttpClient.
 *
 * <p><b>Supported modes:</b>
 * <ul>
 *   <li>{@link #unary}: 1 message in → 1 message out + trailers</li>
 *   <li>{@link #serverStream}: 1 message in → N messages out + trailers (collected)</li>
 * </ul>
 * <p><b>Out of scope for v1:</b> client-streaming and bidi-streaming (HttpClient does not support
 * full duplex on the request body side), native gRPC (requires a custom HTTP/2 client).
 *
 * <p><b>Body mode:</b> {@link Mode#BINARY} (content type {@code application/grpc-web})
 * by default, or {@link Mode#TEXT} ({@code application/grpc-web-text} with Base64).
 *
 * <p>Usage:
 * <pre>{@code
 * var client = GrpcWebClient.builder()
 *         .baseUri(URI.create("http://127.0.0.1:8080"))
 *         .build();
 * var resp = client.unary("/echo.EchoService/Echo", "hello".getBytes(UTF_8));
 * if (resp.isOk()) System.out.println(new String(resp.firstMessage(), UTF_8));
 * }</pre>
 */
public final class GrpcWebClient {

    /** Prefix of the gRPC-Web trailer frame ({@code 0x80}, MSB set). */
    private static final byte TRAILER_FLAG = (byte) 0x80;

    /** Transport encoding chosen for request/response bodies. */
    public enum Mode {
        /** {@code application/grpc-web}: raw binary payload. */
        BINARY,
        /** {@code application/grpc-web-text}: the entire body Base64-encoded. */
        TEXT
    }

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Mode mode;
    private final Duration timeout;

    private GrpcWebClient(Builder b) {
        this.httpClient = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_2)
                        .connectTimeout(b.connectTimeout)
                        .build();
        this.baseUri = Objects.requireNonNull(b.baseUri, "baseUri");
        this.mode = b.mode;
        this.timeout = b.timeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Unary call: sends one message, waits for one message + trailers.
     *
     * @param path gRPC path ({@code /service.Name/Method}) — concatenated with {@code baseUri}
     * @param requestPayload opaque request bytes (protobuf/JSON serialization is the
     *                       caller's responsibility)
     */
    public GrpcWebResponse unary(String path, byte[] requestPayload) throws IOException, InterruptedException {
        return invoke(path, requestPayload);
    }

    /**
     * Server-streaming call: sends one message, collects the N received messages + trailers.
     * <p>
     * V1: the entire body is read at once and then parsed. Incremental streaming (callback
     * on each message) may come in a later iteration if needed.
     */
    public GrpcWebResponse serverStream(String path, byte[] requestPayload) throws IOException, InterruptedException {
        return invoke(path, requestPayload);
    }

    private GrpcWebResponse invoke(String path, byte[] requestPayload) throws IOException, InterruptedException {
        URI uri = baseUri.resolve(path);
        byte[] framedRequest = encodeMessageFrame(requestPayload, false);
        byte[] bodyOnWire = (mode == Mode.TEXT) ? Base64.getEncoder().encode(framedRequest) : framedRequest;

        String contentType = (mode == Mode.TEXT) ? "application/grpc-web-text" : "application/grpc-web";
        var req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("content-type", contentType)
                .header("accept", contentType)
                .header("te", "trailers")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyOnWire))
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from gRPC-Web endpoint " + uri);
        }
        byte[] body = resp.body();
        if (mode == Mode.TEXT && body.length > 0) {
            body = Base64.getDecoder().decode(body);
        }
        return parseBody(body);
    }

    /**
     * Encodes a gRPC message (5-byte prefix + payload). The {@code compressed=0} flag
     * is used in v1 — outgoing client-side compression is not exposed.
     */
    private static byte[] encodeMessageFrame(byte[] payload, boolean compressed) {
        int len = payload.length;
        byte[] out = new byte[5 + len];
        out[0] = (byte) (compressed ? 1 : 0);
        out[1] = (byte) ((len >>> 24) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        return out;
    }

    /**
     * Parses the gRPC-Web body (sequence of 5-byte-framed records): separates application
     * messages from the final trailer frame ({@code 0x80} prefix).
     */
    private static GrpcWebResponse parseBody(byte[] body) {
        var messages = new ArrayList<byte[]>();
        Map<String, String> trailers = new LinkedHashMap<>();
        int i = 0;
        while (i < body.length) {
            if (i + 5 > body.length) {
                throw new IllegalStateException("truncated gRPC-Web frame at offset " + i);
            }
            byte flag = body[i];
            int len = ((body[i + 1] & 0xFF) << 24)
                    | ((body[i + 2] & 0xFF) << 16)
                    | ((body[i + 3] & 0xFF) << 8)
                    | (body[i + 4] & 0xFF);
            if (i + 5 + len > body.length) {
                throw new IllegalStateException("truncated gRPC-Web payload at offset " + i + ", need " + len);
            }
            byte[] payload = java.util.Arrays.copyOfRange(body, i + 5, i + 5 + len);
            if ((flag & 0x80) != 0) {
                trailers = parseTrailerPayload(payload);
            } else {
                messages.add(payload);
            }
            i += 5 + len;
        }
        int status = -1;
        String message = null;
        if (trailers.containsKey("grpc-status")) {
            try {
                status = Integer.parseInt(trailers.get("grpc-status"));
            } catch (NumberFormatException _) {
                status = -1;
            }
            message = trailers.get("grpc-message");
        }
        return new GrpcWebResponse(messages, status, message, trailers);
    }

    private static Map<String, String> parseTrailerPayload(byte[] payload) {
        var map = new LinkedHashMap<String, String>();
        String text = new String(payload, StandardCharsets.US_ASCII);
        for (String line : text.split("\r\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            map.put(name, value);
        }
        return map;
    }

    /** Test hook: allows injecting an {@link HttpClient} configured differently. */
    public HttpClient httpClient() {
        return httpClient;
    }

    public Mode mode() {
        return mode;
    }

    public URI baseUri() {
        return baseUri;
    }

    public static final class Builder {
        private HttpClient httpClient;
        private URI baseUri;
        private Mode mode = Mode.BINARY;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration connectTimeout = Duration.ofSeconds(10);

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        public GrpcWebClient build() {
            return new GrpcWebClient(this);
        }
    }

    /** List of standard gRPC codes exposed to simplify client-side tests/assertions. */
    public static final class Status {
        public static final int OK = 0;
        public static final int CANCELLED = 1;
        public static final int UNKNOWN = 2;
        public static final int INVALID_ARGUMENT = 3;
        public static final int DEADLINE_EXCEEDED = 4;
        public static final int NOT_FOUND = 5;
        public static final int ALREADY_EXISTS = 6;
        public static final int PERMISSION_DENIED = 7;
        public static final int RESOURCE_EXHAUSTED = 8;
        public static final int FAILED_PRECONDITION = 9;
        public static final int ABORTED = 10;
        public static final int OUT_OF_RANGE = 11;
        public static final int UNIMPLEMENTED = 12;
        public static final int INTERNAL = 13;
        public static final int UNAVAILABLE = 14;
        public static final int DATA_LOSS = 15;
        public static final int UNAUTHENTICATED = 16;

        private Status() {}
    }
}
