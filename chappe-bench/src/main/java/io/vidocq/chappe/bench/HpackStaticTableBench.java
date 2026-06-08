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
package io.vidocq.chappe.bench;

import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.http.h2.HpackStaticTable;

import org.openjdk.jmh.annotations.*;

/**
 * Compares the current lookup (prebuilt maps, O(1)) to the previous O(61) linear scan
 * on the two hot methods of the HPACK hot path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class HpackStaticTableBench {

    // 61 entries — copied to reproduce the old linear-scan implementation.
    private static final String[][] OLD_ENTRIES = buildOld();

    // Representative inputs: frequent header (early hit), rare header (late hit), miss.
    @Param({":method", "vary", "x-custom-header"})
    public String name;

    @Param({"GET", ""})
    public String value;

    // -- findByName --

    @Benchmark
    public int findByName_current() {
        return HpackStaticTable.findByName(name);
    }

    @Benchmark
    public int findByName_oldLinear() {
        for (int i = 1; i <= 61; i++) {
            if (OLD_ENTRIES[i][0].equals(name)) return i;
        }
        return 0;
    }

    // -- findExact --

    @Benchmark
    public int findExact_current() {
        return HpackStaticTable.findExact(name, value);
    }

    @Benchmark
    public int findExact_oldLinear() {
        for (int i = 1; i <= 61; i++) {
            if (OLD_ENTRIES[i][0].equals(name) && OLD_ENTRIES[i][1].equals(value)) return i;
        }
        return 0;
    }

    private static String[][] buildOld() {
        return new String[][] {
            null,
            {":authority", ""},
            {":method", "GET"},
            {":method", "POST"},
            {":path", "/"},
            {":path", "/index.html"},
            {":scheme", "http"},
            {":scheme", "https"},
            {":status", "200"},
            {":status", "204"},
            {":status", "206"},
            {":status", "304"},
            {":status", "400"},
            {":status", "404"},
            {":status", "500"},
            {"accept-charset", ""},
            {"accept-encoding", "gzip, deflate"},
            {"accept-language", ""},
            {"accept-ranges", ""},
            {"accept", ""},
            {"access-control-allow-origin", ""},
            {"age", ""},
            {"allow", ""},
            {"authorization", ""},
            {"cache-control", ""},
            {"content-disposition", ""},
            {"content-encoding", ""},
            {"content-language", ""},
            {"content-length", ""},
            {"content-location", ""},
            {"content-range", ""},
            {"content-type", ""},
            {"cookie", ""},
            {"date", ""},
            {"etag", ""},
            {"expect", ""},
            {"expires", ""},
            {"from", ""},
            {"host", ""},
            {"if-match", ""},
            {"if-modified-since", ""},
            {"if-none-match", ""},
            {"if-range", ""},
            {"if-unmodified-since", ""},
            {"last-modified", ""},
            {"link", ""},
            {"location", ""},
            {"max-forwards", ""},
            {"proxy-authenticate", ""},
            {"proxy-authorization", ""},
            {"range", ""},
            {"referer", ""},
            {"refresh", ""},
            {"retry-after", ""},
            {"server", ""},
            {"set-cookie", ""},
            {"strict-transport-security", ""},
            {"transfer-encoding", ""},
            {"user-agent", ""},
            {"vary", ""},
            {"via", ""},
            {"www-authenticate", ""},
        };
    }
}
