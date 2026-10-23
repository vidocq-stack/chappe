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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import io.vidocq.chappe.cli.yaml.YamlReader;

import org.junit.jupiter.api.Test;

/** Characterization of {@link ServeCommand.Effective#resolve}: defaults, YAML values and CLI precedence. */
class EffectiveResolveTest {

    private static final String FULL_YAML = """
            server:
              port: 9000
              bind: 127.0.0.1
              expose-build-info: true
            static:
              root: /srv/site
              fallback: /404.html
              spa-fallback: /index.html
              index-files: [home.html, index.html]
              cache-control: "max-age=60"
              gzip: true
            headers:
              always:
                X-A: yaml
                X-B: yaml
              staging:
                X-Robots-Tag: noindex
            logging:
              access-log: true
            """;

    private static boolean accessLogFromEnvironment() {
        return "true".equals(System.getenv(ServeCommand.ACCESS_LOG_ENV));
    }

    @Test
    void defaultsWhenNothingIsSet() {
        var eff = ServeCommand.Effective.resolve(CliArgs.parse(new String[] {"serve"}), ChappeConfig.EMPTY);
        assertNull(eff.root());
        assertEquals(8080, eff.port());
        assertEquals("0.0.0.0", eff.host());
        assertNull(eff.fallback());
        assertNull(eff.spaFallback());
        assertNull(eff.cacheControl());
        assertFalse(eff.gzip());
        assertEquals(accessLogFromEnvironment(), eff.accessLog());
        assertNull(eff.indexFile());
        assertEquals(Map.of(), eff.alwaysHeaders());
        assertEquals(Map.of(), eff.stagingHeaders());
        assertFalse(eff.exposeBuildInfo());
    }

    @Test
    void yamlValuesApplyWhenNoFlagIsGiven() {
        var yaml = ChappeConfig.from(YamlReader.parse(FULL_YAML));
        var eff = ServeCommand.Effective.resolve(CliArgs.parse(new String[] {"serve"}), yaml);
        assertEquals(Path.of("/srv/site"), eff.root());
        assertEquals(9000, eff.port());
        assertEquals("127.0.0.1", eff.host());
        assertEquals("/404.html", eff.fallback());
        assertEquals("/index.html", eff.spaFallback());
        assertEquals("max-age=60", eff.cacheControl());
        assertTrue(eff.gzip());
        assertTrue(eff.accessLog());
        assertEquals("home.html", eff.indexFile());
        assertEquals(Map.of("X-A", "yaml", "X-B", "yaml"), eff.alwaysHeaders());
        assertEquals(Map.of("X-Robots-Tag", "noindex"), eff.stagingHeaders());
        assertTrue(eff.exposeBuildInfo());
    }

    @Test
    void flagsTakePrecedenceOverYaml() {
        var yaml = ChappeConfig.from(YamlReader.parse(FULL_YAML));
        var args = CliArgs.parse(new String[] {
            "serve",
            "--root",
            "/cli",
            "--port",
            "1",
            "--bind",
            "::1",
            "--fallback",
            "/missing.html",
            "--spa-fallback",
            "/app.html",
            "--cache-control",
            "no-store",
            "--no-gzip",
            "--no-access-log",
            "--no-expose-build-info",
            "--header",
            "X-B=cli",
            "--header",
            "X-C=cli"
        });
        var eff = ServeCommand.Effective.resolve(args, yaml);
        assertEquals(Path.of("/cli"), eff.root());
        assertEquals(1, eff.port());
        assertEquals("::1", eff.host());
        assertEquals("/missing.html", eff.fallback());
        assertEquals("/app.html", eff.spaFallback());
        assertEquals("no-store", eff.cacheControl());
        assertFalse(eff.gzip());
        assertFalse(eff.accessLog());
        assertFalse(eff.exposeBuildInfo());
        assertEquals(Map.of("X-A", "yaml", "X-B", "cli", "X-C", "cli"), eff.alwaysHeaders());
    }

    @Test
    void yamlFalseValuesAndEmptyIndexList() {
        var yaml = ChappeConfig.from(YamlReader.parse("""
                server:
                  expose-build-info: false
                static:
                  gzip: off
                  index-files: []
                logging:
                  access-log: false
                """));
        var eff = ServeCommand.Effective.resolve(CliArgs.parse(new String[] {"serve"}), yaml);
        assertFalse(eff.gzip());
        assertEquals(accessLogFromEnvironment(), eff.accessLog());
        assertNull(eff.indexFile());
        assertFalse(eff.exposeBuildInfo());
    }
}
