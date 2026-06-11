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
package io.vidocq.chappe.api;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP request — read-only view exposed to {@link Handler handlers}.
 * <p>
 * Implementations (in {@code chappe-http}) may reuse and recycle instances
 * to minimise allocations.
 */
public interface Request {

    /** HTTP method (GET, POST, …). */
    HttpMethod method();

    /** Full request URI. */
    URI uri();

    /** Request path (without query string). */
    String path();

    /** Raw query string, or {@code null} if absent. */
    String query();

    /** HTTP protocol version. */
    HttpVersion version();

    /** Request headers. */
    Headers headers();

    /** Request body. */
    Body body();

    /**
     * HTTP trailers sent by the client after the body (HTTP/2 §8.1, chunked HTTP/1.1 §7.1.2).
     * <p>
     * Empty by default. In HTTP/2, trailers arrive in a second HEADERS frame
     * after the DATA frames. Call only <b>after</b> fully reading
     * {@link #body()}, otherwise may return {@link Headers#empty()}.
     */
    default Headers trailers() {
        return Headers.empty();
    }

    /** Shortcut: first value of header {@code name}. */
    default Optional<String> header(String name) {
        return headers().first(name);
    }

    /**
     * Path parameters captured by the router (e.g. {@code {id} → "42"}).
     * Empty if the router matched no parameters.
     */
    Map<String, String> pathParams();

    /**
     * Query string parameters.
     * If a key is duplicated, only the last value is kept.
     */
    Map<String, String> queryParams();

    /** Shortcut: value of a query-string parameter. */
    default Optional<String> queryParam(String name) {
        return Optional.ofNullable(queryParams().get(name));
    }

    /** Context path set by mount(), empty string "" by default. */
    default String contextPath() {
        return "";
    }

    /** Path after context stripping. For unmounted handlers, equals path(). */
    default String pathInfo() {
        return path();
    }

    /** Per-request mutable attribute (for Servlet/JAX-RS state sharing). */
    default Object attribute(String key) {
        return null;
    }

    /** Sets a per-request attribute. Returns this for chaining. */
    default Request attribute(String key, Object value) {
        return this;
    }

    /**
     * Registers a callback invoked if the client connection is detected as
     * closed (FIN or RST) while this request is still being handled — i.e.
     * before the handler has returned its {@link Response}. Intended for
     * long-suspended responses (JAX-RS {@code @Suspended AsyncResponse}):
     * the callback lets the application stop working for a client that is
     * gone instead of waiting for its own timeout.
     *
     * <p>Detection is best-effort, via a non-destructive read probe on the
     * connection's parse buffer (pipelined bytes are preserved). The probe is
     * disarmed as soon as the handler returns; once the response is being
     * written, a disconnect surfaces as a write failure instead.
     *
     * @return {@code true} if the probe was armed; {@code false} when the
     *         transport cannot probe — current limitations: TLS and HTTP/2
     *         connections, and requests whose body has not been fully
     *         consumed (the probe shares the parse buffer).
     */
    default boolean onDisconnect(Runnable callback) {
        return false;
    }

    /** Remote (client) socket address. */
    default java.net.InetSocketAddress remoteAddress() {
        return null;
    }

    /** Local (server) socket address. */
    default java.net.InetSocketAddress localAddress() {
        return null;
    }

    /** True if the connection is TLS-secured. */
    default boolean isSecure() {
        return false;
    }

    /** URI scheme: "http" or "https". */
    default String scheme() {
        return isSecure() ? "https" : "http";
    }
}
