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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Filter;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;

import org.junit.jupiter.api.Test;

/**
 * {@link Filter#accessLog}: forwarding headers ({@code X-Forwarded-For}, {@code X-Real-IP},
 * {@code X-Forwarded-User}, {@code Gap-Auth}) are honored only from a trusted proxy (loopback /
 * private / link-local peer, or an explicit CIDR list), and logged values are escaped so one
 * request cannot forge extra fields in the log line (security audit F-03 / F-10).
 */
class AccessLogTrustedProxyTest {

    @Test
    void xForwardedForIsHonoredFromALoopbackPeer() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add).apply(_ -> Response.ok("ok"));

        h.handle(req(peer("127.0.0.1"), "/", Headers.builder()
                .add("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                .build()));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().startsWith("203.0.113.9 - "), lines.getFirst());
    }

    @Test
    void xForwardedForIsIgnoredFromAPublicPeer() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add).apply(_ -> Response.ok("ok"));

        h.handle(req(peer("203.0.113.50"), "/", Headers.builder()
                .add("X-Forwarded-For", "198.51.100.7")
                .add("X-Real-IP", "198.51.100.8")
                .build()));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().startsWith("203.0.113.50 - "), lines.getFirst());
        assertFalse(lines.getFirst().contains("198.51.100.7"), lines.getFirst());
        assertFalse(lines.getFirst().contains("198.51.100.8"), lines.getFirst());
    }

    @Test
    void xForwardedForIsHonoredFromACustomTrustedCidr() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add, List.of("10.0.0.0/8")).apply(_ -> Response.ok("ok"));

        h.handle(req(peer("10.1.2.3"), "/", Headers.builder()
                .add("X-Forwarded-For", "203.0.113.9")
                .build()));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().startsWith("203.0.113.9 - "), lines.getFirst());
    }

    @Test
    void xForwardedForIsIgnoredWhenThePeerIsOutsideTheCustomCidr() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add, List.of("10.0.0.0/8")).apply(_ -> Response.ok("ok"));

        h.handle(req(peer("192.0.2.10"), "/", Headers.builder()
                .add("X-Forwarded-For", "203.0.113.9")
                .build()));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().startsWith("192.0.2.10 - "), lines.getFirst());
    }

    @Test
    void forwardedUserIsHonoredFromATrustedPeerOnly() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler trusted = Filter.accessLog(lines::add).apply(_ -> Response.ok("ok"));
        trusted.handle(req(peer("127.0.0.1"), "/", Headers.builder()
                .add("X-Forwarded-User", "yann@example.com")
                .build()));
        Handler untrusted = Filter.accessLog(lines::add).apply(_ -> Response.ok("ok"));
        untrusted.handle(req(peer("203.0.113.50"), "/", Headers.builder()
                .add("Gap-Auth", "mallory@example.com")
                .build()));

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("127.0.0.1 - yann@example.com "), lines.get(0));
        assertTrue(lines.get(1).startsWith("203.0.113.50 - - "), lines.get(1));
        assertFalse(lines.get(1).contains("mallory"), lines.get(1));
    }

    @Test
    void quotesBackslashesAndControlCharsInThePathAreEscaped() throws Exception {
        List<String> lines = new ArrayList<>();
        Handler h = Filter.accessLog(lines::add).apply(_ -> Response.ok("ok"));
        String evilPath = "/a\"b\\c\nd\te";

        h.handle(req(peer("203.0.113.50"), evilPath, Headers.empty()));

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertFalse(line.contains("\n"), "raw newline must never reach the log: " + line);
        assertFalse(line.contains("\t"), "raw tab must never reach the log: " + line);
        assertTrue(line.contains("/a\\\"b\\\\c\\u000ad\\u0009e"), line);
    }

    private static InetSocketAddress peer(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 51234);
    }

    private static Request req(InetSocketAddress remote, String path, Headers headers) {
        return new Request() {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public URI uri() {
                return URI.create("http://test" + path);
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public HttpVersion version() {
                return HttpVersion.HTTP_1_1;
            }

            @Override
            public Headers headers() {
                return headers;
            }

            @Override
            public Body body() {
                return Body.empty();
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParams() {
                return Map.of();
            }

            @Override
            public InetSocketAddress remoteAddress() {
                return remote;
            }
        };
    }
}
