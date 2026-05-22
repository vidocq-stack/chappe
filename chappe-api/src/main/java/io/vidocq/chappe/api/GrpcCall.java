package io.vidocq.chappe.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Représente un appel gRPC en cours côté serveur.
 * <p>
 * API synchrone bloquante — un appel gRPC tourne sur un virtual thread dédié.
 * Couvre les 4 modes : unary, server-streaming, client-streaming, bidi-streaming.
 * <p>
 * Cycle de vie typique :
 * <pre>{@code
 * // unary
 * byte[] req = call.receive();
 * byte[] resp = ...;
 * call.send(resp);
 * call.complete(GrpcStatus.OK, "");
 *
 * // server-streaming
 * byte[] req = call.receive();
 * for (var msg : stream) call.send(serialize(msg));
 * call.complete(GrpcStatus.OK, "");
 *
 * // client-streaming
 * byte[] msg;
 * while ((msg = call.receive()) != null) accumulate(msg);
 * call.send(reduce());
 * call.complete(GrpcStatus.OK, "");
 *
 * // bidi-streaming (le handler peut lancer un autre virtual thread pour write)
 * }</pre>
 *
 * Les bytes échangés sont opaques pour Chappe — la sérialisation (protobuf ou autre)
 * est la responsabilité de l'extension.
 */
public interface GrpcCall {

    /**
     * Lit le prochain message du client.
     *
     * @return les bytes du message, ou {@code null} si le client a fait half-close (END_STREAM)
     * @throws IOException si l'I/O ou le framing échoue
     */
    byte[] receive() throws IOException;

    /**
     * Émet un message vers le client. Bloque si le flow control HTTP/2 est saturé.
     * <p>
     * Le premier appel à {@code send} déclenche l'envoi des headers initiaux serveur si
     * pas encore fait ({@code :status 200}, {@code content-type: application/grpc}).
     */
    void send(byte[] message) throws IOException;

    /**
     * Termine l'appel avec un statut gRPC final.
     * <p>
     * Émet les trailers HTTP/2 avec {@code grpc-status} (et {@code grpc-message} si non vide),
     * avec END_STREAM. Si aucun {@link #send} n'a été appelé et que les headers initiaux n'ont
     * pas été émis, fusionne tout dans un trailers-only HEADERS frame (RFC §8.1).
     */
    void complete(int grpcStatus, String message) throws IOException;

    /** Headers reçus du client en début de stream (avant DATA frames). */
    Headers metadata();

    /**
     * Ajoute un header au response initial (Initial-Metadata).
     * Doit être appelé <b>avant</b> le premier {@link #send}.
     * @throws IllegalStateException si les headers initiaux ont déjà été émis
     */
    void addHeader(String name, String value);

    /**
     * Ajoute un trailer additionnel. Doit être appelé avant {@link #complete}.
     * @throws IllegalStateException si {@code complete} a déjà été appelé
     */
    void addTrailer(String name, String value);

    /** {@code true} si le client a annulé le stream (RST_STREAM ou déconnexion). */
    boolean isCancelled();

    /** Content-type négocié, ex. {@code application/grpc}, {@code application/grpc+proto}. */
    String contentType();

    /**
     * Active la compression des messages sortants (RFC gRPC §"Compression").
     * <p>
     * Doit être appelé <b>avant</b> le premier {@link #send(byte[])} sinon les headers
     * initiaux auront déjà été émis. La couche transport ajoute alors :
     * <ul>
     *   <li>{@code grpc-encoding: <encoding>} aux headers initiaux serveur</li>
     *   <li>flag {@code compressed=1} dans le préfixe 5 octets de chaque message envoyé</li>
     * </ul>
     * En réception, le serveur déclare toujours {@code grpc-accept-encoding: identity,gzip}
     * et décompresse automatiquement les messages entrants compressés selon le
     * {@code grpc-encoding} du client.
     *
     * @param encoding codec — actuellement {@code "identity"} (no-op) ou {@code "gzip"}
     * @throws IllegalStateException         si les headers initiaux ont déjà été émis
     * @throws UnsupportedOperationException si {@code encoding} n'est pas supporté
     */
    void useResponseEncoding(String encoding);

    /**
     * Deadline propagée par le client via le header {@code grpc-timeout} (RFC gRPC §"Requests").
     * <p>
     * Si présent, la couche transport Chappe arme automatiquement un watchdog : à
     * l'expiration, le stream est annulé (débloque les {@link #receive()} en cours) et un
     * trailer {@code grpc-status: 4 (DEADLINE_EXCEEDED)} est émis vers le client si le
     * handler n'avait pas encore appelé {@link #complete(int, String)}.
     * <p>
     * Le handler peut consulter cette valeur pour adapter son comportement — par exemple
     * raccourcir la deadline d'un appel downstream.
     *
     * @return durée restante depuis {@link System#nanoTime()}, ou {@link Optional#empty()} si
     *         le client n'a pas envoyé de {@code grpc-timeout}. Peut être {@link Duration#ZERO}
     *         si la deadline est déjà expirée au moment de l'appel.
     */
    Optional<Duration> deadline();
}
