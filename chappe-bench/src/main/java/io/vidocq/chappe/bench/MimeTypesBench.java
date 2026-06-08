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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.MimeTypes;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares the new zero-allocation implementation ({@code regionMatches(true, …)} on a frequency-
 * ordered array) with the previous one ({@code substring + toLowerCase + Map.getOrDefault}).
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
@SuppressWarnings("StringCaseLocaleUsage") // Reproduit l'ancien code à benchmarker
public class MimeTypesBench {

    private static final Map<String, String> OLD_TYPES = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"));

    @Param({"index.html", "APP.CSS", "main.js", "Logo.PNG", "unknown.xyz"})
    public String filename;

    @Benchmark
    public String current_detect() {
        return MimeTypes.detect(filename);
    }

    @Benchmark
    public String old_substringLowercase() {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "application/octet-stream";
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        return OLD_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    // Allocation profiler : JMH signalera bytes/op via -prof gc.
    @Benchmark
    public void current_alloc(Blackhole bh) {
        bh.consume(MimeTypes.detect(filename));
    }

    @Benchmark
    public void old_alloc(Blackhole bh) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            bh.consume("application/octet-stream");
            return;
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        bh.consume(OLD_TYPES.getOrDefault(ext, "application/octet-stream"));
    }
}
