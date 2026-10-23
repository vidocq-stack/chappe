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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Characterization of {@link CliArgs#parse(String[])}: ordering, overrides and error messages. */
class CliArgsParsingTest {

    @Test
    void helpCommandReturnsTheSentinel() {
        CliArgs help = CliArgs.parse(new String[] {"help"});
        assertSame(CliArgs.parse(new String[0]), help);
        assertTrue(help.help());
        assertNull(help.port());
        assertEquals(Map.of(), help.extraHeaders());
    }

    @Test
    void helpFlagAfterServeKeepsTheOtherFlags() {
        CliArgs a = CliArgs.parse(new String[] {"serve", "--help", "--port", "1"});
        assertTrue(a.help());
        assertEquals(1, a.port());
    }

    @Test
    void laterFlagsOverrideEarlierOnes() {
        CliArgs a = CliArgs.parse(new String[] {
            "serve",
            "--port",
            "1",
            "--port",
            "2",
            "--gzip",
            "--no-gzip",
            "--no-access-log",
            "--access-log",
            "--expose-build-info",
            "--no-expose-build-info"
        });
        assertEquals(2, a.port());
        assertFalse(a.gzip());
        assertTrue(a.accessLog());
        assertFalse(a.exposeBuildInfo());
    }

    @Test
    void headerKeyIsTrimmedAndValueKeptVerbatim() {
        CliArgs a = CliArgs.parse(new String[] {"serve", "--header", " X-A =b= c", "--header", "X-B=1"});
        assertEquals(Map.of("X-A", "b= c", "X-B", "1"), a.extraHeaders());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "serve --nope          | unknown flag: --nope",
                "serve --port          | missing value for --port",
                "serve --header        | missing value for --header",
                "serve --header =v     | --header expects KEY=VALUE, got: =v",
                "serve --gzip --what x | unknown flag: --what",
                "start                 | unknown command: start (expected: serve)"
            })
    void invalidArgumentsAreRejected(String argv, String message) {
        String[] args = argv.split(" ", -1);
        var ex = assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(args));
        assertEquals(message, ex.getMessage());
    }

    @Test
    void nonNumericPortIsANumberFormatException() {
        String[] args = {"serve", "--port", "http"};
        assertThrows(NumberFormatException.class, () -> CliArgs.parse(args));
    }
}
