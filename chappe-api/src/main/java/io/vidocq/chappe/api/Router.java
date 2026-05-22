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

        /**
         * Enregistre un endpoint WebSocket (RFC 6455).
         * <p>
         * Sur une requête HTTP/1.1 {@code GET} avec les headers de handshake corrects,
         * la connexion est upgradée et {@code handler} reçoit les événements de la session.
         * Sinon une {@code 400 Bad Request} est retournée.
         */
        Builder webSocket(String pattern, WebSocketHandler handler);

        /**
         * Enregistre un endpoint gRPC (transport HTTP/2 + framing core gRPC).
         * <p>
         * À une requête {@code POST} sur {@code pattern} en HTTP/2 avec
         * {@code content-type: application/grpc[+xxx]}, la connexion bascule en mode
         * streaming bidirectionnel et {@code handler} reçoit un {@link GrpcCall}.
         * <p>
         * Conditions de refus :
         * <ul>
         *   <li>version HTTP &lt; 2 → {@code 505 HTTP Version Not Supported}</li>
         *   <li>{@code content-type} absent ou ≠ {@code application/grpc...} → {@code 415}</li>
         * </ul>
         * La sérialisation des messages (protobuf, json, …) est à la charge du handler.
         */
        Builder grpc(String pattern, GrpcHandler handler);

        /**
         * Enregistre un endpoint <b>gRPC-Web</b> (PROTOCOL-WEB.md, navigateurs).
         * <p>
         * Variante de gRPC où les trailers sont sérialisés inline dans le corps comme une
         * frame DATA spéciale (préfixe {@code 0x80}), car les navigateurs n'exposent pas
         * les trailers HTTP/2 à JavaScript. Le content-type du client choisit le mode :
         * <ul>
         *   <li>{@code application/grpc-web} → binaire</li>
         *   <li>{@code application/grpc-web-text} → Base64 (chaque chunk indépendamment)</li>
         * </ul>
         * Toute autre valeur → {@code 415}. V1 chappe = HTTP/2 uniquement ({@code 505}
         * sinon). Le même {@link GrpcHandler} que pour {@link #grpc} est utilisé —
         * le handler reçoit les bytes décodés, ne se soucie pas de la variante.
         */
        Builder grpcWeb(String pattern, GrpcHandler handler);

        /** Handler pour les routes non trouvées (404 par défaut). */
        Builder notFound(Handler handler);

        /** Construit le routeur immutable. */
        Router build();
    }
}
