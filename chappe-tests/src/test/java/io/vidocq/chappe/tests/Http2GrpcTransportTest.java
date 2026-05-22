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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.vidocq.chappe.api.GrpcHandler;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.http.grpc.GrpcFrameWriter;
import io.vidocq.chappe.http.h2.HpackDecoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de transport gRPC : 4 modes (unary, server-stream, client-stream, bidi)
 * + cas d'erreur (handler error, trailers-only) + rejet HTTP/1.1.
 * <p>
 * Client gRPC raw HTTP/2 : preface + SETTINGS + HEADERS + DATA (préfixe 5 octets)
 * + (optionnel) END_STREAM, et lecture des frames serveur.
 */
class Http2GrpcTransportTest {

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
    // 1. Unary RPC : 1 msg in → 1 msg out → trailers OK
    // ------------------------------------------------------------------
    @Test
    void unaryEcho() throws Exception {
        startServer("/echo", call -> {
            byte[] req = call.receive();
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), req);
            call.send(req);
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequest(out, 1, "/echo", true, GrpcFrameWriter.encode("hello".getBytes(StandardCharsets.UTF_8)));
            var seq = readUntilEndStream(in, 1);

            assertEquals("200", seq.initialHeaders().get(":status"));
            assertEquals("application/grpc", seq.initialHeaders().get("content-type"));
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), decodeOneGrpcMessage(seq.body.toByteArray()));
            assertEquals("0", seq.trailers().get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 2. Server-streaming : 1 in → N out → trailers OK
    // ------------------------------------------------------------------
    @Test
    void serverStreaming() throws Exception {
        startServer("/stream", call -> {
            call.receive(); // ignore
            for (int i = 0; i < 3; i++) {
                call.send(("msg-" + i).getBytes(StandardCharsets.UTF_8));
            }
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequest(out, 1, "/stream", true, GrpcFrameWriter.encode(new byte[] {0x42}));
            var seq = readUntilEndStream(in, 1);

            var msgs = decodeGrpcMessages(seq.body.toByteArray());
            assertEquals(3, msgs.size());
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(("msg-" + i).getBytes(StandardCharsets.UTF_8), msgs.get(i));
            }
            assertEquals("0", seq.trailers().get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 3. Client-streaming : N in (END_STREAM) → 1 out → trailers OK
    // ------------------------------------------------------------------
    @Test
    void clientStreaming() throws Exception {
        startServer("/collect", call -> {
            int total = 0;
            byte[] msg;
            while ((msg = call.receive()) != null) {
                total += msg.length;
            }
            call.send(("total=" + total).getBytes(StandardCharsets.UTF_8));
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            // HEADERS sans END_STREAM
            sendGrpcRequestHeaders(out, 1, "/collect", false);
            // 3 DATA frames sans END_STREAM
            writeFrame(out, TYPE_DATA, 0, 1, GrpcFrameWriter.encode("aaa".getBytes(StandardCharsets.UTF_8)));
            writeFrame(out, TYPE_DATA, 0, 1, GrpcFrameWriter.encode("bbbb".getBytes(StandardCharsets.UTF_8)));
            writeFrame(
                    out, TYPE_DATA, FLAG_END_STREAM, 1, GrpcFrameWriter.encode("cc".getBytes(StandardCharsets.UTF_8)));
            out.flush();

            var seq = readUntilEndStream(in, 1);
            var msgs = decodeGrpcMessages(seq.body.toByteArray());
            assertEquals(1, msgs.size());
            assertArrayEquals("total=9".getBytes(StandardCharsets.UTF_8), msgs.get(0));
            assertEquals("0", seq.trailers().get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 4. Bidi-streaming : 3 in / 3 out alternés
    //    On envoie 3 DATA puis END_STREAM. Le handler echo chaque message.
    // ------------------------------------------------------------------
    @Test
    void bidiStreaming() throws Exception {
        startServer("/bidi", call -> {
            byte[] msg;
            while ((msg = call.receive()) != null) {
                call.send(msg);
            }
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequestHeaders(out, 1, "/bidi", false);
            writeFrame(out, TYPE_DATA, 0, 1, GrpcFrameWriter.encode("one".getBytes(StandardCharsets.UTF_8)));
            writeFrame(out, TYPE_DATA, 0, 1, GrpcFrameWriter.encode("two".getBytes(StandardCharsets.UTF_8)));
            writeFrame(
                    out,
                    TYPE_DATA,
                    FLAG_END_STREAM,
                    1,
                    GrpcFrameWriter.encode("three".getBytes(StandardCharsets.UTF_8)));
            out.flush();

            var seq = readUntilEndStream(in, 1);
            var msgs = decodeGrpcMessages(seq.body.toByteArray());
            assertEquals(
                    List.of("one", "two", "three"),
                    msgs.stream()
                            .map(b -> new String(b, StandardCharsets.UTF_8))
                            .toList());
            assertEquals("0", seq.trailers().get("grpc-status"));
        }
    }

    // ------------------------------------------------------------------
    // 5. Handler error : status != OK avec grpc-message
    // ------------------------------------------------------------------
    @Test
    void handlerReturnsError() throws Exception {
        startServer("/err", call -> {
            call.receive();
            call.send(new byte[] {1, 2, 3});
            call.complete(GrpcStatus.INTERNAL, "kaboom");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequest(out, 1, "/err", true, GrpcFrameWriter.encode(new byte[] {0}));
            var seq = readUntilEndStream(in, 1);

            assertEquals("13", seq.trailers().get("grpc-status"));
            assertEquals("kaboom", seq.trailers().get("grpc-message"));
        }
    }

    // ------------------------------------------------------------------
    // 6. Trailers-Only : erreur immédiate, un seul HEADERS frame avec END_STREAM
    // ------------------------------------------------------------------
    @Test
    void trailersOnlyResponse() throws Exception {
        startServer("/deny", call -> call.complete(GrpcStatus.PERMISSION_DENIED, "no way"));

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequest(out, 1, "/deny", true, GrpcFrameWriter.encode(new byte[0]));
            var seq = readUntilEndStream(in, 1);

            // Un seul HEADERS frame (initial = trailers), pas de DATA.
            assertEquals(1, seq.headers.size(), "trailers-only doit produire UN HEADERS frame");
            assertEquals(0, seq.body.size(), "aucun DATA frame attendu");
            var h = seq.headers.get(0);
            assertEquals("200", h.get(":status"));
            assertEquals("application/grpc", h.get("content-type"));
            assertEquals("7", h.get("grpc-status"));
            assertEquals("no way", h.get("grpc-message"));
        }
    }

    // ------------------------------------------------------------------
    // 7a. grpc-timeout : handler dépasse la deadline → trailers DEADLINE_EXCEEDED (4)
    //     Le watchdog doit interrompre receive() et émettre les trailers
    //     bien avant que le handler ne finisse son sleep.
    // ------------------------------------------------------------------
    @Test
    void deadlineExceededViaGrpcTimeout() throws Exception {
        startServer("/slow", call -> {
            try {
                Thread.sleep(5_000); // bien plus long que la deadline de 200ms
            } catch (InterruptedException _) {
                // cancel via watchdog → InterruptedException ignored, handler sort
            }
            // Volontairement pas de complete : le watchdog l'a déjà fait.
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            long t0 = System.nanoTime();
            sendGrpcRequestHeadersWithTimeout(out, 1, "/slow", false, "200m");
            writeFrame(out, TYPE_DATA, FLAG_END_STREAM, 1, GrpcFrameWriter.encode("x".getBytes(StandardCharsets.UTF_8)));
            out.flush();
            var seq = readUntilEndStream(in, 1);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertEquals("4", seq.trailers().get("grpc-status"),
                    "grpc-status doit être DEADLINE_EXCEEDED (4)");
            assertEquals("deadline exceeded", seq.trailers().get("grpc-message"));
            assertTrue(elapsedMs < 2_000,
                    "trailers devraient arriver bien avant 2s (effectif=" + elapsedMs + "ms)");
        }
    }

    // ------------------------------------------------------------------
    // 7b. grpc-timeout respectée : handler termine avant deadline → OK (0)
    //     Vérifie que le watchdog est interrompu proprement et n'écrase
    //     pas le statut OK du handler.
    // ------------------------------------------------------------------
    @Test
    void deadlineRespectedReturnsOk() throws Exception {
        var observedDeadline = new AtomicReference<String>();
        startServer("/fast", call -> {
            observedDeadline.set(call.deadline()
                    .map(d -> d.toMillis() + "ms")
                    .orElse("<none>"));
            byte[] req = call.receive();
            call.send(req);
            call.complete(GrpcStatus.OK, "");
        });

        try (var socket = new Socket()) {
            connectAndHandshake(socket);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendGrpcRequestHeadersWithTimeout(out, 1, "/fast", false, "10S");
            writeFrame(out, TYPE_DATA, FLAG_END_STREAM, 1, GrpcFrameWriter.encode("hi".getBytes(StandardCharsets.UTF_8)));
            out.flush();
            var seq = readUntilEndStream(in, 1);

            assertEquals("0", seq.trailers().get("grpc-status"));
            assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), decodeOneGrpcMessage(seq.body.toByteArray()));
            // Le handler doit avoir vu une deadline non-vide.
            assertNotNull(observedDeadline.get());
            assertNotEquals("<none>", observedDeadline.get(),
                    "le handler doit observer la deadline propagée par le client");
        }
    }

    // ------------------------------------------------------------------
    // 8. Refus HTTP/1.1 → 505 HTTP Version Not Supported
    // ------------------------------------------------------------------
    @Test
    void rejectHttp11With505() throws Exception {
        startServer("/svc", call -> call.complete(GrpcStatus.OK, ""));

        // HttpClient HTTP/1.1 sur ce port
        var client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/svc"))
                .header("content-type", "application/grpc")
                .POST(HttpRequest.BodyPublishers.ofByteArray(GrpcFrameWriter.encode(new byte[0])))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
        assertEquals(505, resp.statusCode());
    }

    // ==================================================================
    // Helpers : serveur + client H2 minimal
    // ==================================================================

    private void startServer(String path, GrpcHandler handler) {
        var router = Router.builder().grpc(path, handler).build();
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
        // Lit SETTINGS serveur + SETTINGS ACK (2 frames de contrôle attendues)
        for (int i = 0; i < 2; i++) readFrame(in);
        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        out.flush();
    }

    private static void sendGrpcRequest(DataOutputStream out, int streamId, String path, boolean endStream, byte[] body)
            throws IOException {
        sendGrpcRequestHeaders(out, streamId, path, false);
        writeFrame(out, TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, body);
        out.flush();
    }

    private static void sendGrpcRequestHeadersWithTimeout(
            DataOutputStream out, int streamId, String path, boolean endStream, String timeout) throws IOException {
        var hb = new ByteArrayOutputStream();
        writeLiteral(hb, ":method", "POST");
        writeLiteral(hb, ":scheme", "http");
        writeLiteral(hb, ":authority", "127.0.0.1");
        writeLiteral(hb, ":path", path);
        writeLiteral(hb, "content-type", "application/grpc");
        writeLiteral(hb, "te", "trailers");
        writeLiteral(hb, "grpc-timeout", timeout);
        int flags = FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0);
        writeFrame(out, TYPE_HEADERS, flags, streamId, hb.toByteArray());
    }

    private static void sendGrpcRequestHeaders(DataOutputStream out, int streamId, String path, boolean endStream)
            throws IOException {
        var hb = new ByteArrayOutputStream();
        writeLiteral(hb, ":method", "POST");
        writeLiteral(hb, ":scheme", "http");
        writeLiteral(hb, ":authority", "127.0.0.1");
        writeLiteral(hb, ":path", path);
        writeLiteral(hb, "content-type", "application/grpc");
        writeLiteral(hb, "te", "trailers");
        int flags = FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0);
        writeFrame(out, TYPE_HEADERS, flags, streamId, hb.toByteArray());
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

    private static void writeLiteral(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        writeStr(out, name);
        writeStr(out, value);
    }

    private static void writeStr(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        int mask = 0x7F;
        if (bytes.length < mask) {
            out.write(bytes.length);
        } else {
            out.write(mask);
            int v = bytes.length - mask;
            while (v >= 128) {
                out.write((v & 0x7F) | 0x80);
                v >>= 7;
            }
            out.write(v);
        }
        out.write(bytes, 0, bytes.length);
    }

    private static final class ResponseSequence {
        final java.util.List<Map<String, String>> headers = new java.util.ArrayList<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Map<String, String> initialHeaders() {
            return headers.get(0);
        }

        Map<String, String> trailers() {
            return headers.get(headers.size() - 1);
        }
    }

    private static ResponseSequence readUntilEndStream(DataInputStream in, int streamId) throws IOException {
        var seq = new ResponseSequence();
        var decoder = new HpackDecoder(4096, 64 * 1024);
        while (true) {
            var f = readFrame(in);
            if (f.streamId == 0) continue;
            if (f.streamId != streamId) continue;
            if (f.type == TYPE_HEADERS) {
                var map = new LinkedHashMap<String, String>();
                try {
                    decoder.decode(ByteBuffer.wrap(f.payload), map::put);
                } catch (Exception e) {
                    throw new IOException("HPACK decode failed", e);
                }
                seq.headers.add(map);
                if ((f.flags & FLAG_END_STREAM) != 0) return seq;
            } else if (f.type == TYPE_DATA) {
                seq.body.write(f.payload);
                if ((f.flags & FLAG_END_STREAM) != 0) return seq;
            }
        }
    }

    /** Décodage du payload gRPC (préfixe 5 octets + payload, possiblement répété). */
    private static java.util.List<byte[]> decodeGrpcMessages(byte[] data) {
        var out = new java.util.ArrayList<byte[]>();
        int i = 0;
        while (i + 5 <= data.length) {
            int len = ((data[i + 1] & 0xFF) << 24)
                    | ((data[i + 2] & 0xFF) << 16)
                    | ((data[i + 3] & 0xFF) << 8)
                    | (data[i + 4] & 0xFF);
            byte[] payload = new byte[len];
            System.arraycopy(data, i + 5, payload, 0, len);
            out.add(payload);
            i += 5 + len;
        }
        return out;
    }

    private static byte[] decodeOneGrpcMessage(byte[] data) {
        var msgs = decodeGrpcMessages(data);
        assertEquals(1, msgs.size(), "expected exactly one gRPC message");
        return msgs.get(0);
    }

    /** Variable inutilisée — la requête GET ne sert que dans rejectHttp11With505 et n'a pas de body. */
    @SuppressWarnings("unused")
    private static Object _unused(AtomicReference<?> ignored) {
        return null;
    }
}
