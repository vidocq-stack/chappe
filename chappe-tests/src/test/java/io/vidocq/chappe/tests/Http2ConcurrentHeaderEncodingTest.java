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

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrent responses on one HTTP/2 connection keep the peer's HPACK context in step (CHAPPE-034).
 * The server's HPACK encoder inserts into its dynamic table as it encodes, so the header blocks
 * must reach the wire in the order they were encoded. Many streams answer at the same instant with
 * header values that repeat across rounds, so later blocks refer to earlier insertions by index:
 * if two blocks were written in the opposite order from their encoding, the client's strict HPACK
 * decoder would fail or hand back another stream's values.
 */
class Http2ConcurrentHeaderEncodingTest {

    private static final int STREAMS = 64;
    private static final int ROUNDS = 30;

    private final AtomicReference<CountDownLatch> barrier = new AtomicReference<>();

    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /**
     * {@code /echo/<round>/<id>}: waits for every stream of the round, so they all answer at once,
     * then answers with {@code x-echo: id-<id>} (repeated across rounds) and {@code x-nonce} (new in
     * each round). One stream in three has no body, one a body, one a body and a trailer.
     */
    private Response handle(Request request) throws InterruptedException {
        String[] parts = request.path().split("/");
        String round = parts[2];
        int id = Integer.parseInt(parts[3]);
        CountDownLatch latch = barrier.get();
        latch.countDown();
        latch.await(5, TimeUnit.SECONDS);
        var builder = Response.builder()
                .status(StatusCode.OK)
                .header("x-echo", echo(id))
                .header("x-nonce", nonce(round, id));
        switch (id % 3) {
            case 0 -> {}
            case 1 -> builder.body("body-" + id);
            default -> builder.body("body-" + id).trailer("x-trailer", echo(id));
        }
        return builder.build();
    }

    private static String echo(int id) {
        return "id-%03d".formatted(id);
    }

    private static String nonce(String round, int id) {
        return "r%s-%03d".formatted(round, id);
    }

    @Test
    void concurrentResponsesDecodeInTheOrderTheyWereEncoded() throws IOException {
        try (var h2 = H2TestConnection.open(server.port())) {
            int nextStreamId = 1;
            for (int round = 0; round < ROUNDS; round++) {
                barrier.set(new CountDownLatch(STREAMS));
                Map<Integer, Integer> idOf = new HashMap<>();
                for (int id = 0; id < STREAMS; id++) {
                    idOf.put(nextStreamId, id);
                    h2.headers(nextStreamId, true, requestHeaders("GET", "/echo/" + round + "/" + id));
                    nextStreamId += 2;
                }
                h2.flush();
                assertRound(h2, round, idOf);
            }
        }
    }

    /** Reads every frame of the round up to the end of its last stream and checks each response. */
    private static void assertRound(H2TestConnection h2, int round, Map<Integer, Integer> idOf) throws IOException {
        Map<Integer, List<Map<String, String>>> blocks = new HashMap<>();
        int open = idOf.size();
        while (open > 0) {
            Frame f;
            try {
                f = h2.read();
            } catch (IOException e) {
                throw new AssertionError("round " + round + ": the HPACK context diverged: " + e, e);
            }
            if (f.type() == TYPE_GOAWAY) {
                fail("round " + round + ": GOAWAY (error " + f.goawayErrorCode() + ")");
            }
            if (!idOf.containsKey(f.streamId())) continue;
            if (f.type() == TYPE_RST_STREAM) {
                fail("round " + round + ": stream " + f.streamId() + " reset (error " + f.errorCode() + ")");
            }
            if (f.type() == TYPE_HEADERS) {
                blocks.computeIfAbsent(f.streamId(), _ -> new ArrayList<>()).add(f.headers());
            }
            if (f.endStream()) open--;
        }
        String roundText = String.valueOf(round);
        for (var e : idOf.entrySet()) {
            int id = e.getValue();
            List<Map<String, String>> got = blocks.get(e.getKey());
            assertNotNull(got, "round " + round + ": no HEADERS on stream " + e.getKey());
            Map<String, String> headers = got.getFirst();
            assertEquals("200", headers.get(":status"), "round " + round + " id " + id);
            assertEquals(echo(id), headers.get("x-echo"), "round " + round + " id " + id + ": x-echo");
            assertEquals(nonce(roundText, id), headers.get("x-nonce"), "round " + round + " id " + id + ": x-nonce");
            if (id % 3 == 2) {
                assertEquals(2, got.size(), "round " + round + " id " + id + ": a trailer block");
                assertEquals(echo(id), got.get(1).get("x-trailer"), "round " + round + " id " + id + ": x-trailer");
            } else {
                assertEquals(1, got.size(), "round " + round + " id " + id + ": one block");
            }
        }
    }
}
