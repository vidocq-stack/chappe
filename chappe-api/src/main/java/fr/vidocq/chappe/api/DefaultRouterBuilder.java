package fr.vidocq.chappe.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implémentation par défaut de {@link Router.Builder}.
 */
final class DefaultRouterBuilder implements Router.Builder {

    private record Route(HttpMethod method, String pattern, Handler handler, List<Filter> filters) {}

    private final List<Route> routes = new ArrayList<>();
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
        return this;
    }

    @Override
    public Router.Builder filter(Filter filter) {
        filters.add(filter);
        return this;
    }

    @Override
    public Router.Builder notFound(Handler handler) {
        this.notFoundHandler = handler;
        return this;
    }

    @Override
    public Router build() {
        var snapshot = List.copyOf(routes);
        var globalFilters = List.copyOf(filters);
        var fallback = notFoundHandler;

        return request -> {
            for (var route : snapshot) {
                if (route.method() == request.method()) {
                    var params = matchPath(route.pattern(), request.path());
                    if (params != null) {
                        // Wrapper la request avec les path params
                        var routedRequest = params.isEmpty() ? request : withPathParams(request, params);
                        Handler h = route.handler();
                        // Appliquer les filtres de la route (inclut parent + enfant)
                        var routeFilters = route.filters();
                        for (int i = routeFilters.size() - 1; i >= 0; i--) {
                            h = routeFilters.get(i).apply(h);
                        }
                        // Appliquer les filtres globaux (ajoutés après les routes)
                        for (int i = globalFilters.size() - 1; i >= 0; i--) {
                            if (!routeFilters.contains(globalFilters.get(i))) {
                                h = globalFilters.get(i).apply(h);
                            }
                        }
                        return h.handle(routedRequest);
                    }
                }
            }
            return fallback.handle(request);
        };
    }

    /**
     * Matche un pattern contre un path. Retourne les params capturés, ou null si pas de match.
     */
    private static Map<String, String> matchPath(String pattern, String path) {
        if (pattern.equals(path)) return Collections.emptyMap();

        if (pattern.endsWith("/*")) {
            var base = pattern.substring(0, pattern.length() - 2);
            if (path.equals(base) || path.startsWith(base + "/")) {
                return Collections.emptyMap();
            }
            return null;
        }

        var patternParts = pattern.split("/", -1);
        var pathParts = path.split("/", -1);
        if (patternParts.length != pathParts.length) return null;

        Map<String, String> params = null;
        for (int i = 0; i < patternParts.length; i++) {
            var pp = patternParts[i];
            if (pp.startsWith("{") && pp.endsWith("}")) {
                if (params == null) params = new LinkedHashMap<>();
                params.put(pp.substring(1, pp.length() - 1), pathParts[i]);
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
            @Override public HttpMethod method() { return delegate.method(); }
            @Override public URI uri() { return delegate.uri(); }
            @Override public String path() { return delegate.path(); }
            @Override public String query() { return delegate.query(); }
            @Override public HttpVersion version() { return delegate.version(); }
            @Override public Headers headers() { return delegate.headers(); }
            @Override public Body body() { return delegate.body(); }
            @Override public Map<String, String> pathParams() { return pathParams; }
            @Override public Map<String, String> queryParams() { return delegate.queryParams(); }
        };
    }
}
