package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.CloseCodes;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.WebSocketHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WebSocket integration tests via {@link java.net.http.WebSocket} (JDK client).
 */
class WebSocketEchoTest {

    private Server server;
    private String wsBaseUrl;

    @BeforeEach
    void setUp() {
        var openLatch = new CountDownLatch(1);

        var router = Router.builder()
                .webSocket("/echo", new WebSocketHandler() {
                    @Override
                    public void onOpen(io.vidocq.chappe.api.WebSocket ws, io.vidocq.chappe.api.Request handshake) {
                        openLatch.countDown();
                    }

                    @Override
                    public void onText(io.vidocq.chappe.api.WebSocket ws, String message) throws Exception {
                        ws.sendText(message);
                    }

                    @Override
                    public void onBinary(io.vidocq.chappe.api.WebSocket ws, ByteBuffer data) throws Exception {
                        var copy = ByteBuffer.allocate(data.remaining());
                        copy.put(data).flip();
                        ws.sendBinary(copy);
                    }
                })
                .webSocket("/close-1011", new WebSocketHandler() {
                    @Override
                    public void onOpen(io.vidocq.chappe.api.WebSocket ws, io.vidocq.chappe.api.Request h)
                            throws Exception {
                        ws.close(CloseCodes.INTERNAL_ERROR, "boom");
                    }
                })
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        wsBaseUrl = "ws://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void textEcho() throws Exception {
        var messages = new ConcurrentLinkedQueue<String>();
        var done = new CountDownLatch(1);

        var ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUrl + "/echo"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(
                            WebSocket ws, CharSequence data, boolean last) {
                        messages.add(data.toString());
                        done.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        ws.sendText("hello", true).get(5, TimeUnit.SECONDS);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("hello"), new ArrayList<>(messages));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
    }

    @Test
    void binaryEcho() throws Exception {
        var received = new CountDownLatch(1);
        var buf = new byte[1024];
        for (int i = 0; i < buf.length; i++) buf[i] = (byte) (i & 0xFF);
        var holder = new byte[buf.length];

        var ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUrl + "/echo"), new WebSocket.Listener() {
                    private int offset = 0;

                    @Override
                    public java.util.concurrent.CompletionStage<?> onBinary(
                            WebSocket ws, ByteBuffer data, boolean last) {
                        int n = Math.min(data.remaining(), holder.length - offset);
                        data.get(holder, offset, n);
                        offset += n;
                        if (last) received.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        ws.sendBinary(ByteBuffer.wrap(buf), true).get(5, TimeUnit.SECONDS);
        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertArrayEquals(buf, holder);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    }

    @Test
    void pingPong() throws Exception {
        var pongReceived = new CountDownLatch(1);

        var ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUrl + "/echo"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onPong(WebSocket ws, ByteBuffer payload) {
                        pongReceived.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        ws.sendPing(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
        assertTrue(pongReceived.await(5, TimeUnit.SECONDS), "Server must auto-PONG to PING");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    }

    @Test
    void serverInitiatedCloseWithCode() throws Exception {
        var closeLatch = new CountDownLatch(1);
        var statusHolder = new int[1];

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUrl + "/close-1011"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onClose(
                            WebSocket ws, int statusCode, String reason) {
                        statusHolder[0] = statusCode;
                        closeLatch.countDown();
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "Server must send Close");
        assertEquals(CloseCodes.INTERNAL_ERROR, statusHolder[0]);
    }

    @Test
    void largeTextMessage() throws Exception {
        var sb = new StringBuilder(200_000);
        for (int i = 0; i < 200_000; i++) sb.append((char) ('a' + (i % 26)));
        var expected = sb.toString();
        var got = new CountDownLatch(1);
        var assembled = new StringBuilder();

        var ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUrl + "/echo"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(
                            WebSocket ws, CharSequence data, boolean last) {
                        assembled.append(data);
                        if (last) got.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        ws.sendText(expected, true).get(10, TimeUnit.SECONDS);
        assertTrue(got.await(10, TimeUnit.SECONDS));
        assertEquals(expected, assembled.toString());

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    }
}
