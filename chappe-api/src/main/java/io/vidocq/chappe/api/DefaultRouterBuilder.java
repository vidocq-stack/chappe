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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Default implementation of {@link Router.Builder}.
 */
final class DefaultRouterBuilder implements Router.Builder {

    private record Route(HttpMethod method, String pattern, Handler handler, List<Filter> filters) {}

    private record Mount(String prefix, Handler handler, List<Filter> filters, boolean stripPrefix) {}

    private final List<Route> routes = new ArrayList<>();
    private final List<Mount> mounts = new ArrayList<>();
    private final List<Filter> filters = new ArrayList<>();
    private final String prefix;
    private Handler notFoundHandler = _ -> Response.of(StatusCode.NOT_FOUND);

    DefaultRouterBuilder() {
        this("");
    }

    private DefaultRouterBuilder(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Router.Builder get(String pattern, Handler handler) {
        return route(HttpMethod.GET, pattern, handler);
    }

    @Override
    public Router.Builder head(String pattern, Handler handler) {
        return route(HttpMethod.HEAD, pattern, handler);
    }

    @Override
    public Router.Builder post(String pattern, Handler handler) {
        return route(HttpMethod.POST, pattern, handler);
    }

    @Override
    public Router.Builder put(String pattern, Handler handler) {
        return route(HttpMethod.PUT, pattern, handler);
    }

    @Override
    public Router.Builder delete(String pattern, Handler handler) {
        return route(HttpMethod.DELETE, pattern, handler);
    }

    @Override
    public Router.Builder options(String pattern, Handler handler) {
        return route(HttpMethod.OPTIONS, pattern, handler);
    }

    @Override
    public Router.Builder patch(String pattern, Handler handler) {
        return route(HttpMethod.PATCH, pattern, handler);
    }

    @Override
    public Router.Builder route(HttpMethod method, String pattern, Handler handler) {
        routes.add(new Route(method, prefix + pattern, handler, List.copyOf(filters)));
        return this;
    }

    @Override
    public Router.Builder group(String groupPrefix, Consumer<Router.Builder> configurator) {
        var child = new DefaultRouterBuilder(prefix + groupPrefix);
        // Parent filters are inherited by the child group
        child.filters.addAll(this.filters);
        configurator.accept(child);
        // Child routes already include their filters (parent + child)
        routes.addAll(child.routes);
        mounts.addAll(child.mounts);
        return this;
    }

    @Override
    public Router.Builder mount(String mountPrefix, Handler handler) {
        return mount(mountPrefix, handler, true);
    }

    @Override
    public Router.Builder mount(String mountPrefix, Handler handler, boolean stripPrefix) {
        mounts.add(new Mount(prefix + mountPrefix, handler, List.copyOf(filters), stripPrefix));
        return this;
    }

    @Override
    public Router.Builder filter(Filter filter) {
        filters.add(filter);
        return this;
    }

    @Override
    public Router.Builder webSocket(String pattern, WebSocketHandler handler) {
        return route(HttpMethod.GET, pattern, request -> {
            // RFC 6455 §4.2.1: validate server-side handshake headers.
            if (request.version() != HttpVersion.HTTP_1_1) {
                return Response.builder()
                        .status(StatusCode.BAD_REQUEST)
                        .body("WebSocket requires HTTP/1.1")
                        .build();
            }
            var headers = request.headers();
            if (!hasTokenIgnoreCase(headers.firstOrNull("Connection"), "upgrade")
                    || !"websocket".equalsIgnoreCase(headers.firstOrNull("Upgrade"))) {
                return Response.builder()
                        .status(StatusCode.BAD_REQUEST)
                        .body("Missing Upgrade: websocket / Connection: Upgrade")
                        .build();
            }
            if (!"13".equals(headers.firstOrNull("Sec-WebSocket-Version"))) {
                return Response.builder()
                        .status(StatusCode.of(426, "Upgrade Required"))
                        .header("Sec-WebSocket-Version", "13")
                        .build();
            }
            var key = headers.firstOrNull("Sec-WebSocket-Key");
            if (key == null || key.isBlank()) {
                return Response.builder()
                        .status(StatusCode.BAD_REQUEST)
                        .body("Missing Sec-WebSocket-Key")
                        .build();
            }
            // Carry the matched request (with route pathParams) so onOpen sees {param} captures.
            return new WebSocketUpgrade(handler, null, request);
        });
    }

    private static boolean hasTokenIgnoreCase(String headerValue, String token) {
        if (headerValue == null) return false;
        int start = 0;
        int len = headerValue.length();
        while (start < len) {
            int comma = headerValue.indexOf(',', start);
            if (comma < 0) comma = len;
            int s = start;
            int e = comma;
            while (s < e && Character.isWhitespace(headerValue.charAt(s))) s++;
            while (e > s && Character.isWhitespace(headerValue.charAt(e - 1))) e--;
            if (e - s == token.length() && headerValue.regionMatches(true, s, token, 0, token.length())) {
                return true;
            }
            start = comma + 1;
        }
        return false;
    }

    @Override
    public Router.Builder grpc(String pattern, GrpcHandler handler) {
        return route(HttpMethod.POST, pattern, request -> {
            // gRPC requires HTTP/2.
            if (request.version() != HttpVersion.HTTP_2) {
                return Response.of(StatusCode.HTTP_VERSION_NOT_SUPPORTED);
            }
            var ct = request.headers().firstOrNull("content-type");
            if (ct == null || !isGrpcContentType(ct) || isGrpcWebContentType(ct)) {
                return Response.of(StatusCode.UNSUPPORTED_MEDIA_TYPE);
            }
            return new GrpcDispatch(handler);
        });
    }

    @Override
    public Router.Builder grpcWeb(String pattern, GrpcHandler handler) {
        return route(HttpMethod.POST, pattern, request -> {
            var ct = request.headers().firstOrNull("content-type");
            if (ct == null) {
                return Response.of(StatusCode.UNSUPPORTED_MEDIA_TYPE);
            }
            String lower = ct.toLowerCase();
            GrpcWebDispatch.Mode mode;
            if (lower.startsWith("application/grpc-web-text")) {
                mode = GrpcWebDispatch.Mode.TEXT;
            } else if (lower.startsWith("application/grpc-web")) {
                mode = GrpcWebDispatch.Mode.BINARY;
            } else {
                return Response.of(StatusCode.UNSUPPORTED_MEDIA_TYPE);
            }

            // In HTTP/2: GrpcWebDispatch marker -> streaming dispatch via Http2Connection
            // (frames emitted progressively through H2 DATA frames).
            if (request.version() == HttpVersion.HTTP_2) {
                return new GrpcWebDispatch(handler, mode);
            }

            // In HTTP/1.1: standard Response with Body.ofOutputStream callback.
            // Handler runs in callback, frames are written incrementally using chunked
            // transfer encoding. gRPC-Web over H1 is the typical browser use case
            // typique (XHR/fetch POST, response chunked).
            String responseContentType =
                    (mode == GrpcWebDispatch.Mode.TEXT) ? "application/grpc-web-text" : "application/grpc-web";
            return Response.builder()
                    .status(StatusCode.OK)
                    .header("content-type", responseContentType)
                    .header("grpc-accept-encoding", GrpcWebBufferedCall.acceptEncodingHeader())
                    .body(Body.ofOutputStream(out -> runGrpcWebBuffered(request, out, mode, handler)))
                    .build();
        });
    }

    private static void runGrpcWebBuffered(
            Request request, java.io.OutputStream out, GrpcWebDispatch.Mode mode, GrpcHandler handler) {
        GrpcWebBufferedCall call;
        try {
            call = new GrpcWebBufferedCall(request, out, mode);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        try {
            handler.handle(call);
            if (!call.isCompleted()) {
                call.complete(GrpcStatus.OK, "");
            }
        } catch (Exception e) {
            if (!call.isCompleted()) {
                try {
                    call.complete(GrpcStatus.INTERNAL, String.valueOf(e.getMessage()));
                } catch (java.io.IOException _) {
                    // lost connection
                }
            }
        }
    }

    private static boolean isGrpcContentType(String ct) {
        return ct.regionMatches(true, 0, "application/grpc", 0, "application/grpc".length());
    }

    private static boolean isGrpcWebContentType(String ct) {
        return ct.regionMatches(true, 0, "application/grpc-web", 0, "application/grpc-web".length());
    }

    @Override
    public Router.Builder notFound(Handler handler) {
        this.notFoundHandler = handler;
        return this;
    }

    @Override
    public Router build() {
        var snapshot = List.copyOf(routes);
        var mountSnapshot = List.copyOf(mounts);
        var globalFilters = List.copyOf(filters);
        var fallback = notFoundHandler;

        // Fast path: static routes (without {param} or /*) indexed by (path -> method -> route).
        // O(1) for common case. Parameterized patterns go into dynamicRoutes.
        var staticByPath = new HashMap<String, EnumMap<HttpMethod, Route>>();
        var dynamicRoutes = new ArrayList<Route>();
        for (var r : snapshot) {
            String normPattern = normalize(r.pattern());
            if (isStaticPattern(normPattern)) {
                staticByPath
                        .computeIfAbsent(normPattern, _ -> new EnumMap<>(HttpMethod.class))
                        .putIfAbsent(r.method(), r); // first match wins, consistent with linear scan
            } else {
                dynamicRoutes.add(r);
            }
        }
        Map<String, EnumMap<HttpMethod, Route>> staticIndex = Map.copyOf(staticByPath);
        List<Route> dynamicSnapshot = List.copyOf(dynamicRoutes);

        return request -> {
            // Trailing-slash normalization: /users/ -> /users (except /)
            String path = request.path();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            HttpMethod method = request.method();
            // Auto HEAD for GET routes (RFC 9110 §9.3.2)
            boolean tryHeadAsGet = (method == HttpMethod.HEAD);

            // -- Fast path: static routes --
            var methodsForPath = staticIndex.get(path);
            if (methodsForPath != null) {
                Route route = methodsForPath.get(method);
                if (route == null && tryHeadAsGet) {
                    route = methodsForPath.get(HttpMethod.GET);
                }
                if (route != null) {
                    return invoke(route, request, Collections.emptyMap(), globalFilters);
                }
                // Path matched but method did not -> 405; continue to aggregate with dynamic routes
            }

            // -- Dynamic scan: parameterized patterns --
            boolean pathMatched = (methodsForPath != null);
            Set<HttpMethod> allowedMethods = null;
            if (pathMatched) {
                allowedMethods = EnumSet.copyOf(methodsForPath.keySet());
            }

            for (var route : dynamicSnapshot) {
                var params = matchPath(route.pattern(), path);
                if (params != null) {
                    if (!pathMatched) {
                        pathMatched = true;
                        allowedMethods = EnumSet.noneOf(HttpMethod.class);
                    }
                    allowedMethods.add(route.method());

                    boolean methodMatch =
                            (route.method() == method) || (tryHeadAsGet && route.method() == HttpMethod.GET);

                    if (methodMatch) {
                        return invoke(route, request, params, globalFilters);
                    }
                }
            }

            // 405 Method Not Allowed if path matches but method does not (RFC 9110 §15.5.6)
            if (pathMatched) {
                if (tryHeadAsGet) allowedMethods.add(HttpMethod.HEAD);
                var allow = String.join(
                        ", ", allowedMethods.stream().map(Enum::name).toList());
                return Response.builder()
                        .status(StatusCode.METHOD_NOT_ALLOWED)
                        .header("Allow", allow)
                        .build();
            }

            // Check mounts (after routes, before notFound)
            for (var mount : mountSnapshot) {
                if (path.equals(mount.prefix()) || path.startsWith(mount.prefix() + "/")) {
                    Handler h = mount.handler();
                    var mf = mount.filters();
                    for (int i = mf.size() - 1; i >= 0; i--) {
                        h = mf.get(i).apply(h);
                    }
                    // stripPrefix=true: handler sees the path relative to the mount (context stripping).
                    // stripPrefix=false: routing-only — handler sees the full path (pathInfo == path),
                    // so resources with absolute @Path match without the prefix doubling.
                    return h.handle(mount.stripPrefix() ? withMount(request, mount.prefix(), path) : request);
                }
            }

            return fallback.handle(request);
        };
    }

    private static Response invoke(Route route, Request request, Map<String, String> params, List<Filter> globalFilters)
            throws Exception {
        var routedRequest = params.isEmpty() ? request : withPathParams(request, params);
        Handler h = route.handler();
        var routeFilters = route.filters();
        for (int i = routeFilters.size() - 1; i >= 0; i--) {
            h = routeFilters.get(i).apply(h);
        }
        for (int i = globalFilters.size() - 1; i >= 0; i--) {
            if (!routeFilters.contains(globalFilters.get(i))) {
                h = globalFilters.get(i).apply(h);
            }
        }
        return h.handle(routedRequest);
    }

    private static String normalize(String pattern) {
        return (pattern.length() > 1 && pattern.endsWith("/")) ? pattern.substring(0, pattern.length() - 1) : pattern;
    }

    private static boolean isStaticPattern(String pattern) {
        return pattern.indexOf('{') < 0 && !pattern.endsWith("/*");
    }

    /**
     * Matches a pattern against a path. Returns captured params, or null if no match.
     */
    private static Map<String, String> matchPath(String pattern, String path) {
        // Also normalize trailing slash on pattern
        String normPattern =
                (pattern.length() > 1 && pattern.endsWith("/")) ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (normPattern.equals(path)) return Collections.emptyMap();

        if (normPattern.endsWith("/*")) {
            var base = normPattern.substring(0, normPattern.length() - 2);
            if (path.equals(base) || path.startsWith(base + "/")) {
                return Collections.emptyMap();
            }
            return null;
        }

        var patternParts = normPattern.split("/", -1);
        var pathParts = path.split("/", -1);
        if (patternParts.length != pathParts.length) return null;

        Map<String, String> params = null;
        for (int i = 0; i < patternParts.length; i++) {
            var pp = patternParts[i];
            if (pp.startsWith("{") && pp.endsWith("}")) {
                if (params == null) params = new LinkedHashMap<>();
                params.put(pp.substring(1, pp.length() - 1), URLDecoder.decode(pathParts[i], StandardCharsets.UTF_8));
            } else if (!pp.equals(pathParts[i])) {
                return null;
            }
        }
        return params != null ? Collections.unmodifiableMap(params) : Collections.emptyMap();
    }

    /**
     * Creates a Request wrapper that adds pathParams. Forwards everything
     * else (see {@link ForwardingRequest} for why hand-rolled wrappers are
     * forbidden here).
     */
    private static Request withPathParams(Request delegate, Map<String, String> pathParams) {
        return new ForwardingRequest() {
            @Override
            public Request delegate() {
                return delegate;
            }

            @Override
            public Map<String, String> pathParams() {
                return pathParams;
            }
        };
    }

    private static Request withMount(Request delegate, String mountPrefix, String originalPath) {
        String stripped = originalPath.substring(mountPrefix.length());
        if (stripped.isEmpty()) stripped = "/";
        final String mountedPath = stripped;
        return new ForwardingRequest() {
            @Override
            public Request delegate() {
                return delegate;
            }

            @Override
            public String path() {
                return mountedPath;
            }

            @Override
            public String contextPath() {
                return mountPrefix;
            }

            @Override
            public String pathInfo() {
                return mountedPath;
            }
        };
    }
}
