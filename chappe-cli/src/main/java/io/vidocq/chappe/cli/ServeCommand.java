package io.vidocq.chappe.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vidocq.chappe.api.Filter;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

/**
 * Implementation of the {@code chappe serve} subcommand:
 * merges CLI flags with the YAML file, builds a {@link StaticFileHandler},
 * and starts a {@link Server}.
 */
public final class ServeCommand {

    private ServeCommand() {}

    /** Environment variable used to activate the {@code headers.staging} section. */
    public static final String STAGING_ENV = "STAGING";
    /** Expected value of {@link #STAGING_ENV} to activate the staging block. */
    public static final String STAGING_VALUE = "true";
    /** Environment variable that enables the access log when equal to {@code "true"}. */
    public static final String ACCESS_LOG_ENV = "CHAPPE_ACCESS_LOG";

    /**
     * Effective parameter resolution: the CLI value takes precedence over the YAML value.
     * All unspecified fields are {@code null} (except collections, which are empty).
     */
    public record Effective(
            Path root,
            int port,
            String host,
            String fallback,
            String spaFallback,
            String cacheControl,
            boolean gzip,
            boolean accessLog,
            String indexFile,
            Map<String, String> alwaysHeaders,
            Map<String, String> stagingHeaders) {

        public static Effective resolve(CliArgs args, ChappeConfig yaml) {
            Path root = args.root() != null
                    ? args.root()
                    : (yaml.staticCfg().root() != null
                            ? Path.of(yaml.staticCfg().root())
                            : null);
            int port = args.port() != null
                    ? args.port()
                    : (yaml.server().port() != null ? yaml.server().port() : 8080);
            String host = args.bind() != null
                    ? args.bind()
                    : (yaml.server().bind() != null ? yaml.server().bind() : "0.0.0.0");
            String fallback =
                    args.fallback() != null ? args.fallback() : yaml.staticCfg().fallback();
            String spaFallback = args.spaFallback() != null
                    ? args.spaFallback()
                    : yaml.staticCfg().spaFallback();
            String cache = args.cacheControl() != null
                    ? args.cacheControl()
                    : yaml.staticCfg().cacheControl();
            boolean gzip = args.gzip() != null
                    ? args.gzip()
                    : (yaml.staticCfg().gzip() != null && yaml.staticCfg().gzip());
            // CLI > env > YAML. Env "true" active.
            boolean accessLog;
            if (args.accessLog() != null) {
                accessLog = args.accessLog();
            } else if ("true".equals(System.getenv(ACCESS_LOG_ENV))) {
                accessLog = true;
            } else {
                accessLog = yaml.logging().accessLog() != null && yaml.logging().accessLog();
            }
            List<String> indexFiles = yaml.staticCfg().indexFiles();
            String indexFile = (indexFiles != null && !indexFiles.isEmpty()) ? indexFiles.getFirst() : null;

            LinkedHashMap<String, String> always =
                    new LinkedHashMap<>(yaml.headers().always());
            always.putAll(args.extraHeaders()); // CLI override / extension
            Map<String, String> staging = yaml.headers().staging();

            return new Effective(
                    root,
                    port,
                    host,
                    fallback,
                    spaFallback,
                    cache,
                    gzip,
                    accessLog,
                    indexFile,
                    Map.copyOf(always),
                    Map.copyOf(staging));
        }
    }

    /** Builds the static handler + header filters, without starting the server. */
    public static Handler buildHandler(Effective cfg) {
        if (cfg.root() == null) {
            throw new IllegalArgumentException("static.root (or --root) is required to serve files");
        }
        StaticFileHandler.Builder b = StaticFileHandler.builder().addPath(cfg.root());
        if (cfg.cacheControl() != null) b.cacheControl(cfg.cacheControl());
        if (cfg.indexFile() != null) b.indexFile(cfg.indexFile());
        if (cfg.spaFallback() != null) b.spaFallback(cfg.spaFallback());
        else if (cfg.fallback() != null) b.notFoundFile(cfg.fallback());
        if (cfg.gzip()) b.preferPrecompressed(true);
        Handler h = b.build();
        if (cfg.gzip()) h = Filter.gzip().apply(h);

        // Apply staging filters first so the always-on layer wraps them last (outermost).
        for (var e : cfg.stagingHeaders().entrySet()) {
            h = Filter.addHeaderIfEnv(STAGING_ENV, STAGING_VALUE, e.getKey(), e.getValue())
                    .apply(h);
        }
        for (var e : cfg.alwaysHeaders().entrySet()) {
            h = Filter.addHeader(e.getKey(), e.getValue()).apply(h);
        }

        // Access log: outermost layer — measures total duration and captures final status.
        if (cfg.accessLog()) {
            h = Filter.accessLog().apply(h);
        }
        return h;
    }

    /** Starts an already configured server. The caller must manage the lifecycle. */
    public static Server start(Effective cfg) {
        Handler handler = buildHandler(cfg);
        Server server = Server.builder()
                .port(cfg.port())
                .host(cfg.host())
                .handler(handler)
                .build();
        server.start();
        return server;
    }

    /** Loads config (YAML + CLI), starts the server, and blocks until a signal arrives. */
    public static void run(CliArgs args) throws IOException, InterruptedException {
        ChappeConfig yaml = args.configPath() != null ? ConfigLoader.load(args.configPath()) : ChappeConfig.EMPTY;
        Effective cfg = Effective.resolve(args, yaml);
        Server server = start(cfg);
        System.out.println(io.vidocq.chappe.api.BuildInfo.serverHeader()
                + " listening on " + server.localAddress() + " (root=" + cfg.root()
                + ", access-log=" + cfg.accessLog() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "chappe-shutdown"));
        Thread.currentThread().join();
    }
}
