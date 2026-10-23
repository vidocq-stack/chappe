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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.RequestBodyTooLargeException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The chunked transfer coding decoder (RFC 9112 §7.1), over one read and byte by byte. */
class ChunkedInputStreamTest {

    private final AtomicReference<Headers> trailers = new AtomicReference<>();

    @Test
    void returnsTheDataOfOneChunkPerArrayRead() throws IOException {
        var in = stream("3\r\nabc\r\n5;ext=1\r\ndefgh\r\n0\r\n\r\n", 4096, Long.MAX_VALUE);
        var b = new byte[100];

        assertEquals(3, in.read(b, 0, 100));
        assertEquals("abc", new String(b, 0, 3, StandardCharsets.ISO_8859_1));
        assertEquals(5, in.read(b, 10, 100 - 10));
        assertEquals("defgh", new String(b, 10, 5, StandardCharsets.ISO_8859_1));
        assertEquals(-1, in.read(b, 0, 100));
        assertEquals(-1, in.read(b, 0, 100));
    }

    @Test
    void returnsWhatTheBufferHoldsOfAChunkAndAtMostTheLengthAsked() throws IOException {
        var in = stream("A\r\n0123456789\r\n0\r\n\r\n", 6, Long.MAX_VALUE);
        var b = new byte[10];

        assertEquals(0, in.read(b, 0, 0));
        assertEquals(1, in.read(b, 0, 1), "the length asked bounds the read");
        assertEquals(2, in.read(b, 1, 9), "the rest of the first channel read");
        assertEquals(6, in.read(b, 3, 7));
        assertEquals(1, in.read(b, 9, 1));
        assertEquals("0123456789", new String(b, StandardCharsets.ISO_8859_1));
        assertEquals(-1, in.read(b, 0, 10));
    }

    @ParameterizedTest
    @MethodSource("bodies")
    void decodesTheBodyOverEveryReadSize(String wire, String body) throws IOException {
        for (int sliceSize : new int[] {1, 3, 4096}) {
            assertEquals(body, readAllByArrays(stream(wire, sliceSize, Long.MAX_VALUE)), "array reads");
            assertEquals(body, readAllByBytes(stream(wire, sliceSize, Long.MAX_VALUE)), "single-byte reads");
        }
    }

    static Stream<Arguments> bodies() {
        return Stream.of(
                Arguments.of("3\r\nabc\r\n0\r\n\r\n", "abc"),
                Arguments.of("3;name=value;x\r\nabc\r\n0;last\r\n\r\n", "abc"),
                Arguments.of("3\nabc\n2\nde\n0\n\n", "abcde"),
                Arguments.of("a\r\n0123456789\r\nB\r\nabcdefghijk\r\n0\r\n\r\n", "0123456789abcdefghijk"),
                Arguments.of("3\r\nabcX2\r\nde\r\n0\r\n\r\n", "abcde"),
                Arguments.of("0\r\n\r\n", ""));
    }

    @Test
    void deliversTheTrailerFieldsWithANameAndIgnoresTheOthers() throws IOException {
        var in = stream("1\r\nx\r\n0\r\nX-A: 1\r\nX-B:  two \r\nnot a field\r\n: no name\r\n\r\n", 1, Long.MAX_VALUE);

        assertEquals("x", readAllByArrays(in));
        var fields = trailers.get();
        assertEquals(2, fields.size());
        assertEquals("1", fields.firstOrNull("X-A"));
        assertEquals("two", fields.firstOrNull("X-B"));
    }

    @Test
    void deliversTheTrailerFieldsReadBeforeAnEndOfStream() throws IOException {
        var in = stream("0\r\nX-A: 1\r\nX-B: 2", 4096, Long.MAX_VALUE);

        assertEquals("", readAllByArrays(in));
        assertEquals("1", trailers.get().firstOrNull("X-A"));
        assertNull(trailers.get().firstOrNull("X-B"), "a line without its LF is not a field");
    }

    @Test
    void deliversNoTrailersWhenThereAreNone() throws IOException {
        assertEquals("ab", readAllByArrays(stream("2\r\nab\r\n0\r\n\r\n", 1, Long.MAX_VALUE)));
        assertNull(trailers.get());
    }

    @ParameterizedTest
    @MethodSource("brokenBodies")
    void failsOnABrokenBodyAndKeepsFailing(String wire, Class<? extends IOException> failure, String message) {
        var in = stream(wire, 2, Long.MAX_VALUE);

        var first = assertThrows(failure, () -> readAllByArrays(in));
        assertEquals(message, first.getMessage());
        var again = assertThrows(IOException.class, in::read);
        assertEquals(first, again);
    }

    static Stream<Arguments> brokenBodies() {
        return Stream.of(
                Arguments.of("\r\nabc", IOException.class, "Empty chunk size"),
                Arguments.of("3G\r\nabc", IOException.class, "Invalid hex digit in chunk size: G"),
                Arguments.of("10000000000000000\r\n", IOException.class, "Chunk size overflow"),
                Arguments.of("3", IOException.class, "Unexpected end of chunked stream"),
                Arguments.of("5\r\nab", EOFException.class, "Unexpected end of chunked stream inside a chunk"));
    }

    @Test
    void refusesAChunkTakingTheBodyPastItsCapBeforeReadingIt() {
        var in = stream("4\r\nabcd\r\n0\r\n\r\n", 4096, 6);
        var b = new byte[64];

        assertThrows(RequestBodyTooLargeException.class, () -> in.read(b, 0, b.length));
    }

    private ChunkedInputStream stream(String wire, int sliceSize, long maxBodySize) {
        var buffer = ByteBuffer.allocate(4096).flip();
        return new ChunkedInputStream(buffer, new SlicedChannel(wire, sliceSize), trailers::set, maxBodySize);
    }

    private static String readAllByArrays(ChunkedInputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        var b = new byte[4];
        int n;
        while ((n = in.read(b, 0, b.length)) != -1) {
            out.write(b, 0, n);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static String readAllByBytes(ChunkedInputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            out.write(c);
        }
        assertArrayEquals(new byte[0], in.readNBytes(1));
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
