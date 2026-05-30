package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TLS integration tests — HTTPS with a self-signed certificate.
 * <p>
 * Covers HTTP/1.1 over TLS, multiple keep-alive requests,
 * and HTTP/2 via ALPN negotiation.
 */
class TlsTest {

    private static Path keystorePath;
    private static SSLContext serverSslContext;
    private static SSLContext trustAllContext;

    private Server server;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll
    static void generateKeystore() throws Exception {
        // Generate a self-signed keystore via keytool
        keystorePath = Files.createTempFile("chappe-test-", ".p12");
        Files.delete(keystorePath); // keytool refuses to overwrite an existing file
        var process = new ProcessBuilder(
                        "keytool", "-genkeypair",
                        "-alias", "chappe",
                        "-keyalg", "RSA",
                        "-keysize", "2048",
                        "-validity", "1",
                        "-dname", "CN=localhost",
                        "-storetype", "PKCS12",
                        "-keystore", keystorePath.toString(),
                        "-storepass", "changeit",
                        "-keypass", "changeit",
                        "-ext", "san=ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        assertEquals(0, process.exitValue(), "keytool failed");

        // Load keystore on server side
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(keystorePath)) {
            keyStore.load(is, "changeit".toCharArray());
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());

        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        // Client SSLContext that trusts everything
        trustAllContext = SSLContext.getInstance("TLS");
        trustAllContext.init(
                null,
                new javax.net.ssl.TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] c, String a) {}

                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                    }
                },
                null);
    }

    @AfterAll
    static void cleanupKeystore() throws IOException {
        if (keystorePath != null) Files.deleteIfExists(keystorePath);
    }

    @BeforeEach
    void setUp() {
        server = Server.builder()
                .port(0)
                .tls(serverSslContext)
                .handler(_ -> Response.ok("Hello TLS!"))
                .build();
        server.start();
        baseUrl = "https://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(trustAllContext)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void httpsGetRequest() throws IOException, InterruptedException {
        var response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("Hello TLS!", response.body());
    }

    @Test
    void httpsMultipleRequests() throws IOException, InterruptedException {
        for (int i = 0; i < 5; i++) {
            var response = get("/");
            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void httpsH2ViaAlpn() throws IOException, InterruptedException {
        var h2Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustAllContext)
                .build();
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/")).GET().build();
        var response = h2Client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Hello TLS!", response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
