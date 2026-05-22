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
 * Client <b>gRPC-Web</b> Java, zéro dépendance externe (basé sur {@link HttpClient} du JDK).
 * <p>
 * Pourquoi gRPC-Web et pas gRPC standard ? {@code java.net.http.HttpClient} ne donne
 * pas accès aux trailers HTTP/2 (documenté upstream), or {@code grpc-status} est
 * justement transporté en trailer. gRPC-Web sérialise les trailers inline dans le
 * corps de la réponse (frame DATA avec préfixe {@code 0x80}), donc lisibles depuis
 * HttpClient.
 *
 * <p><b>Modes supportés :</b>
 * <ul>
 *   <li>{@link #unary} : 1 message in → 1 message out + trailers</li>
 *   <li>{@link #serverStream} : 1 message in → N messages out + trailers (collectés)</li>
 * </ul>
 * <p><b>Hors scope v1 :</b> client-streaming et bidi-streaming (HttpClient ne supporte
 * pas le full duplex côté request body), gRPC natif (nécessite un client HTTP/2 custom).
 *
 * <p><b>Mode body :</b> {@link Mode#BINARY} (content-type {@code application/grpc-web})
 * par défaut, ou {@link Mode#TEXT} ({@code application/grpc-web-text} avec Base64).
 *
 * <p>Usage :
 * <pre>{@code
 * var client = GrpcWebClient.builder()
 *         .baseUri(URI.create("http://127.0.0.1:8080"))
 *         .build();
 * var resp = client.unary("/echo.EchoService/Echo", "hello".getBytes(UTF_8));
 * if (resp.isOk()) System.out.println(new String(resp.firstMessage(), UTF_8));
 * }</pre>
 */
public final class GrpcWebClient {

    /** Préfixe du trailer frame gRPC-Web ({@code 0x80}, MSB set). */
    private static final byte TRAILER_FLAG = (byte) 0x80;

    /** Encodage de transport choisi pour les bodies request/response. */
    public enum Mode {
        /** {@code application/grpc-web} : payload binaire brut. */
        BINARY,
        /** {@code application/grpc-web-text} : tout le body Base64-encodé. */
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
     * Appel unary : envoie un message, attend un message + trailers.
     *
     * @param path chemin gRPC ({@code /service.Name/Method}) — sera concaténé au {@code baseUri}
     * @param requestPayload bytes opaques du request (la sérialisation protobuf/JSON est
     *                       à la charge de l'appelant)
     */
    public GrpcWebResponse unary(String path, byte[] requestPayload) throws IOException, InterruptedException {
        return invoke(path, requestPayload);
    }

    /**
     * Appel server-streaming : envoie un message, collecte les N messages reçus + trailers.
     * <p>
     * V1 : on lit tout le body en une fois puis on parse. Le streaming incrémental (callback
     * sur chaque message) viendra dans une itération ultérieure si besoin.
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
     * Encode un message gRPC (préfixe 5 octets + payload). Le flag {@code compressed=0}
     * en v1 — la compression sortante côté client n'est pas exposée.
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
     * Parse le body gRPC-Web (suite de frames 5 octets) : sépare les messages applicatifs
     * du trailer frame final (préfixe {@code 0x80}).
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

    /** Marker pour les tests : permet d'injecter un {@link HttpClient} configuré différemment. */
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

    /** Liste de codes gRPC standards exposée pour faciliter les tests/assert client-side. */
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
