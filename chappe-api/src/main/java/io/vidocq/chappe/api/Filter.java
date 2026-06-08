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
package io.vidocq.chappe.api;

import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP filter (middleware) — transforms a {@link Handler} into another.
 * <p>
 * Filters compose naturally:
 * <pre>{@code
 * Filter logging = next -> request -> {
 *     System.out.println(request.method() + " " + request.path());
 *     return next.handle(request);
 * };
 *
 * Filter auth = next -> request -> {
 *     if (request.header("Authorization").isEmpty()) {
 *         return Response.of(StatusCode.UNAUTHORIZED);
 *     }
 *     return next.handle(request);
 * };
 *
 * // Compose: logging runs before auth
 * Handler secured = logging.andThen(auth).apply(myHandler);
 * }</pre>
 *
 * <h2>Helpers</h2>
 * <ul>
 *   <li>{@link #addHeader(String, String)} — injects a header on every response.</li>
 *   <li>{@link #addHeaderIf(BooleanSupplier, String, String)} — conditional header.</li>
 *   <li>{@link #addHeaderIfEnv(String, String, String, String)} — header based on environment variable.</li>
 * </ul>
 */
@FunctionalInterface
public interface Filter {

    /**
     * Named system logger used by the default {@link #accessLog()} sink — {@code io.vidocq.chappe.access}.
     * Configure it through {@code java.util.logging} (or any {@link System.LoggerFinder}) to redirect
     * the access log elsewhere. For the historical "stdout captured by Docker/Portainer" behaviour,
     * use {@code accessLog(System.out::println)} explicitly.
     */
    System.Logger ACCESS_LOG = System.getLogger("io.vidocq.chappe.access");

    /**
     * Wraps the handler {@code next} with additional behaviour.
     *
     * @param next the next handler in the chain
     * @return a new decorated handler
     */
    Handler apply(Handler next);

    /**
     * Composes this filter with another: {@code this} runs before {@code after}.
     *
     * @param after the filter to apply after this one
     * @return a composed filter
     */
    default Filter andThen(Filter after) {
        return next -> this.apply(after.apply(next));
    }

    /**
     * Filter that adds a header to every response, unconditionally.
     *
     * @param name  header name
     * @param value header value
     * @return filter that injects the header
     */
    static Filter addHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return addHeaderIf(() -> true, name, value);
    }

    /**
     * Filter that adds a header if {@code predicate} returns {@code true}.
     * The predicate is re-evaluated for every request.
     *
     * @param predicate add condition (evaluated per-request)
     * @param name      header name
     * @param value     header value
     * @return filter that conditionally injects the header
     */
    static Filter addHeaderIf(BooleanSupplier predicate, String name, String value) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return next -> request -> {
            Response r = next.handle(request);
            if (!predicate.getAsBoolean()) return r;
            Headers.Builder hb = Headers.builder();
            for (Headers.Entry e : r.headers()) {
                hb.add(e.name(), e.value());
            }
            hb.add(name, value);
            return Response.builder()
                    .status(r.status())
                    .headers(hb.build())
                    .body(r.body())
                    .build();
        };
    }

    /**
     * Filter that adds a header if the environment variable {@code envVar}
     * has the value {@code expectedValue} (strict case-sensitive comparison).
     *
     * @param envVar        name of the environment variable to inspect
     * @param expectedValue expected value (the variable must be strictly equal)
     * @param name          name of the header to add
     * @param value         value of the header to add
     * @return filter conditional on the environment
     */
    static Filter addHeaderIfEnv(String envVar, String expectedValue, String name, String value) {
        Objects.requireNonNull(envVar, "envVar");
        Objects.requireNonNull(expectedValue, "expectedValue");
        return addHeaderIf(() -> expectedValue.equals(System.getenv(envVar)), name, value);
    }

    /** Default threshold below which on-the-fly compression is not applied. */
    int GZIP_DEFAULT_THRESHOLD = 1024;

    /**
     * On-the-fly {@code Content-Encoding: gzip} compression filter, negotiated
     * via {@code Accept-Encoding}. Default threshold: {@value #GZIP_DEFAULT_THRESHOLD} bytes.
     */
    static Filter gzip() {
        return gzip(GZIP_DEFAULT_THRESHOLD);
    }

    /**
     * Variant of {@link #gzip()} with a configurable threshold.
     * Responses whose declared size is strictly below the threshold
     * are not compressed (overhead not worthwhile).
     */
    static Filter gzip(int threshold) {
        return next -> request -> {
            String acceptEnc = request.header("Accept-Encoding").orElse(null);
            Response r = next.handle(request);
            if (!AcceptEncoding.accepts(acceptEnc, "gzip")) return r;
            if (r.headers().contains("Content-Encoding")) return r;
            String cc = r.headers().firstOrNull("Cache-Control");
            if (cc != null && cc.toLowerCase(Locale.ROOT).contains("no-transform")) return r;
            if (!isCompressible(r.headers().firstOrNull("Content-Type"))) return r;
            long len = r.body().contentLength();
            if (len >= 0 && len < threshold) return r;

            byte[] compressed;
            try (var in = r.body().asInputStream();
                    var bos = new ByteArrayOutputStream();
                    var gout = new GZIPOutputStream(bos)) {
                in.transferTo(gout);
                gout.finish();
                compressed = bos.toByteArray();
            }

            Headers.Builder hb = Headers.builder();
            for (Headers.Entry e : r.headers()) {
                if (!e.name().equalsIgnoreCase("Content-Length")) {
                    hb.add(e.name(), e.value());
                }
            }
            hb.add("Content-Encoding", "gzip");
            hb.add("Vary", "Accept-Encoding");
            return Response.builder()
                    .status(r.status())
                    .headers(hb.build())
                    .body(Body.of(compressed))
                    .build();
        };
    }

    /** Apache date format: {@code [day/Mon/yyyy:HH:mm:ss +0000]}. */
    DateTimeFormatter ACCESS_LOG_DATE = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss xx", Locale.ROOT);

    /**
     * Access log filter in extended Apache Combined Log Format (CLF).
     * <p>
     * Each line is emitted at {@link System.Logger.Level#INFO INFO} on the named logger
     * {@link #ACCESS_LOG io.vidocq.chappe.access}:
     * <pre>
     * 127.0.0.1 - yann.blazart@gmail.com [09/May/2026:18:50:54 +0000] "GET /a.png HTTP/1.1" 200 877719 12ms
     * </pre>
     * For the cloud-native "stdout captured by Docker/Portainer" convention, use
     * {@code accessLog(System.out::println)} explicitly, or configure JUL to bind
     * {@code io.vidocq.chappe.access} to a {@code ConsoleHandler} on {@link System#out}.
     * <ul>
     *   <li>The client IP is read from {@code X-Forwarded-For} (first hop) or
     *       {@code X-Real-IP}, otherwise {@code Request.remoteAddress()}.</li>
     *   <li>The user is read from {@code X-Forwarded-User}, {@code Gap-Auth}
     *       (oauth2-proxy) or {@code Authorization}, otherwise {@code "-"}.</li>
     *   <li>The body size is the one declared by {@code Response.body().contentLength()}
     *       (may be {@code -1} for streamed/chunked bodies, indicated as {@code "-"}).</li>
     * </ul>
     */
    static Filter accessLog() {
        return accessLog(line -> ACCESS_LOG.log(System.Logger.Level.INFO, line));
    }

    /** Variant of {@link #accessLog()} with a custom sink. */
    static Filter accessLog(Consumer<String> sink) {
        Objects.requireNonNull(sink, "sink");
        return next -> request -> {
            long startNanos = System.nanoTime();
            Response response;
            int status;
            try {
                response = next.handle(request);
                status = response.status().code();
            } catch (RuntimeException | Error e) {
                logLine(sink, request, 500, -1, startNanos);
                throw e;
            } catch (Exception e) {
                logLine(sink, request, 500, -1, startNanos);
                throw new RuntimeException(e);
            }
            long size = response.body().contentLength();
            logLine(sink, request, status, size, startNanos);
            return response;
        };
    }

    private static void logLine(Consumer<String> sink, Request req, int status, long size, long startNanos) {
        long durMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String ts = ZonedDateTime.now(ZoneOffset.UTC).format(ACCESS_LOG_DATE);
        String ip = clientIp(req);
        String user = clientUser(req);
        String sizeStr = size < 0 ? "-" : Long.toString(size);
        sink.accept(ip + " - " + user + " [" + ts + "] \""
                + req.method() + " " + req.path() + " " + req.version().wireFormat() + "\" "
                + status + " " + sizeStr + " " + durMs + "ms");
    }

    private static String clientIp(Request req) {
        String xff = req.header("X-Forwarded-For").orElse(null);
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        String xri = req.header("X-Real-IP").orElse(null);
        if (xri != null && !xri.isEmpty()) return xri.trim();
        var ra = req.remoteAddress();
        return ra != null ? ra.getAddress().getHostAddress() : "-";
    }

    private static String clientUser(Request req) {
        String u = req.header("X-Forwarded-User").orElse(null);
        if (u != null && !u.isEmpty()) return u;
        u = req.header("Gap-Auth").orElse(null);
        if (u != null && !u.isEmpty()) return u;
        return "-";
    }

    /** "Compressible" heuristic based on {@code Content-Type}. */
    private static boolean isCompressible(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/")) return true;
        if (ct.startsWith("image/svg+xml")) return true;
        // Heuristic for text-like subtypes: json, xml, javascript, wasm, manifest+json...
        return ct.startsWith("application/json")
                || ct.startsWith("application/xml")
                || ct.startsWith("application/javascript")
                || ct.startsWith("application/x-javascript")
                || ct.startsWith("application/wasm")
                || ct.startsWith("application/manifest+json")
                || ct.startsWith("application/ld+json")
                || ct.startsWith("application/xhtml+xml")
                || ct.endsWith("+json")
                || ct.endsWith("+xml");
    }
}
