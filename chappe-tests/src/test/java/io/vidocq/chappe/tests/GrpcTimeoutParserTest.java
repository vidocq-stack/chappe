package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.chappe.http.grpc.GrpcCallImpl;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du parser de {@code grpc-timeout} (RFC gRPC §"Requests" — Timeout grammar).
 * <p>
 * Grammaire : {@code TimeoutValue (1-8 digits) TimeoutUnit (H|M|S|m|u|n)}.
 */
class GrpcTimeoutParserTest {

    @Test
    void parsesNanoseconds() {
        assertEquals(1L, GrpcCallImpl.parseTimeoutNanos("1n"));
        assertEquals(999L, GrpcCallImpl.parseTimeoutNanos("999n"));
    }

    @Test
    void parsesMicroseconds() {
        assertEquals(1_000L, GrpcCallImpl.parseTimeoutNanos("1u"));
        assertEquals(500_000L, GrpcCallImpl.parseTimeoutNanos("500u"));
    }

    @Test
    void parsesMilliseconds() {
        assertEquals(1_000_000L, GrpcCallImpl.parseTimeoutNanos("1m"));
        assertEquals(200_000_000L, GrpcCallImpl.parseTimeoutNanos("200m"));
    }

    @Test
    void parsesSeconds() {
        assertEquals(1_000_000_000L, GrpcCallImpl.parseTimeoutNanos("1S"));
        assertEquals(30_000_000_000L, GrpcCallImpl.parseTimeoutNanos("30S"));
    }

    @Test
    void parsesMinutes() {
        assertEquals(60L * 1_000_000_000L, GrpcCallImpl.parseTimeoutNanos("1M"));
        assertEquals(5L * 60L * 1_000_000_000L, GrpcCallImpl.parseTimeoutNanos("5M"));
    }

    @Test
    void parsesHours() {
        assertEquals(3_600L * 1_000_000_000L, GrpcCallImpl.parseTimeoutNanos("1H"));
        assertEquals(24L * 3_600L * 1_000_000_000L, GrpcCallImpl.parseTimeoutNanos("24H"));
    }

    @Test
    void parsesMaxEightDigits() {
        // Spec : up to 8 digits. 99999999u = ~99 secondes
        long v = GrpcCallImpl.parseTimeoutNanos("99999999u");
        assertEquals(99_999_999L * 1_000L, v);
    }

    @Test
    void rejectsNullOrEmpty() {
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos(null));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos(""));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("S"));
    }

    @Test
    void rejectsTooLong() {
        // >9 chars (8 digits + 1 unit max)
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("999999999n"));
    }

    @Test
    void rejectsUnknownUnit() {
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("100x"));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("100s")); // 's' minuscule != 'S'
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("100h")); // 'h' minuscule != 'H'
    }

    @Test
    void rejectsNonNumericValue() {
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("abcS"));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("1aS"));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("-1S"));
    }

    @Test
    void rejectsZeroOrNegative() {
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("0S"));
        assertEquals(-1L, GrpcCallImpl.parseTimeoutNanos("0n"));
    }

    @Test
    void saturatesOnOverflow() {
        // 99999999H * 3600 * 1e9 > Long.MAX_VALUE -> saturé à MAX_VALUE
        long v = GrpcCallImpl.parseTimeoutNanos("99999999H");
        assertTrue(v == Long.MAX_VALUE, "overflow doit saturer à MAX_VALUE, got " + v);
    }
}
