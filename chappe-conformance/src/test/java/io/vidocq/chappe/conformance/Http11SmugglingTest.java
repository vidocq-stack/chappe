package io.vidocq.chappe.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Anti request-smuggling conformance tests — RFC 9112 §6.3 and §11.2.
 *
 * <p>A request whose framing is ambiguous (a {@code Transfer-Encoding} whose final coding is not
 * {@code chunked}, both {@code Transfer-Encoding} and {@code Content-Length}, duplicate or
 * non-canonical {@code Content-Length} values) must be rejected with 400 and the connection
 * closed: the server never guesses between the two framings, so a front proxy and chappe cannot
 * disagree on where the body ends (CL.TE / TE.CL / CL.CL smuggling).</p>
 */
class Http11SmugglingTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        var router = Router.builder()
                .post("/echo", req -> {
                    byte[] body = req.body().asInputStream().readAllBytes();
                    return Response.ok(new String(body, StandardCharsets.UTF_8));
                })
                .get("/nobody", _ -> Response.ok("no-body-expected"))
                .build();

        server = Server.builder().port(0).handler(router).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                // TE present but chunked is not the final coding → 400 (RFC 9112 §6.3)
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: gzip\r\nContent-Length: 5\r\n\r\nhello",
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked, gzip\r\nContent-Length: 0\r\n\r\n",
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: identity\r\nContent-Length: 5\r\n\r\nhello",
                // two TE headers whose combined final coding is not chunked → 400
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: gzip\r\nContent-Length: 0\r\n\r\n",
                // TE + CL together is a smuggling signal → reject, never silently prefer one
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 6\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                // duplicate CL with different values → 400
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nContent-Length: 10\r\n\r\nhello",
                // a single CL header listing different values → 400
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5, 10\r\n\r\nhello",
                // non-canonical CL forms that proxies parse differently → 400
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: +5\r\n\r\nhello",
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 007\r\n\r\nhello",
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5x\r\n\r\nhello",
                // chunked requires HTTP/1.1
                "POST /echo HTTP/1.0\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            })
    void ambiguousFramingIsRejectedWith400(String rawRequest) {
        String response = RawHttp.sendAndReceive(port, rawRequest);

        assertEquals(400, RawHttp.extractStatusCode(response), "request must be rejected: " + rawRequest);
    }

    @Test
    void identicalContentLengthListIsAccepted() {
        // RFC 9112 §6.3: a single CL with a list of identical values may be treated as one value
        String body = "hello";
        String response = RawHttp.sendAndReceive(
                port, "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5, 5\r\n\r\n" + body);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(body, RawHttp.extractBody(response));
    }

    @Test
    void duplicateIdenticalContentLengthHeadersAreAccepted() {
        String body = "hello";
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n" + body);

        assertEquals(200, RawHttp.extractStatusCode(response));
        assertEquals(body, RawHttp.extractBody(response));
    }

    @Test
    void smuggledSecondRequestIsNeverAnswered() {
        // Classic CL.TE payload: the "body" of the first request is a whole second request.
        // The server must answer 400 to the first request and close — never parse the smuggled GET.
        String response = RawHttp.sendAndReceive(
                port,
                "POST /echo HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 44\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n"
                        + "0\r\n\r\nGET /nobody HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertEquals(400, RawHttp.extractStatusCode(response));
        assertFalse(response.contains("no-body-expected"), "the smuggled request must not be answered");
        assertEquals(1, countOccurrences(response, "HTTP/1.1 "), "exactly one response on the connection");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
