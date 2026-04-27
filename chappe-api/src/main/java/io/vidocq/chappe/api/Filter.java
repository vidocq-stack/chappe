package io.vidocq.chappe.api;

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
}
