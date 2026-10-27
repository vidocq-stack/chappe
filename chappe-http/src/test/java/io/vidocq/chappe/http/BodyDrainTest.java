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
package io.vidocq.chappe.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * After a handler, the connection drains what is left of the request body before the next
 * request. The drain allocated a 4 KB buffer for every request with a body, also when the handler
 * had read it to its end, which is the usual case (a JSON entity bound by the application).
 */
class BodyDrainTest {

    private static final String JSON = "{\"title\":\"buy milk\",\"completed\":false}";

    @Test
    void aBodyReadToItsEndIsDrainedWithoutABuffer() throws IOException {
        RequestBodyInputStream body = body(JSON);
        assertEquals(JSON, new String(body.readAllBytes(), StandardCharsets.US_ASCII));
        assertTrue(HttpBodyReader.drain(body, 1 << 20));
        long perCall = bytesPerCall(() -> {
            try {
                HttpBodyReader.drain(body, 1 << 20);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        assertTrue(perCall < 64, "draining a body read to its end allocates " + perCall + " bytes per call");
    }

    @Test
    void aBodyLeftUnreadIsStillDrained() throws IOException {
        RequestBodyInputStream body = body("x".repeat(10_000));
        assertTrue(HttpBodyReader.drain(body, 1 << 20));
        assertEquals(-1, body.read());
    }

    @Test
    void aBodyLongerThanTheBudgetIsStillReported() throws IOException {
        assertFalse(HttpBodyReader.drain(body("x".repeat(100)), 10));
        assertFalse(HttpBodyReader.drain(body("x"), 0));
        assertTrue(HttpBodyReader.drain(body("xyz"), 3));
    }

    /** A fixed-length body whose bytes all came with the request head, as a small JSON body does. */
    private static RequestBodyInputStream body(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
        return HttpBodyReader.fixedLength(ByteBuffer.wrap(bytes), new SlicedChannel("", 1), bytes.length);
    }

    /** Mean bytes the current thread allocates per call, after a warm-up. HotSpot accounts them
     *  exactly; {@code com.sun.management} is reached through reflection because the module
     *  under test reads neither {@code java.management} nor {@code jdk.management}. */
    private static long bytesPerCall(Runnable call) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean")
                    .invoke(null);
            Method allocated =
                    Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            for (int i = 0; i < 2_000; i++) call.run();
            long before = (Long) allocated.invoke(threads);
            for (int i = 0; i < 10_000; i++) call.run();
            return ((Long) allocated.invoke(threads) - before) / 10_000;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }
}
