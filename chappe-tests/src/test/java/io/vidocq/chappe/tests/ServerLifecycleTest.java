package io.vidocq.chappe.tests;

import io.vidocq.chappe.api.ChappeException;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerLifecycleTest {

    @Test
    void startAndStop() {
        var server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok("hello"))
                .build();

        assertFalse(server.isRunning());

        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.port() > 0);

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void ephemeralPort() {
        try (var server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok())
                .build()) {
            server.start();
            int port = server.port();
            assertTrue(port > 0 && port < 65536, "Port should be valid: " + port);
            assertNotNull(server.localAddress());
            assertEquals(port, server.localAddress().getPort());
        }
    }

    @Test
    void doubleStartThrows() {
        try (var server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok())
                .build()) {
            server.start();
            assertThrows(ChappeException.ServerException.class, server::start);
        }
    }

    @Test
    void stopIdempotent() {
        var server = Server.builder()
                .port(0)
                .handler(_ -> Response.ok())
                .build();
        server.start();
        server.stop();
        // Second stop should not throw
        assertDoesNotThrow(server::stop);
    }

    @Test
    void tryWithResources() {
        Server server;
        try (var s = Server.builder()
                .port(0)
                .handler(_ -> Response.ok())
                .build()) {
            s.start();
            server = s;
            assertTrue(server.isRunning());
        }
        assertFalse(server.isRunning());
    }

    @Test
    void configPreserved() {
        try (var server = Server.builder()
                .port(0)
                .host("127.0.0.1")
                .backlog(512)
                .maxRequestSize(1024)
                .maxHeaderSize(4096)
                .handler(_ -> Response.ok())
                .build()) {
            var config = server.config();
            assertEquals("127.0.0.1", config.host());
            assertEquals(0, config.port());
            assertEquals(512, config.backlog());
            assertEquals(1024, config.maxRequestSize());
            assertEquals(4096, config.maxHeaderSize());
        }
    }

    @Test
    void builderRequiresHandler() {
        assertThrows(IllegalStateException.class, () ->
                Server.builder().port(0).build());
    }
}
