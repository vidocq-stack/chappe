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

import java.util.function.Consumer;

/**
 * HTTP router — maps path patterns to {@link Handler handlers}.
 * <p>
 * The router is itself a {@link Handler}: it can be used anywhere a handler
 * is expected (composition, nesting, wrapping by filters).
 *
 * <pre>{@code
 * var router = Router.builder()
 *     .get("/", _ -> Response.ok("Home"))
 *     .get("/users/{id}", req -> {
 *         var id = req.pathParams().get("id");
 *         return Response.ok("User " + id);
 *     })
 *     .group("/api", api -> api
 *         .filter(authFilter)
 *         .get("/health", _ -> Response.ok("UP"))
 *     )
 *     .build();
 * }</pre>
 *
 * <h2>Path patterns</h2>
 * <ul>
 *   <li>{@code /users} — literal</li>
 *   <li>{@code /users/{id}} — named parameter (captured in {@link Request#pathParams()})</li>
 *   <li>{@code /static/*} — wildcard (matches the rest of the path)</li>
 * </ul>
 */
public interface Router extends Handler {

    /** Creates a new router builder. */
    static Builder builder() {
        return new DefaultRouterBuilder();
    }

    /** Fluent builder for constructing a {@link Router}. */
    interface Builder {

        Builder get(String pattern, Handler handler);

        Builder head(String pattern, Handler handler);

        Builder post(String pattern, Handler handler);

        Builder put(String pattern, Handler handler);

        Builder delete(String pattern, Handler handler);

        Builder options(String pattern, Handler handler);

        Builder patch(String pattern, Handler handler);

        /** Registers a route for an arbitrary method. */
        Builder route(HttpMethod method, String pattern, Handler handler);

        /**
         * Route group with a common prefix.
         * Filters added within the group apply only to its routes.
         */
        Builder group(String prefix, Consumer<Builder> routes);

        /** Adds a filter to all routes of this builder. */
        Builder filter(Filter filter);

        /** Mounts a sub-handler at the given path prefix (all methods, path stripping). */
        Builder mount(String prefix, Handler handler);

        /**
         * Mounts a sub-handler at the given path prefix, controlling path stripping.
         * <p>
         * With {@code stripPrefix=true} (the behaviour of {@link #mount(String, Handler)}) the
         * prefix is removed before the handler runs: {@code Request.pathInfo()} is relative to the
         * mount (e.g. a {@code /api} mount sees {@code /rooms} for {@code /api/rooms}).
         * <p>
         * With {@code stripPrefix=false} the prefix is used for <em>routing only</em>: the handler
         * receives the <em>full</em> path ({@code pathInfo() == path()}). This suits handlers whose
         * resources carry absolute paths (e.g. a JAX-RS resource {@code @Path("/health")} mounted at
         * the {@code /health} prefix matches {@code GET /health}, {@code /health/live}, … without the
         * prefix doubling), while still leaving sibling prefixes (static {@code /}, {@code /api}) untouched.
         */
        Builder mount(String prefix, Handler handler, boolean stripPrefix);

        /**
         * Registers a WebSocket endpoint (RFC 6455).
         * <p>
         * On an HTTP/1.1 {@code GET} request with the correct handshake headers,
         * the connection is upgraded and {@code handler} receives the session events.
         * Otherwise a {@code 400 Bad Request} is returned.
         */
        Builder webSocket(String pattern, WebSocketHandler handler);

        /**
         * Registers a gRPC endpoint (HTTP/2 transport + core gRPC framing).
         * <p>
         * For a {@code POST} request on {@code pattern} over HTTP/2 with
         * {@code content-type: application/grpc[+xxx]}, the connection switches to
         * bidirectional streaming mode and {@code handler} receives a {@link GrpcCall}.
         * <p>
         * Rejection conditions:
         * <ul>
         *   <li>HTTP version &lt; 2 → {@code 505 HTTP Version Not Supported}</li>
         *   <li>{@code content-type} absent or ≠ {@code application/grpc...} → {@code 415}</li>
         * </ul>
         * Message serialisation (protobuf, json, …) is the handler's responsibility.
         */
        Builder grpc(String pattern, GrpcHandler handler);

        /**
         * Registers a <b>gRPC-Web</b> endpoint (PROTOCOL-WEB.md, browsers).
         * <p>
         * gRPC variant where trailers are serialised inline in the body as a special
         * DATA frame (prefix {@code 0x80}), because browsers do not expose HTTP/2
         * trailers to JavaScript. The client's content-type selects the mode:
         * <ul>
         *   <li>{@code application/grpc-web} → binary</li>
         *   <li>{@code application/grpc-web-text} → Base64 (each chunk independently)</li>
         * </ul>
         * Any other value → {@code 415}. Chappe v1 = HTTP/2 only ({@code 505} otherwise).
         * The same {@link GrpcHandler} as for {@link #grpc} is used —
         * the handler receives decoded bytes and is unaware of the variant.
         */
        Builder grpcWeb(String pattern, GrpcHandler handler);

        /** Handler for unmatched routes (404 by default). */
        Builder notFound(Handler handler);

        /** Builds the immutable router. */
        Router build();
    }
}
