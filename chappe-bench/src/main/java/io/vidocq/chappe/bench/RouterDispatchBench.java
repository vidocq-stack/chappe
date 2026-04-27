package io.vidocq.chappe.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Compare le dispatch du Router :
 *   - Ancien : scan linéaire sur toutes les routes, avec {@code String.split("/")}
 *     par tentative de match.
 *   - Nouveau : fast-path {@code HashMap<path, Route>} pour les routes statiques,
 *     scan linéaire uniquement sur les patterns paramétriques.
 *
 * <p>Scénario : 20 routes statiques + 3 patterns paramétriques. On dispatche une requête
 * dont le path cible soit la première, soit la dernière route statique, soit un miss.
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

    @Param({"/users",           // première route — scan trouve vite
            "/internal/status", // dernière statique — scan parcourt toutes les autres
            "/api/v1/404"})     // miss — scan parcourt tout
    public String requestPath;

    @Setup
    public void setup() {
        String[] staticPatterns = {
                "/users", "/users/me", "/users/profile", "/posts", "/posts/trending",
                "/comments", "/login", "/logout", "/register", "/health",
                "/metrics", "/api/v1/ping", "/api/v1/echo", "/api/v1/time", "/api/v2/version",
                "/admin", "/admin/users", "/admin/settings", "/config", "/internal/status"
        };
        String[] dynamicPatterns = {
                "/users/{id}", "/posts/{slug}", "/api/v1/items/{id}"
        };

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

    // ── Ancien : scan linéaire complet sur allRoutes ──
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

    // ── Nouveau : fast-path map + scan dynamique seulement ──
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

    // Copie minimale de DefaultRouterBuilder.matchPath — équivalence fonctionnelle.
    private static Map<String, String> matchPath(String pattern, String path) {
        String np = (pattern.length() > 1 && pattern.endsWith("/"))
                ? pattern.substring(0, pattern.length() - 1) : pattern;
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
