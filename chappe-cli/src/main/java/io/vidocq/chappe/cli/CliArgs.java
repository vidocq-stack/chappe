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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parsed representation of the flags for the {@code chappe serve} subcommand.
 *
 * <p>Specified flags take precedence over values read from the
 * YAML file pointed to by {@code --config}.</p>
 */
public record CliArgs(
        Path configPath,
        Path root,
        Integer port,
        String bind,
        String fallback,
        String spaFallback,
        String cacheControl,
        Boolean gzip,
        Boolean accessLog,
        Boolean exposeBuildInfo,
        Map<String, String> extraHeaders,
        boolean help) {

    /** Sentinel indicating that help should be displayed. */
    public static final CliArgs HELP_REQUEST =
            new CliArgs(null, null, null, null, null, null, null, null, null, null, Map.of(), true);

    /** Flags of {@code chappe serve} that take their value from the next argument. */
    private static final Set<String> VALUE_FLAGS = Set.of(
            "--config", "--root", "--port", "--bind", "--fallback", "--spa-fallback", "--cache-control", "--header");

    private static final String UNKNOWN_FLAG = "unknown flag: ";

    private static final String HELP_TEXT = """
            chappe serve — sert un répertoire statique en HTTP/1.1+H2

            Usage:
              chappe serve [--config FILE] [flags...]

            Flags:
              --config FILE        Chemin du fichier YAML de configuration
              --root DIR           Répertoire racine (override static.root)
              --port N             Port d'écoute (défaut 8080)
              --bind ADDR          Adresse de bind (défaut 0.0.0.0)
              --fallback PATH      Fichier servi en 404 (override static.fallback)
              --spa-fallback PATH  Fichier servi en 200 si non trouvé (SPA mode)
              --cache-control STR  Header Cache-Control sur chaque ressource
              --gzip / --no-gzip   Active/désactive la compression à la volée
              --access-log         Active l'access log Apache CLF sur stdout
              --no-access-log      Désactive l'access log
              --header KEY=VALUE   Ajoute un header (répétable, merge dans headers.always)
              --expose-build-info  Puts Chappe's version and build in Server and X-Chappe-Build
                                   (off by default: Server: Chappe; --no-expose-build-info)
              -h, --help           Affiche ce message
            """;

    /** Parses arguments. {@code argv[0]} must be {@code "serve"} (otherwise {@code --help}). */
    public static CliArgs parse(String[] argv) {
        if (argv.length == 0 || isHelpCommand(argv[0])) return HELP_REQUEST;
        if (!argv[0].equals("serve")) {
            throw new IllegalArgumentException("unknown command: " + argv[0] + " (expected: serve)");
        }
        Parser parser = new Parser();
        int i = 1;
        while (i < argv.length) {
            i = parser.accept(argv, i);
        }
        return parser.build();
    }

    private static boolean isHelpCommand(String command) {
        return command.equals("--help") || command.equals("-h") || command.equals("help");
    }

    private static String requireValue(String[] argv, int idx, String flag) {
        if (idx >= argv.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return argv[idx];
    }

    /** Help text (multi-line). */
    public static String helpText() {
        return HELP_TEXT;
    }

    /** Flags read so far by one {@link #parse} call. */
    private static final class Parser {
        private Path configPath;
        private Path root;
        private Integer port;
        private String bind;
        private String fallback;
        private String spaFallback;
        private String cacheControl;
        private Boolean gzip;
        private Boolean accessLog;
        private Boolean exposeBuildInfo;
        private final Map<String, String> extraHeaders = new LinkedHashMap<>();
        private boolean help;

        /** Applies the flag at {@code argv[i]} (and its value) and returns the index of the next flag. */
        int accept(String[] argv, int i) {
            String flag = argv[i];
            if (applySwitch(flag)) return i + 1;
            if (!VALUE_FLAGS.contains(flag)) {
                throw new IllegalArgumentException(UNKNOWN_FLAG + flag);
            }
            applyValue(flag, requireValue(argv, i + 1, flag));
            return i + 2;
        }

        /** Applies a flag that takes no value; {@code false} when {@code flag} is not such a flag. */
        private boolean applySwitch(String flag) {
            switch (flag) {
                case "--help", "-h" -> help = true;
                case "--gzip" -> gzip = Boolean.TRUE;
                case "--no-gzip" -> gzip = Boolean.FALSE;
                case "--access-log" -> accessLog = Boolean.TRUE;
                case "--no-access-log" -> accessLog = Boolean.FALSE;
                case "--expose-build-info" -> exposeBuildInfo = Boolean.TRUE;
                case "--no-expose-build-info" -> exposeBuildInfo = Boolean.FALSE;
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** Applies one of the {@link #VALUE_FLAGS}. */
        private void applyValue(String flag, String value) {
            switch (flag) {
                case "--config" -> configPath = Path.of(value);
                case "--root" -> root = Path.of(value);
                case "--port" -> port = Integer.parseInt(value);
                case "--bind" -> bind = value;
                case "--fallback" -> fallback = value;
                case "--spa-fallback" -> spaFallback = value;
                case "--cache-control" -> cacheControl = value;
                case "--header" -> putHeader(value);
                default -> throw new IllegalArgumentException(UNKNOWN_FLAG + flag);
            }
        }

        private void putHeader(String keyValue) {
            int eq = keyValue.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--header expects KEY=VALUE, got: " + keyValue);
            }
            extraHeaders.put(keyValue.substring(0, eq).trim(), keyValue.substring(eq + 1));
        }

        CliArgs build() {
            return new CliArgs(
                    configPath,
                    root,
                    port,
                    bind,
                    fallback,
                    spaFallback,
                    cacheControl,
                    gzip,
                    accessLog,
                    exposeBuildInfo,
                    Map.copyOf(extraHeaders),
                    help);
        }
    }
}
