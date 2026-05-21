package io.vidocq.chappe.api;

import java.nio.ByteBuffer;

/**
 * Gestionnaire d'une connexion WebSocket — RFC 6455.
 * <p>
 * Toutes les méthodes ont une implémentation par défaut vide :
 * une application n'a qu'à surcharger les événements qu'elle traite.
 * <p>
 * Tous les callbacks pour une même connexion sont invoqués séquentiellement
 * sur le virtual thread de la connexion : pas de synchronisation nécessaire
 * pour accéder à un état attaché via {@link WebSocket#attribute(String, Object)}.
 *
 * <pre>{@code
 * var router = Router.builder()
 *     .webSocket("/echo", new WebSocketHandler() {
 *         @Override public void onText(WebSocket ws, String message) throws Exception {
 *             ws.sendText(message);
 *         }
 *     })
 *     .build();
 * }</pre>
 */
public interface WebSocketHandler {

    /** Appelé une fois après un handshake réussi, avant la première frame. */
    default void onOpen(WebSocket ws, Request handshake) throws Exception {}

    /** Message texte complet (frames TEXT + CONTINUATION ré-assemblées, UTF-8 validé). */
    default void onText(WebSocket ws, String message) throws Exception {}

    /**
     * Message binaire complet (frames BINARY + CONTINUATION ré-assemblées).
     * <p>
     * Le {@link ByteBuffer} est en mode lecture (position = 0, limit = taille) ;
     * il ne doit pas être conservé au-delà du callback — son contenu peut être recyclé.
     */
    default void onBinary(WebSocket ws, ByteBuffer data) throws Exception {}

    /**
     * Frame PING reçue. Par défaut, le serveur répond automatiquement par un PONG
     * avec le même payload <em>avant</em> que ce callback soit invoqué (RFC 6455 §5.5.2).
     * Surcharger uniquement pour de l'observabilité.
     */
    default void onPing(WebSocket ws, ByteBuffer payload) throws Exception {}

    /** Frame PONG reçue (réponse à un précédent PING). */
    default void onPong(WebSocket ws, ByteBuffer payload) throws Exception {}

    /**
     * Frame Close reçue ou déduite (close handshake terminé / connexion perdue).
     * <p>
     * {@code code} vaut {@link CloseCodes#NO_STATUS_RCVD} si le pair n'a pas envoyé de code,
     * {@link CloseCodes#ABNORMAL_CLOSURE} si la connexion TCP a été coupée sans Close.
     */
    default void onClose(WebSocket ws, int code, String reason) throws Exception {}

    /**
     * Erreur lors du traitement de la connexion (lecture/écriture, callback applicatif).
     * Invoqué juste avant {@link #onClose} avec un code adapté.
     */
    default void onError(WebSocket ws, Throwable error) {}
}
