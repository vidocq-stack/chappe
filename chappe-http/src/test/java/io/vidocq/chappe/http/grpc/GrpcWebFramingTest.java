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
package io.vidocq.chappe.http.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.vidocq.chappe.api.Headers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trailer names of a gRPC-Web trailer frame are lowercased the same way whatever the default
 * locale of the JVM: under a Turkish default, {@code "ID".toLowerCase()} gives a dotless {@code ı},
 * which is not even ASCII (CHAPPE-040).
 */
class GrpcWebFramingTest {

    private Locale previousDefault;

    @BeforeEach
    void useATurkishDefaultLocale() {
        previousDefault = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
    }

    @AfterEach
    void restoreTheDefaultLocale() {
        Locale.setDefault(previousDefault);
    }

    @Test
    void trailerNamesAreWrittenInLowercaseUnderATurkishDefaultLocale() {
        byte[] frame = GrpcWebFraming.encodeTrailerFrame(
                Headers.builder().add("X-Trace-ID", "1").build());

        String payload = new String(Arrays.copyOfRange(frame, 5, frame.length), StandardCharsets.US_ASCII);
        assertEquals("x-trace-id:1\r\n", payload);
    }

    @Test
    void trailerNamesAreReadInLowercaseUnderATurkishDefaultLocale() {
        Map<String, String> trailers =
                GrpcWebFraming.parseTrailerPayload("X-Trace-ID:1\r\n".getBytes(StandardCharsets.US_ASCII));

        assertEquals(Map.of("x-trace-id", "1"), trailers);
    }

    @Test
    void emptyLinesInATrailerPayloadAreIgnored() {
        Map<String, String> trailers = GrpcWebFraming.parseTrailerPayload(
                "grpc-status:0\r\n\r\ngrpc-message:ok\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

        assertEquals(List.of("grpc-status", "grpc-message"), List.copyOf(trailers.keySet()));
        assertEquals(List.of("0", "ok"), List.copyOf(trailers.values()));
    }
}
