package io.vidocq.chappe.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares Router dispatch:
 *   - Old: linear scan over all routes, with {@code String.split("/")}
 *     for each match attempt.
 *   - New: {@code HashMap<path, Route>} fast path for static routes,
 *     linear scan only for parameterized patterns.
 *
 * <p>Scenario: 20 static routes + 3 parameterized patterns. A request is dispatched
 * whose target path is either the first route, the last static route, or a miss.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class RouterDispatchBench {

    record Route(String pattern, Object handler) {}

    private List<Route> allRoutes;
    private Map<String, Route> staticIndex;
    private List<Route> dynamicRoutes;

    @Param({
        "/users", // first route — scan finds it quickly
        "/internal/status", // last static route — scan traverses all others
        "/api/v1/404"
    }) // miss — scan traverses everything
    public String requestPath;

    @Setup
    public void setup() {
        String[] staticPatterns = {
            "/users", "/users/me", "/users/profile", "/posts", "/posts/trending",
            "/comments", "/login", "/logout", "/register", "/health",
            "/metrics", "/api/v1/ping", "/api/v1/echo", "/api/v1/time", "/api/v2/version",
            "/admin", "/admin/users", "/admin/settings", "/config", "/internal/status"
        };
        String[] dynamicPatterns = {"/users/{id}", "/posts/{slug}", "/api/v1/items/{id}"};

        allRoutes = new ArrayList<>();
        staticIndex = new HashMap<>();
        dynamicRoutes = new ArrayList<>();

        for (String p : staticPatterns) {
            Route r = new Route(p, new Object());
            allRoutes.add(r);
            staticIndex.put(p, r);
        }
        for (String p : dynamicPatterns) {
            Route r = new Route(p, new Object());
            allRoutes.add(r);
            dynamicRoutes.add(r);
        }
    }

    // -- Old: full linear scan over allRoutes --
    @Benchmark
    public void old_linearScan(Blackhole bh) {
        for (Route r : allRoutes) {
            if (matchPath(r.pattern(), requestPath) != null) {
                bh.consume(r);
                return;
            }
        }
        bh.consume(0);
    }

    // -- New: fast-path map + dynamic scan only --
    @Benchmark
    public void current_fastPath(Blackhole bh) {
        Route r = staticIndex.get(requestPath);
        if (r != null) {
            bh.consume(r);
            return;
        }
        for (Route dr : dynamicRoutes) {
            if (matchPath(dr.pattern(), requestPath) != null) {
                bh.consume(dr);
                return;
            }
        }
        bh.consume(0);
    }

    // Minimal copy of DefaultRouterBuilder.matchPath — functionally equivalent.
    private static Map<String, String> matchPath(String pattern, String path) {
        String np =
                (pattern.length() > 1 && pattern.endsWith("/")) ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (np.equals(path)) return Collections.emptyMap();
        if (np.endsWith("/*")) {
            var base = np.substring(0, np.length() - 2);
            if (path.equals(base) || path.startsWith(base + "/")) return Collections.emptyMap();
            return null;
        }
        var pp = np.split("/", -1);
        var xp = path.split("/", -1);
        if (pp.length != xp.length) return null;
        Map<String, String> params = null;
        for (int i = 0; i < pp.length; i++) {
            var s = pp[i];
            if (s.startsWith("{") && s.endsWith("}")) {
                if (params == null) params = new LinkedHashMap<>();
                params.put(s.substring(1, s.length() - 1), xp[i]);
            } else if (!s.equals(xp[i])) return null;
        }
        return params != null ? params : Collections.emptyMap();
    }
}
