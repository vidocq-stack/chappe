package io.vidocq.chappe.api.client;

import java.util.List;
import java.util.Map;

/**
 * Résultat d'un appel {@link GrpcWebClient#unary} ou de la dernière itération d'un
 * {@link GrpcWebClient#serverStream}.
 *
 * @param messages payloads décodés (déjà déframés du préfixe 5 octets et désérialisés
 *                 de Base64 si mode TEXT)
 * @param status   {@code grpc-status} extrait du trailer frame inline (0 = OK)
 * @param message  {@code grpc-message} extrait du trailer frame (peut être null/vide
 *                 si OK ou si le serveur ne l'a pas envoyé)
 * @param trailers tous les trailers parsés (noms en lowercase) pour accès aux
 *                 metadata custom — inclut {@code grpc-status}/{@code grpc-message}
 */
public record GrpcWebResponse(List<byte[]> messages, int status, String message, Map<String, String> trailers) {

    /** {@code true} si {@code grpc-status == 0}. */
    public boolean isOk() {
        return status == 0;
    }

    /** Premier message reçu ; pratique pour les unary calls. */
    public byte[] firstMessage() {
        if (messages.isEmpty()) return null;
        return messages.get(0);
    }
}
