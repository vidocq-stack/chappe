package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.http.h2.HpackDecoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests trailers HTTP/2 (RFC 9113 §8.1) — envoi et réception.
 * <p>
 * Client H2 raw : preface + SETTINGS + HEADERS + DATA + HEADERS (trailers).
 * Encodage HPACK littéral sans indexation pour rester minimal côté client.
 */
class Http2TrailersTest {

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

    @BeforeEach
    void setUp() {
        // Default no-op handler — overridden per test.
    }

    @Test
    void serverSendsResponseTrailers() throws Exception {
        startServer(_ -> Response.builder()
                .body("hello")
                .trailer("x-server-status", "0")
                .trailer("x-trace-id", "abc-123")
                .build());

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendPrefaceAndSettings(in, out);

            // GET /
            byte[] headerBlock = encodeRequestHeaders("GET", "/", null);
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, headerBlock);
            out.flush();

            // Lit la réponse jusqu'au trailers (HEADERS frame avec END_STREAM)
            var seq = readUntilEndStream(in, 1);

            // Doit y avoir au moins : HEADERS (status), DATA (hello), HEADERS (trailers)
            assertTrue(
                    seq.headers.size() >= 2, "expected initial HEADERS + trailers HEADERS, got " + seq.headers.size());

            var initial = seq.headers.get(0);
            assertEquals("200", initial.get(":status"));

            var trailers = seq.headers.get(seq.headers.size() - 1);
            assertFalse(trailers.containsKey(":status"), "trailers must not contain pseudo-headers");
            assertEquals("0", trailers.get("x-server-status"));
            assertEquals("abc-123", trailers.get("x-trace-id"));

            assertEquals("hello", new String(seq.body.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void serverReceivesClientTrailers() throws Exception {
        var receivedTrailers = new AtomicReference<Map<String, String>>();
        startServer(req -> {
            // Consomme entièrement le body AVANT de lire les trailers (contrat documenté).
            try {
                req.body().asInputStream().readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var snap = new LinkedHashMap<String, String>();
            for (var entry : req.trailers()) {
                snap.put(entry.name(), entry.value());
            }
            receivedTrailers.set(snap);
            return Response.ok();
        });

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            sendPrefaceAndSettings(in, out);

            // POST / avec body et trailers
            byte[] headerBlock = encodeRequestHeaders("POST", "/", "5");
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS, 1, headerBlock);

            // DATA frame (no END_STREAM)
            writeFrame(out, TYPE_DATA, 0, 1, "hello".getBytes(StandardCharsets.UTF_8));

            // Trailers HEADERS (END_STREAM)
            byte[] trailerBlock = encodeTrailers(Map.of("x-client-status", "42", "x-request-id", "xyz"));
            writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, 1, trailerBlock);
            out.flush();

            // Lit la réponse jusqu'au END_STREAM
            readUntilEndStream(in, 1);

            var trailers = receivedTrailers.get();
            assertNotNull(trailers, "handler n'a pas vu les trailers");
            assertEquals("42", trailers.get("x-client-status"));
            assertEquals("xyz", trailers.get("x-request-id"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers H2 client minimal
    // -------------------------------------------------------------------------

    private void startServer(io.vidocq.chappe.api.Handler handler) {
        var router = Router.builder().get("/", handler).post("/", handler).build();
        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    private static void sendPrefaceAndSettings(DataInputStream in, DataOutputStream out) throws IOException {
        out.write(H2_PREFACE);
        // SETTINGS vide
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();

        // Lit le SETTINGS initial + SETTINGS ACK jusqu'à recevoir un ACK
        // (au plus quelques frames de contrôle, on filtre par stream 0).
        // Pour rester simple : on lit 2 frames de contrôle et on continue.
        for (int i = 0; i < 2; i++) {
            readFrame(in);
        }
        // Envoie notre SETTINGS ACK
        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        out.flush();
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

    /** Encode des trailers sans pseudo-header. */
    private static byte[] encodeTrailers(Map<String, String> trailers) {
        var out = new ByteArrayOutputStream();
        for (var e : trailers.entrySet()) {
            writeLiteralWithoutIndexing(out, e.getKey(), e.getValue());
        }
        return out.toByteArray();
    }

    /** Encode une requête HEADERS : pseudo-headers + Host + éventuel content-length. */
    private static byte[] encodeRequestHeaders(String method, String path, String contentLength) {
        var out = new ByteArrayOutputStream();
        writeLiteralWithoutIndexing(out, ":method", method);
        writeLiteralWithoutIndexing(out, ":scheme", "http");
        writeLiteralWithoutIndexing(out, ":authority", "127.0.0.1");
        writeLiteralWithoutIndexing(out, ":path", path);
        if (contentLength != null) {
            writeLiteralWithoutIndexing(out, "content-length", contentLength);
        }
        return out.toByteArray();
    }

    /**
     * HPACK Literal without Indexing (§6.2.2), nouveau nom : 0000 0000 + name + value (sans Huffman).
     */
    private static void writeLiteralWithoutIndexing(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00); // 0000 0000 = literal without indexing, new name
        writeRawString(out, name);
        writeRawString(out, value);
    }

    private static void writeRawString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        // length sur 7 bits, H=0 (pas de Huffman)
        encodeInteger(out, bytes.length, 7, 0x00);
        out.write(bytes, 0, bytes.length);
    }

    private static void encodeInteger(ByteArrayOutputStream out, int value, int prefix, int pattern) {
        int mask = (1 << prefix) - 1;
        if (value < mask) {
            out.write(pattern | value);
        } else {
            out.write(pattern | mask);
            value -= mask;
            while (value >= 128) {
                out.write((value & 0x7F) | 0x80);
                value >>= 7;
            }
            out.write(value);
        }
    }

    /** Lecture d'une séquence de frames côté serveur sur un stream donné jusqu'à END_STREAM. */
    private static ResponseSequence readUntilEndStream(DataInputStream in, int streamId) throws IOException {
        var seq = new ResponseSequence();
        // Décodeur HPACK partagé : table dynamique stateful entre HEADERS.
        var decoder = new HpackDecoder(4096, 64 * 1024);
        while (true) {
            var f = readFrame(in);
            if (f.streamId == 0) continue; // control frame
            if (f.streamId != streamId) continue;
            if (f.type == TYPE_HEADERS) {
                var headersMap = new LinkedHashMap<String, String>();
                try {
                    decoder.decode(ByteBuffer.wrap(f.payload), headersMap::put);
                } catch (Exception e) {
                    throw new IOException("HPACK decode failed", e);
                }
                seq.headers.add(headersMap);
                if ((f.flags & FLAG_END_STREAM) != 0) return seq;
            } else if (f.type == TYPE_DATA) {
                seq.body.write(f.payload);
                if ((f.flags & FLAG_END_STREAM) != 0) return seq;
            }
            // ignore RST_STREAM, WINDOW_UPDATE etc.
        }
    }

    private static final class ResponseSequence {
        final java.util.List<Map<String, String>> headers = new java.util.ArrayList<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
    }
}
