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
 * Filtre HTTP (middleware) — transforme un {@link Handler} en un autre.
 * <p>
 * Les filtres se composent naturellement :
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
 * // Compose : logging s'exécute avant auth
 * Handler secured = logging.andThen(auth).apply(myHandler);
 * }</pre>
 *
 * <h2>Helpers</h2>
 * <ul>
 *   <li>{@link #addHeader(String, String)} — injecte un header sur toute réponse.</li>
 *   <li>{@link #addHeaderIf(BooleanSupplier, String, String)} — header conditionnel.</li>
 *   <li>{@link #addHeaderIfEnv(String, String, String, String)} — header selon variable d'environnement.</li>
 * </ul>
 */
@FunctionalInterface
public interface Filter {

    /**
     * Enveloppe le handler {@code next} avec un comportement additionnel.
     *
     * @param next le handler suivant dans la chaîne
     * @return un nouveau handler décoré
     */
    Handler apply(Handler next);

    /**
     * Compose ce filtre avec un autre : {@code this} s'exécute avant {@code after}.
     *
     * @param after le filtre à appliquer après celui-ci
     * @return un filtre composé
     */
    default Filter andThen(Filter after) {
        return next -> this.apply(after.apply(next));
    }

    /**
     * Filtre qui ajoute un header à chaque réponse, sans condition.
     *
     * @param name  nom du header
     * @param value valeur du header
     * @return filtre injectant le header
     */
    static Filter addHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return addHeaderIf(() -> true, name, value);
    }

    /**
     * Filtre qui ajoute un header si {@code predicate} retourne {@code true}.
     * Le prédicat est ré-évalué à chaque requête.
     *
     * @param predicate condition d'ajout (évaluée per-request)
     * @param name      nom du header
     * @param value     valeur du header
     * @return filtre injectant conditionnellement le header
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
     * Filtre qui ajoute un header si la variable d'environnement {@code envVar}
     * a la valeur {@code expectedValue} (comparaison stricte sensible à la casse).
     *
     * @param envVar        nom de la variable d'environnement à inspecter
     * @param expectedValue valeur attendue (la variable doit être strictement égale)
     * @param name          nom du header à ajouter
     * @param value         valeur du header à ajouter
     * @return filtre conditionnel sur l'environnement
     */
    static Filter addHeaderIfEnv(String envVar, String expectedValue, String name, String value) {
        Objects.requireNonNull(envVar, "envVar");
        Objects.requireNonNull(expectedValue, "expectedValue");
        return addHeaderIf(() -> expectedValue.equals(System.getenv(envVar)), name, value);
    }

    /** Seuil par défaut sous lequel on n'applique pas la compression à la volée. */
    int GZIP_DEFAULT_THRESHOLD = 1024;

    /**
     * Filtre de compression {@code Content-Encoding: gzip} à la volée, négocié
     * via {@code Accept-Encoding}. Seuil par défaut : {@value #GZIP_DEFAULT_THRESHOLD} octets.
     */
    static Filter gzip() {
        return gzip(GZIP_DEFAULT_THRESHOLD);
    }

    /**
     * Variante de {@link #gzip()} avec seuil configurable.
     * Les réponses dont la taille déclarée est strictement inférieure au seuil
     * ne sont pas compressées (overhead non rentable).
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

    /** Format de date Apache : {@code [day/Mon/yyyy:HH:mm:ss +0000]}. */
    DateTimeFormatter ACCESS_LOG_DATE = DateTimeFormatter
            .ofPattern("dd/MMM/yyyy:HH:mm:ss xx", Locale.ROOT);

    /**
     * Filtre d'access log au format Apache Combined Log Format (CLF) étendu.
     * <p>
     * Sortie sur {@code System.out} (capté par Docker/Portainer) :
     * <pre>
     * 127.0.0.1 - yann.blazart@gmail.com [09/May/2026:18:50:54 +0000] "GET /a.png HTTP/1.1" 200 877719 12ms
     * </pre>
     * <ul>
     *   <li>L'IP cliente est lue dans {@code X-Forwarded-For} (premier hop) ou
     *       {@code X-Real-IP}, sinon {@code Request.remoteAddress()}.</li>
     *   <li>L'utilisateur est lu dans {@code X-Forwarded-User}, {@code Gap-Auth}
     *       (oauth2-proxy) ou {@code Authorization}, sinon {@code "-"}.</li>
     *   <li>La taille du body est celle annoncée par {@code Response.body().contentLength()}
     *       (peut être {@code -1} pour les corps streamés/chunked, indiquée alors par {@code "-"}).</li>
     * </ul>
     */
    static Filter accessLog() {
        return accessLog(line -> System.out.println(line));
    }

    /** Variante de {@link #accessLog()} avec un sink personnalisé. */
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

    private static void logLine(Consumer<String> sink, Request req,
                                int status, long size, long startNanos) {
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

    /** Heuristique « compressible » sur le {@code Content-Type}. */
    private static boolean isCompressible(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/")) return true;
        if (ct.startsWith("image/svg+xml")) return true;
        // Heuristique pour les sous-types text-like : json, xml, javascript, wasm, manifest+json…
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
