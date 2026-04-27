package io.vidocq.chappe.api;

/**
 * Gestionnaire de requête HTTP — interface fonctionnelle {@code Request → Response}.
 * <p>
 * C'est le contrat central de Chappe. Chaque handler reçoit une requête
 * immutable et retourne une réponse.
 *
 * <pre>{@code
 * Handler hello = request -> Response.ok("Hello, Chappe!");
 * }</pre>
 */
@FunctionalInterface
public interface Handler {

    /**
     * Traite une requête HTTP et retourne une réponse.
     *
     * @param request la requête entrante (lecture seule)
     * @return la réponse à envoyer au client
     * @throws Exception si le traitement échoue (sera converti en 500 par le serveur)
     */
    Response handle(Request request) throws Exception;
}
