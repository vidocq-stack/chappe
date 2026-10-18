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
package io.vidocq.chappe.http.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.Headers;

import org.junit.jupiter.api.Test;

/**
 * CHAPPE-018: an HTTP/2 response carries no connection-specific field (RFC 9113 §8.2.2) and no value with
 * leading or trailing whitespace (§8.2.1); either makes the message malformed, and strict clients reset the stream.
 */
class HpackEncoderResponseFieldsTest {

    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder(4096, 8192, 65_536);

    private List<String> decode(byte[] block) throws Exception {
        var fields = new ArrayList<String>();
        decoder.decode(ByteBuffer.wrap(block), (name, value) -> fields.add(name + "=" + value));
        return fields;
    }

    @Test
    void connection_specific_fields_are_not_sent() throws Exception {
        Headers headers = Headers.builder()
                .add("Connection", "close")
                .add("Keep-Alive", "timeout=5")
                .add("Proxy-Connection", "keep-alive")
                .add("Transfer-Encoding", "chunked")
                .add("Upgrade", "h2c")
                .add("X-Kept", "yes")
                .build();

        assertEquals(List.of(":status=200", "x-kept=yes"), decode(encoder.encode(200, headers)));
    }

    @Test
    void te_is_sent_only_as_trailers() throws Exception {
        assertEquals(List.of(":status=200"), decode(encoder.encode(200, Headers.of("TE", "gzip"))));
        assertEquals(List.of(":status=200", "te=trailers"), decode(encoder.encode(200, Headers.of("te", "trailers"))));
    }

    @Test
    void surrounding_whitespace_is_trimmed() throws Exception {
        Headers headers = Headers.builder()
                .add("X-Padded", " padded ")
                .add("X-Tabs", "\tvalue\t")
                .add("X-Inner", "a b")
                .build();

        assertEquals(
                List.of(":status=200", "x-padded=padded", "x-tabs=value", "x-inner=a b"),
                decode(encoder.encode(200, headers)));
    }

    @Test
    void trailers_follow_the_same_rules() throws Exception {
        Headers trailers = Headers.builder()
                .add("Connection", "close")
                .add("grpc-status", " 0 ")
                .build();

        assertEquals(List.of("grpc-status=0"), decode(encoder.encodeTrailers(trailers)));
    }
}
