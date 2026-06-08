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

/**
 * HTTP response — immutable once built.
 * <p>
 * Create via the static factories or the {@link Builder}.
 */
public interface Response {

    /** HTTP status code. */
    StatusCode status();

    /** Response headers. */
    Headers headers();

    /** Response body. */
    Body body();

    /**
     * HTTP trailers sent after the body (HTTP/2 §8.1, RFC 9113 §8.1, chunked HTTP/1.1 §7.1.2).
     * <p>
     * Empty by default. Called by the transport layer <b>after</b> {@link #body()}
     * has been fully consumed: a custom implementation can therefore compute the
     * trailers on the fly (e.g. {@code grpc-status} in gRPC streaming).
     * <p>
     * In HTTP/1.1, the application must explicitly announce the names via the
     * {@code Trailer:} header and use chunked encoding.
     */
    default Headers trailers() {
        return Headers.empty();
    }

    // --- Factories ---

    /** Pre-allocated 200 OK "ok" response (hot path benchmark). */
    Response OK_TEXT = Response.ok("ok");

    /** 200 OK with no body. */
    static Response ok() {
        return builder().status(StatusCode.OK).build();
    }

    /** 200 OK with a UTF-8 text body. */
    static Response ok(String text) {
        return builder()
                .status(StatusCode.OK)
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(text)
                .build();
    }

    /** 200 OK with a {@link Body} body. */
    static Response ok(Body body) {
        return builder().status(StatusCode.OK).body(body).build();
    }

    /** Response with the given status and no body. */
    static Response of(StatusCode status) {
        return builder().status(status).build();
    }

    /** Response with the given status and body. */
    static Response of(StatusCode status, Body body) {
        return builder().status(status).body(body).build();
    }

    /** Creates a new builder. */
    static Builder builder() {
        return new DefaultResponseBuilder();
    }

    /** Fluent builder for constructing a {@link Response}. */
    interface Builder {

        Builder status(StatusCode status);

        Builder header(String name, String value);

        Builder headers(Headers headers);

        /** Adds a trailer (HTTP/2 or chunked HTTP/1.1). */
        Builder trailer(String name, String value);

        /** Replaces all trailers. */
        Builder trailers(Headers trailers);

        Builder body(Body body);

        Builder body(String text);

        Builder body(byte[] bytes);

        Response build();
    }
}
