package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vidocq.chappe.api.GrpcHandler;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.http.grpc.GrpcFrameWriter;
import io.vidocq.chappe.http.grpc.GrpcWebFraming;
import io.vidocq.chappe.http.h2.HpackDecoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de transport <b>gRPC-Web</b> sur HTTP/2 :
 * <ul>
 *   <li>Mode BINARY ({@code application/grpc-web}) : trailers inline (préfixe 0x80)</li>
 *   <li>Mode TEXT ({@code application/grpc-web-text}) : tout le corps en Base64</li>
 * </ul>
 * Le client raw HTTP/2 reproduit exactement ce que ferait grpc-web (Improbable, grpc-js
 * en mode web, etc.) : POST + content-type spécifique, payload framé 5 octets, et lit
 * la frame DATA spéciale 0x80 en fin de stream pour reconstituer les trailers.
 */
class Http2GrpcWebTransportTest {

    private static final byte[] H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_ACK = 0x1;

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // ------------------------------------------------------------------
    // 1. BINARY unary echo : trailers inline 0x80 + grpc-status:0
    // ------------------------------------------------------------------
    @Test
    void binaryUnaryEcho() throws Exception {
        startServer("/echo", call -> {
            byte[] req = call.receive();
            call.send(req);
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcWebRequest(
                    out,
                    1,
                    "/echo",
                    "application/grpc-web",
                    true,
                    GrpcFrameWriter.encode("hello".getBytes(StandardCharsets.UTF_8)));
            var seq = readUntilEndStream(in, 1);

            assertEquals("200", seq.initialHeaders().get(":status"));
            assertEquals("application/grpc-web", seq.initialHeaders().get("content-type"));

            byte[] body = seq.body.toByteArray();
            // Body = [message frame] + [trailer frame 0x80]
            var parsed = parseGrpcWebFrames(body);
            assertEquals(1, parsed.messages.size(), "1 message reçu");
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), parsed.messages.get(0));
            assertEquals("0", parsed.trailers.get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 2. BINARY server-streaming : N messages puis trailer 0x80
    // ------------------------------------------------------------------
    @Test
    void binaryServerStreaming() throws Exception {
        startServer("/stream", call -> {
            call.receive();
            for (int i = 0; i < 3; i++) {
                call.send(("msg-" + i).getBytes(StandardCharsets.UTF_8));
            }
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcWebRequest(
                    out, 1, "/stream", "application/grpc-web", true, GrpcFrameWriter.encode(new byte[] {0x42}));
            var seq = readUntilEndStream(in, 1);
            var parsed = parseGrpcWebFrames(seq.body.toByteArray());

            assertEquals(3, parsed.messages.size());
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(("msg-" + i).getBytes(StandardCharsets.UTF_8), parsed.messages.get(i));
            }
            assertEquals("0", parsed.trailers.get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 3. TEXT unary : tout le corps request/response est Base64
    // ------------------------------------------------------------------
    @Test
    void textUnaryEcho() throws Exception {
        startServer("/echo-text", call -> {
            byte[] req = call.receive();
            call.send(req);
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            byte[] framed = GrpcFrameWriter.encode("bonjour".getBytes(StandardCharsets.UTF_8));
            byte[] base64Body = Base64.getEncoder().encode(framed);

            sendGrpcWebRequest(out, 1, "/echo-text", "application/grpc-web-text", true, base64Body);
            var seq = readUntilEndStream(in, 1);

            assertEquals("application/grpc-web-text", seq.initialHeaders().get("content-type"));
            // Body est intégralement Base64 : on décode puis on parse comme du gRPC-Web binaire
            byte[] decodedBody = Base64.getDecoder().decode(seq.body.toByteArray());
            var parsed = parseGrpcWebFrames(decodedBody);
            assertEquals(1, parsed.messages.size());
            assertArrayEquals("bonjour".getBytes(StandardCharsets.UTF_8), parsed.messages.get(0));
            assertEquals("0", parsed.trailers.get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 4. Erreur handler : trailers contiennent grpc-status: 13 + grpc-message
    // ------------------------------------------------------------------
    @Test
    void binaryHandlerErrorEmitsStatusInTrailer() throws Exception {
        startServer("/boom", call -> {
            call.receive();
            call.complete(GrpcStatus.INTERNAL, "kaboom");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcWebRequest(out, 1, "/boom", "application/grpc-web", true, GrpcFrameWriter.encode(new byte[0]));
            var seq = readUntilEndStream(in, 1);
            var parsed = parseGrpcWebFrames(seq.body.toByteArray());

            assertEquals(0, parsed.messages.size(), "aucun message en cas d'erreur immédiate");
            assertEquals("13", parsed.trailers.get("grpc-status"));
            assertEquals("kaboom", parsed.trailers.get("grpc-message"));
        }
    }

    // ------------------------------------------------------------------
    // 5. content-type non grpc-web -> 415 (route grpcWeb ne matche pas application/json)
    // ------------------------------------------------------------------
    @Test
    void unknownContentTypeReturns415() throws Exception {
        startServer("/svc", call -> call.complete(GrpcStatus.OK, ""));

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcWebRequest(out, 1, "/svc", "application/json", true, new byte[] {0x7b, 0x7d});
            var seq = readUntilEndStream(in, 1);

            assertEquals("415", seq.initialHeaders().get(":status"));
        }
    }

    // ------------------------------------------------------------------
    // 6. HTTP/1.1 supporté : gRPC-Web tourne sur H1 via Body.ofOutputStream
    //    + chunked transfer encoding (les trailers étant déjà inline 0x80,
    //    pas besoin de trailers HTTP/2). Couvre le cas navigateur fetch/XHR
    //    sans HTTP/2 et HttpClient JDK en cleartext.
    // ------------------------------------------------------------------
    @Test
    void http11BinaryUnaryEchoWorks() throws Exception {
        startServer("/h1-echo", call -> {
            byte[] req = call.receive();
            call.send(req);
            call.complete(GrpcStatus.OK, "");
        });

        var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        byte[] framed = GrpcFrameWriter.encode("ahoi".getBytes(StandardCharsets.UTF_8));
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/h1-echo"))
                .header("content-type", "application/grpc-web")
                .POST(HttpRequest.BodyPublishers.ofByteArray(framed))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode());
        assertEquals(
                "application/grpc-web",
                resp.headers().firstValue("content-type").orElse(null));
        var parsed = parseGrpcWebFrames(resp.body());
        assertEquals(1, parsed.messages.size());
        assertArrayEquals("ahoi".getBytes(StandardCharsets.UTF_8), parsed.messages.get(0));
        assertEquals("0", parsed.trailers.get("grpc-status"));
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private void startServer(String path, GrpcHandler handler) {
        var router = Router.builder().grpcWeb(path, handler).build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    private void connectAndHandshake(Socket socket) throws IOException {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);
        var in = new DataInputStream(socket.getInputStream());
        var out = new DataOutputStream(socket.getOutputStream());
        out.write(H2_PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();
        for (int i = 0; i < 2; i++) readFrame(in);
        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        out.flush();
    }

    private static void sendGrpcWebRequest(
            DataOutputStream out, int streamId, String path, String contentType, boolean endStream, byte[] body)
            throws IOException {
        var hb = new ByteArrayOutputStream();
        writeLiteral(hb, ":method", "POST");
        writeLiteral(hb, ":scheme", "http");
        writeLiteral(hb, ":authority", "127.0.0.1");
        writeLiteral(hb, ":path", path);
        writeLiteral(hb, "content-type", contentType);
        writeLiteral(hb, "te", "trailers");
        int flags = FLAG_END_HEADERS;
        writeFrame(out, TYPE_HEADERS, flags, streamId, hb.toByteArray());
        writeFrame(out, TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, body);
        out.flush();
    }

    /** Résultat parsé d'un body gRPC-Web : messages + trailers. */
    private record ParsedGrpcWeb(List<byte[]> messages, Map<String, String> trailers) {}

    /**
     * Parse un body gRPC-Web binaire : suite de frames de 5 octets de préfixe + payload.
     * Le préfixe {@code 0x80} marque le trailer frame final (payload = headers texte).
     */
    private static ParsedGrpcWeb parseGrpcWebFrames(byte[] body) {
        var msgs = new java.util.ArrayList<byte[]>();
        Map<String, String> trailers = Map.of();
        int i = 0;
        while (i < body.length) {
            byte flag = body[i];
            int len = ((body[i + 1] & 0xFF) << 24)
                    | ((body[i + 2] & 0xFF) << 16)
                    | ((body[i + 3] & 0xFF) << 8)
                    | (body[i + 4] & 0xFF);
            byte[] payload = java.util.Arrays.copyOfRange(body, i + 5, i + 5 + len);
            if (GrpcWebFraming.isTrailerFlag(flag)) {
                trailers = GrpcWebFraming.parseTrailerPayload(payload);
            } else {
                msgs.add(payload);
            }
            i += 5 + len;
        }
        return new ParsedGrpcWeb(msgs, trailers);
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    private record Frame(int length, int type, int flags, int streamId, byte[] payload) {}

    private static Frame readFrame(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int len = (b0 << 16) | (b1 << 8) | b2;
        int type = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int streamId = in.readInt() & 0x7FFFFFFF;
        byte[] payload = in.readNBytes(len);
        return new Frame(len, type, flags, streamId, payload);
    }

    private record StreamSequence(List<Map<String, String>> headers, ByteArrayOutputStream body) {
        Map<String, String> initialHeaders() {
            return headers.get(0);
        }
    }

    private StreamSequence readUntilEndStream(DataInputStream in, int streamId) throws IOException {
        var headers = new java.util.ArrayList<Map<String, String>>();
        var body = new ByteArrayOutputStream();
        var hpackDecoder = new HpackDecoder(4096, 64 * 1024);
        while (true) {
            var f = readFrame(in);
            if (f.streamId == 0 || f.streamId != streamId) continue;
            if (f.type == TYPE_HEADERS) {
                var map = new LinkedHashMap<String, String>();
                try {
                    hpackDecoder.decode(ByteBuffer.wrap(f.payload), map::put);
                } catch (Exception e) {
                    throw new IOException("HPACK decode failed", e);
                }
                headers.add(map);
            } else if (f.type == TYPE_DATA) {
                body.write(f.payload);
            }
            if ((f.flags & FLAG_END_STREAM) != 0) break;
        }
        return new StreamSequence(headers, body);
    }

    /** HPACK littéral sans indexation (huffman=0). */
    private static void writeLiteral(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        byte[] nb = name.getBytes(StandardCharsets.US_ASCII);
        writeIntegerOnPrefix(out, nb.length, 7);
        out.writeBytes(nb);
        byte[] vb = value.getBytes(StandardCharsets.US_ASCII);
        writeIntegerOnPrefix(out, vb.length, 7);
        out.writeBytes(vb);
    }

    private static void writeIntegerOnPrefix(ByteArrayOutputStream out, int value, int prefixBits) {
        int max = (1 << prefixBits) - 1;
        if (value < max) {
            out.write(value & 0xFF);
        } else {
            out.write(max);
            int v = value - max;
            while (v >= 128) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
    }
}
