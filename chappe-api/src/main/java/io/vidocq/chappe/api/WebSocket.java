package io.vidocq.chappe.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Connexion WebSocket active — RFC 6455.
 * <p>
 * Une instance est fournie au {@link WebSocketHandler} après un handshake réussi.
 * Les méthodes {@code send*} sont thread-safe : les frames sortantes sont sérialisées
 * en interne pour éviter l'entrelacement (interdit par la RFC §5.4).
 * <p>
 * Les appels bloquent jusqu'à ce que la frame complète soit écrite dans la socket —
 * naturel sur virtual threads. Une connexion fermée fait lever {@link IOException}.
 */
public interface WebSocket {

    /** Envoie un message texte (frame TEXT, opcode 0x1) en une seule frame non fragmentée. */
    void sendText(String message) throws IOException;

    /** Envoie un message binaire (frame BINARY, opcode 0x2) en une seule frame non fragmentée. */
    void sendBinary(ByteBuffer payload) throws IOException;

    /** Envoie un PING (opcode 0x9) — payload ≤ 125 octets. */
    void sendPing(ByteBuffer payload) throws IOException;

    /** Envoie un PONG (opcode 0xA) — payload ≤ 125 octets. */
    void sendPong(ByteBuffer payload) throws IOException;

    /**
     * Initie le close handshake avec un code de statut et un motif texte.
     * <p>
     * Le motif est encodé en UTF-8 et limité à 123 octets ({@code 125 - 2} pour le code).
     * Après cet appel, la connexion attend la frame Close du pair puis se ferme.
     */
    void close(int code, String reason) throws IOException;

    /** Raccourci : ferme avec {@link CloseCodes#NORMAL_CLOSURE} et sans motif. */
    default void close() throws IOException {
        close(CloseCodes.NORMAL_CLOSURE, "");
    }

    /** Vrai tant que la connexion n'a pas reçu ni envoyé de frame Close. */
    boolean isOpen();

    /** Adresse de l'extrémité distante (client). */
    InetSocketAddress remoteAddress();

    /** Vrai si la connexion sous-jacente est TLS (wss://). */
    boolean isSecure();

    /**
     * Sous-protocole négocié (header {@code Sec-WebSocket-Protocol}), ou {@code null}
     * si aucun n'a été demandé ou accepté.
     */
    String subprotocol();

    /** Attribut mutable attaché à la connexion (état session). */
    Object attribute(String key);

    /** Affecte un attribut. Retourne {@code this} pour chaînage. */
    WebSocket attribute(String key, Object value);
}
