package io.vidocq.chappe.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vidocq.chappe.cli.yaml.YamlNode;

/**
 * Configuration résolue de la CLI {@code chappe serve}, projection d'un
 * {@link YamlNode.Map} sur un graphe de records typés.
 *
 * <p>Tous les champs sont nullable / optionnels — c'est la responsabilité du
 * {@link ServeCommand} de combiner avec les flags CLI puis de valider.</p>
 */
public record ChappeConfig(Server server, Static staticCfg, Headers headers, Logging logging) {

    public record Server(Integer port, String bind) {
        public static final Server EMPTY = new Server(null, null);
    }

    public record Static(
            String root,
            String fallback,
            String spaFallback,
            List<String> indexFiles,
            String cacheControl,
            Boolean gzip) {
        public static final Static EMPTY = new Static(null, null, null, null, null, null);
    }

    public record Headers(Map<String, String> always, Map<String, String> staging) {
        public static final Headers EMPTY = new Headers(Map.of(), Map.of());
    }

    public record Logging(String level, Boolean accessLog) {
        public static final Logging EMPTY = new Logging(null, null);
    }

    public static final ChappeConfig EMPTY = new ChappeConfig(Server.EMPTY, Static.EMPTY, Headers.EMPTY, Logging.EMPTY);

    /** Projette un nœud YAML racine sur un {@code ChappeConfig}. */
    public static ChappeConfig from(YamlNode root) {
        if (!(root instanceof YamlNode.Map m)) return EMPTY;
        return new ChappeConfig(
                parseServer(m.map("server").orElse(null)),
                parseStatic(m.map("static").orElse(null)),
                parseHeaders(m.map("headers").orElse(null)),
                parseLogging(m.map("logging").orElse(null)));
    }

    private static Server parseServer(YamlNode.Map m) {
        if (m == null) return Server.EMPTY;
        return new Server(m.integer("port").orElse(null), m.string("bind").orElse(null));
    }

    private static Static parseStatic(YamlNode.Map m) {
        if (m == null) return Static.EMPTY;
        return new Static(
                m.string("root").orElse(null),
                m.string("fallback").orElse(null),
                m.string("spa-fallback").orElse(null),
                m.stringList("index-files").orElse(null),
                m.string("cache-control").orElse(null),
                m.bool("gzip").orElse(null));
    }

    private static Headers parseHeaders(YamlNode.Map m) {
        if (m == null) return Headers.EMPTY;
        return new Headers(
                flatStringMap(m.map("always").orElse(null)),
                flatStringMap(m.map("staging").orElse(null)));
    }

    private static Logging parseLogging(YamlNode.Map m) {
        if (m == null) return Logging.EMPTY;
        return new Logging(m.string("level").orElse(null), m.bool("access-log").orElse(null));
    }

    private static Map<String, String> flatStringMap(YamlNode.Map m) {
        if (m == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (var e : m.entries().entrySet()) {
            if (e.getValue() instanceof YamlNode.Scalar s) {
                out.put(e.getKey(), s.value());
            }
        }
        return Map.copyOf(out);
    }
}
