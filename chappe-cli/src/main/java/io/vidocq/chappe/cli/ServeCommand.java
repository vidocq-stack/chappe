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
            Map<String, String> stagingHeaders,
            boolean exposeBuildInfo) {

        /** Port used when neither {@code --port} nor {@code server.port} is set. */
        private static final int DEFAULT_PORT = 8080;
        /** Address used when neither {@code --bind} nor {@code server.bind} is set. */
        private static final String DEFAULT_HOST = "0.0.0.0";

        public static Effective resolve(CliArgs args, ChappeConfig yaml) {
            ChappeConfig.Static staticCfg = yaml.staticCfg();
            Path root = cliOrYaml(args.root(), toPath(staticCfg.root()));
            int port = cliOrYaml(args.port(), yaml.server().port(), DEFAULT_PORT);
            String host = cliOrYaml(args.bind(), yaml.server().bind(), DEFAULT_HOST);
            String fallback = cliOrYaml(args.fallback(), staticCfg.fallback());
            String spaFallback = cliOrYaml(args.spaFallback(), staticCfg.spaFallback());
            String cache = cliOrYaml(args.cacheControl(), staticCfg.cacheControl());
            boolean gzip = cliOrYaml(args.gzip(), staticCfg.gzip(), Boolean.FALSE);
            boolean accessLog =
                    resolveAccessLog(args.accessLog(), yaml.logging().accessLog());
            String indexFile = firstOrNull(staticCfg.indexFiles());

            LinkedHashMap<String, String> always =
                    new LinkedHashMap<>(yaml.headers().always());
            always.putAll(args.extraHeaders()); // CLI override / extension
            Map<String, String> staging = yaml.headers().staging();
            // CLI > YAML; off by default, so that the server does not tell which version to aim at.
            boolean exposeBuildInfo =
                    cliOrYaml(args.exposeBuildInfo(), yaml.server().exposeBuildInfo(), Boolean.FALSE);

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
                    Map.copyOf(staging),
                    exposeBuildInfo);
        }

        /** The CLI value when the flag was given, else the YAML value (possibly {@code null}). */
        private static <T> T cliOrYaml(T cli, T yaml) {
            return cli != null ? cli : yaml;
        }

        /** The CLI value when the flag was given, else the YAML value, else {@code defaultValue}. */
        private static <T> T cliOrYaml(T cli, T yaml, T defaultValue) {
            T value = cliOrYaml(cli, yaml);
            return value != null ? value : defaultValue;
        }

        /** CLI first, then {@link ServeCommand#ACCESS_LOG_ENV} when it equals {@code "true"}, then YAML; off by default. */
        private static boolean resolveAccessLog(Boolean cli, Boolean yaml) {
            if (cli != null) return cli;
            if ("true".equals(System.getenv(ACCESS_LOG_ENV))) return true;
            return Boolean.TRUE.equals(yaml);
        }

        private static Path toPath(String path) {
            return path != null ? Path.of(path) : null;
        }

        private static String firstOrNull(List<String> values) {
            return values != null && !values.isEmpty() ? values.getFirst() : null;
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
                .exposeBuildInfo(cfg.exposeBuildInfo())
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
