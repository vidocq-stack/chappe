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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CliArgsTest {

    @Test
    void noArgsImpliesHelp() {
        assertTrue(CliArgs.parse(new String[0]).help());
    }

    @Test
    void helpFlagSetsHelp() {
        assertTrue(CliArgs.parse(new String[] {"--help"}).help());
        assertTrue(CliArgs.parse(new String[] {"-h"}).help());
        assertTrue(CliArgs.parse(new String[] {"help"}).help());
    }

    @Test
    void unknownCommandRejected() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"oops"}));
    }

    @Test
    void serveWithAllFlags() {
        CliArgs a = CliArgs.parse(new String[] {
            "serve",
            "--config",
            "/etc/chappe/config.yml",
            "--root",
            "/var/www",
            "--port",
            "9090",
            "--bind",
            "127.0.0.1",
            "--fallback",
            "/404.html",
            "--cache-control",
            "max-age=60",
            "--gzip",
            "--header",
            "X-Foo=bar",
            "--header",
            "X-Baz=qux"
        });
        assertEquals(Path.of("/etc/chappe/config.yml"), a.configPath());
        assertEquals(Path.of("/var/www"), a.root());
        assertEquals(9090, a.port());
        assertEquals("127.0.0.1", a.bind());
        assertEquals("/404.html", a.fallback());
        assertEquals("max-age=60", a.cacheControl());
        assertEquals(Boolean.TRUE, a.gzip());
        assertEquals("bar", a.extraHeaders().get("X-Foo"));
        assertEquals("qux", a.extraHeaders().get("X-Baz"));
    }

    @Test
    void noGzipDisables() {
        assertEquals(
                Boolean.FALSE,
                CliArgs.parse(new String[] {"serve", "--no-gzip"}).gzip());
    }

    @Test
    void spaFallbackFlag() {
        assertEquals(
                "/index.html",
                CliArgs.parse(new String[] {"serve", "--spa-fallback", "/index.html"})
                        .spaFallback());
    }

    @Test
    void missingValueRaises() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"serve", "--port"}));
    }

    @Test
    void unknownFlagRaises() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"serve", "--what"}));
    }

    @Test
    void headerWithoutEqualsRaises() {
        assertThrows(
                IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"serve", "--header", "noEquals"}));
    }

    @Test
    void minimalServeProducesEmptyOptionals() {
        CliArgs a = CliArgs.parse(new String[] {"serve"});
        assertNull(a.port());
        assertNull(a.root());
        assertTrue(a.extraHeaders().isEmpty());
    }
}
