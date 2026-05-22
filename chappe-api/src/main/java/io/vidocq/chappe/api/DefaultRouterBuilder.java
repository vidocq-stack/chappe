package io.vidocq.chappe.api;

import java.net.URI;
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
 * Implémentation par défaut de {@link Router.Builder}.
 */
final class DefaultRouterBuilder implements Router.Builder {

    private record Route(HttpMethod method, String pattern, Handler handler, List<Filter> filters) {}

    private record Mount(String prefix, Handler handler, List<Filter> filters) {}

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
        // Les filtres du parent sont hérités par le groupe enfant
        child.filters.addAll(this.filters);
        configurator.accept(child);
        // Les routes enfant portent déjà leurs filtres (parent + enfant)
        routes.addAll(child.routes);
        mounts.addAll(child.mounts);
        return this;
    }

    @Override
    public Router.Builder mount(String mountPrefix, Handler handler) {
        mounts.add(new Mount(prefix + mountPrefix, handler, List.copyOf(filters)));
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
            // RFC 6455 §4.2.1 : valide les headers de handshake côté serveur.
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
            return new WebSocketUpgrade(handler);
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
            // gRPC exige HTTP/2.
            if (request.version() != HttpVersion.HTTP_2) {
                return Response.of(StatusCode.HTTP_VERSION_NOT_SUPPORTED);
            }
            var ct = request.headers().firstOrNull("content-type");
            if (ct == null || !ct.regionMatches(true, 0, "application/grpc", 0, "application/grpc".length())) {
                return Response.of(StatusCode.UNSUPPORTED_MEDIA_TYPE);
            }
            return new GrpcDispatch(handler);
        });
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

        // Fast path : routes statiques (sans {param} ni /*) indexées par (path → method → route).
        // O(1) pour le cas fréquent. Les patterns paramétriques vont dans dynamicRoutes.
        var staticByPath = new HashMap<String, EnumMap<HttpMethod, Route>>();
        var dynamicRoutes = new ArrayList<Route>();
        for (var r : snapshot) {
            String normPattern = normalize(r.pattern());
            if (isStaticPattern(normPattern)) {
                staticByPath
                        .computeIfAbsent(normPattern, _ -> new EnumMap<>(HttpMethod.class))
                        .putIfAbsent(r.method(), r); // premier gagnant, cohérent avec scan linéaire
            } else {
                dynamicRoutes.add(r);
            }
        }
        Map<String, EnumMap<HttpMethod, Route>> staticIndex = Map.copyOf(staticByPath);
        List<Route> dynamicSnapshot = List.copyOf(dynamicRoutes);

        return request -> {
            // Normalisation trailing slash : /users/ → /users (sauf /)
            String path = request.path();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            HttpMethod method = request.method();
            // Auto HEAD pour routes GET (RFC 9110 §9.3.2)
            boolean tryHeadAsGet = (method == HttpMethod.HEAD);

            // ── Fast path : routes statiques ──
            var methodsForPath = staticIndex.get(path);
            if (methodsForPath != null) {
                Route route = methodsForPath.get(method);
                if (route == null && tryHeadAsGet) {
                    route = methodsForPath.get(HttpMethod.GET);
                }
                if (route != null) {
                    return invoke(route, request, Collections.emptyMap(), globalFilters);
                }
                // Path matché mais pas la méthode → 405, on continue pour agréger avec dynamic
            }

            // ── Scan dynamique : patterns paramétriques ──
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

            // 405 Method Not Allowed si le path matche mais pas la méthode (RFC 9110 §15.5.6)
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
                    return h.handle(withMount(request, mount.prefix(), path));
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
     * Matche un pattern contre un path. Retourne les params capturés, ou null si pas de match.
     */
    private static Map<String, String> matchPath(String pattern, String path) {
        // Normalisation trailing slash sur le pattern aussi
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
     * Crée un wrapper de Request qui ajoute les pathParams.
     */
    private static Request withPathParams(Request delegate, Map<String, String> pathParams) {
        return new Request() {
            @Override
            public HttpMethod method() {
                return delegate.method();
            }

            @Override
            public URI uri() {
                return delegate.uri();
            }

            @Override
            public String path() {
                return delegate.path();
            }

            @Override
            public String contextPath() {
                return delegate.contextPath();
            }

            @Override
            public String pathInfo() {
                return delegate.pathInfo();
            }

            @Override
            public String query() {
                return delegate.query();
            }

            @Override
            public HttpVersion version() {
                return delegate.version();
            }

            @Override
            public Headers headers() {
                return delegate.headers();
            }

            @Override
            public Body body() {
                return delegate.body();
            }

            @Override
            public Map<String, String> pathParams() {
                return pathParams;
            }

            @Override
            public Map<String, String> queryParams() {
                return delegate.queryParams();
            }

            @Override
            public Object attribute(String key) {
                return delegate.attribute(key);
            }

            @Override
            public Request attribute(String key, Object value) {
                delegate.attribute(key, value);
                return this;
            }

            @Override
            public java.net.InetSocketAddress remoteAddress() {
                return delegate.remoteAddress();
            }

            @Override
            public java.net.InetSocketAddress localAddress() {
                return delegate.localAddress();
            }

            @Override
            public boolean isSecure() {
                return delegate.isSecure();
            }

            @Override
            public String scheme() {
                return delegate.scheme();
            }
        };
    }

    private static Request withMount(Request delegate, String mountPrefix, String originalPath) {
        String stripped = originalPath.substring(mountPrefix.length());
        if (stripped.isEmpty()) stripped = "/";
        final String mountedPath = stripped;
        return new Request() {
            @Override
            public HttpMethod method() {
                return delegate.method();
            }

            @Override
            public URI uri() {
                return delegate.uri();
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

            @Override
            public String query() {
                return delegate.query();
            }

            @Override
            public HttpVersion version() {
                return delegate.version();
            }

            @Override
            public Headers headers() {
                return delegate.headers();
            }

            @Override
            public Body body() {
                return delegate.body();
            }

            @Override
            public Map<String, String> pathParams() {
                return delegate.pathParams();
            }

            @Override
            public Map<String, String> queryParams() {
                return delegate.queryParams();
            }

            @Override
            public Object attribute(String key) {
                return delegate.attribute(key);
            }

            @Override
            public Request attribute(String key, Object value) {
                delegate.attribute(key, value);
                return this;
            }

            @Override
            public java.net.InetSocketAddress remoteAddress() {
                return delegate.remoteAddress();
            }

            @Override
            public java.net.InetSocketAddress localAddress() {
                return delegate.localAddress();
            }

            @Override
            public boolean isSecure() {
                return delegate.isSecure();
            }

            @Override
            public String scheme() {
                return delegate.scheme();
            }
        };
    }
}
