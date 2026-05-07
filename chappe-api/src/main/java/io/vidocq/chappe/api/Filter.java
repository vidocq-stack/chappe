package io.vidocq.chappe.api;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
