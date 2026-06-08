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
        Map<String, String> extraHeaders,
        boolean help) {

    /** Sentinel indicating that help should be displayed. */
    public static final CliArgs HELP =
            new CliArgs(null, null, null, null, null, null, null, null, null, Map.of(), true);

    /** Parses arguments. {@code argv[0]} must be {@code "serve"} (otherwise {@code --help}). */
    public static CliArgs parse(String[] argv) {
        if (argv.length == 0) return HELP;
        String first = argv[0];
        if (first.equals("--help") || first.equals("-h") || first.equals("help")) return HELP;
        if (!first.equals("serve")) {
            throw new IllegalArgumentException("unknown command: " + first + " (expected: serve)");
        }

        Path configPath = null;
        Path root = null;
        Integer port = null;
        String bind = null;
        String fallback = null;
        String spaFallback = null;
        String cacheControl = null;
        Boolean gzip = null;
        Boolean accessLog = null;
        LinkedHashMap<String, String> extraHeaders = new LinkedHashMap<>();
        boolean help = false;

        int i = 1;
        while (i < argv.length) {
            String a = argv[i];
            switch (a) {
                case "--help", "-h" -> {
                    help = true;
                    i++;
                }
                case "--config" -> {
                    configPath = Path.of(requireValue(argv, ++i, a));
                    i++;
                }
                case "--root" -> {
                    root = Path.of(requireValue(argv, ++i, a));
                    i++;
                }
                case "--port" -> {
                    port = Integer.parseInt(requireValue(argv, ++i, a));
                    i++;
                }
                case "--bind" -> {
                    bind = requireValue(argv, ++i, a);
                    i++;
                }
                case "--fallback" -> {
                    fallback = requireValue(argv, ++i, a);
                    i++;
                }
                case "--spa-fallback" -> {
                    spaFallback = requireValue(argv, ++i, a);
                    i++;
                }
                case "--cache-control" -> {
                    cacheControl = requireValue(argv, ++i, a);
                    i++;
                }
                case "--gzip" -> {
                    gzip = Boolean.TRUE;
                    i++;
                }
                case "--no-gzip" -> {
                    gzip = Boolean.FALSE;
                    i++;
                }
                case "--access-log" -> {
                    accessLog = Boolean.TRUE;
                    i++;
                }
                case "--no-access-log" -> {
                    accessLog = Boolean.FALSE;
                    i++;
                }
                case "--header" -> {
                    String kv = requireValue(argv, ++i, a);
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("--header expects KEY=VALUE, got: " + kv);
                    }
                    extraHeaders.put(kv.substring(0, eq).trim(), kv.substring(eq + 1));
                    i++;
                }
                default -> throw new IllegalArgumentException("unknown flag: " + a);
            }
        }

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
                Map.copyOf(extraHeaders),
                help);
    }

    private static String requireValue(String[] argv, int idx, String flag) {
        if (idx >= argv.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return argv[idx];
    }

    /** Help text (multi-line). */
    public static String helpText() {
        return """
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
                  -h, --help           Affiche ce message
                """;
    }
}
