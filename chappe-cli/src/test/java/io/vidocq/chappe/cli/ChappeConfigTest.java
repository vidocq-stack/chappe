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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.vidocq.chappe.cli.yaml.YamlReader;

import org.junit.jupiter.api.Test;

class ChappeConfigTest {

    @Test
    void emptyDocumentProducesEmptyConfig() {
        ChappeConfig c = ChappeConfig.from(YamlReader.parse(""));
        assertEquals(ChappeConfig.EMPTY, c);
    }

    @Test
    void parsesFullChappeConfigYml() {
        var c = ChappeConfig.from(YamlReader.parse("""
                server:
                  port: 8080
                  bind: 0.0.0.0
                static:
                  root: /var/www/vidocq-docs
                  fallback: /404.html
                  index-files: [index.html, index.htm]
                  cache-control: "max-age=3600, public"
                  gzip: true
                headers:
                  always:
                    X-Content-Type-Options: "nosniff"
                    Referrer-Policy: "strict-origin-when-cross-origin"
                  staging:
                    X-Robots-Tag: "noindex, nofollow"
                logging:
                  level: INFO
                  access-log: false
                """));

        assertEquals(8080, c.server().port());
        assertEquals("0.0.0.0", c.server().bind());
        assertEquals("/var/www/vidocq-docs", c.staticCfg().root());
        assertEquals("/404.html", c.staticCfg().fallback());
        assertEquals(List.of("index.html", "index.htm"), c.staticCfg().indexFiles());
        assertEquals("max-age=3600, public", c.staticCfg().cacheControl());
        assertEquals(Boolean.TRUE, c.staticCfg().gzip());
        assertEquals("nosniff", c.headers().always().get("X-Content-Type-Options"));
        assertEquals("noindex, nofollow", c.headers().staging().get("X-Robots-Tag"));
        assertEquals("INFO", c.logging().level());
        assertEquals(Boolean.FALSE, c.logging().accessLog());
    }

    @Test
    void missingSectionsLeaveSubrecordsEmpty() {
        var c = ChappeConfig.from(YamlReader.parse("""
                server:
                  port: 9090
                """));
        assertEquals(9090, c.server().port());
        assertNull(c.server().bind());
        assertNull(c.staticCfg().root());
        assertTrue(c.headers().always().isEmpty());
        assertTrue(c.headers().staging().isEmpty());
    }

    @Test
    void spaFallbackKeyRecognised() {
        var c = ChappeConfig.from(YamlReader.parse("""
                static:
                  spa-fallback: /index.html
                """));
        assertEquals("/index.html", c.staticCfg().spaFallback());
    }
}
