package io.vidocq.chappe.api;

import java.util.function.Consumer;

/**
 * Routeur HTTP — associe des patterns de chemin à des {@link Handler handlers}.
 * <p>
 * Le routeur est lui-même un {@link Handler} : il peut être utilisé partout
 * où un handler est attendu (composition, nesting, wrapping par des filtres).
 *
 * <pre>{@code
 * var router = Router.builder()
 *     .get("/", _ -> Response.ok("Home"))
 *     .get("/users/{id}", req -> {
 *         var id = req.pathParams().get("id");
 *         return Response.ok("User " + id);
 *     })
 *     .group("/api", api -> api
 *         .filter(authFilter)
 *         .get("/health", _ -> Response.ok("UP"))
 *     )
 *     .build();
 * }</pre>
 *
 * <h2>Patterns de chemin</h2>
 * <ul>
 *   <li>{@code /users} — littéral</li>
 *   <li>{@code /users/{id}} — paramètre nommé (capturé dans {@link Request#pathParams()})</li>
 *   <li>{@code /static/*} — wildcard (matche tout le reste du chemin)</li>
 * </ul>
 */
public interface Router extends Handler {

    /** Crée un nouveau builder de routeur. */
    static Builder builder() {
        return new DefaultRouterBuilder();
    }

    /** Builder fluide pour construire un {@link Router}. */
    interface Builder {

        Builder get(String pattern, Handler handler);

        Builder head(String pattern, Handler handler);

        Builder post(String pattern, Handler handler);

        Builder put(String pattern, Handler handler);

        Builder delete(String pattern, Handler handler);

        Builder options(String pattern, Handler handler);

        Builder patch(String pattern, Handler handler);

        /** Enregistre une route pour une méthode arbitraire. */
        Builder route(HttpMethod method, String pattern, Handler handler);

        /**
         * Groupe de routes avec un préfixe commun.
         * Les filtres ajoutés dans le groupe ne s'appliquent qu'à ses routes.
         */
        Builder group(String prefix, Consumer<Builder> routes);

        /** Ajoute un filtre à toutes les routes de ce builder. */
        Builder filter(Filter filter);

        /** Mounts a sub-handler at the given path prefix (all methods, path stripping). */
        Builder mount(String prefix, Handler handler);

        /** Handler pour les routes non trouvées (404 par défaut). */
        Builder notFound(Handler handler);

        /** Construit le routeur immutable. */
        Router build();
    }
}
