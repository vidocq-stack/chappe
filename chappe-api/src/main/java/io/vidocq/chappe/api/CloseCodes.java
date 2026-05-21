package io.vidocq.chappe.api;

/**
 * Codes de fermeture WebSocket — RFC 6455 §7.4.
 * <p>
 * Plage 0–999 réservée, 1000–2999 spécifiée par RFC, 3000–3999 réservée aux registres
 * de librairies et frameworks, 4000–4999 disponible pour les applications.
 */
public final class CloseCodes {

    /** Fermeture normale — le but pour lequel la connexion a été établie est atteint. */
    public static final int NORMAL_CLOSURE = 1000;

    /** L'endpoint s'en va (serveur qui s'arrête, onglet navigateur qui se ferme). */
    public static final int GOING_AWAY = 1001;

    /** Erreur de protocole. */
    public static final int PROTOCOL_ERROR = 1002;

    /** Type de données non supporté (ex. endpoint texte recevant du binaire). */
    public static final int UNSUPPORTED_DATA = 1003;

    /** Aucun code reçu — réservé, NE DOIT PAS être envoyé dans une frame Close. */
    public static final int NO_STATUS_RCVD = 1005;

    /** Fermeture anormale — réservé, NE DOIT PAS être envoyé dans une frame Close. */
    public static final int ABNORMAL_CLOSURE = 1006;

    /** Payload non conforme au type (ex. UTF-8 invalide dans une frame TEXT). */
    public static final int INVALID_PAYLOAD_DATA = 1007;

    /** Violation de politique générique. */
    public static final int POLICY_VIOLATION = 1008;

    /** Message trop volumineux pour être traité. */
    public static final int MESSAGE_TOO_BIG = 1009;

    /** Extension(s) attendue(s) absente(s) côté serveur. */
    public static final int MANDATORY_EXTENSION = 1010;

    /** Condition inattendue empêchant le serveur de répondre. */
    public static final int INTERNAL_ERROR = 1011;

    /** Service redémarre. */
    public static final int SERVICE_RESTART = 1012;

    /** Service surchargé, ré-essayer plus tard. */
    public static final int TRY_AGAIN_LATER = 1013;

    /** Passerelle invalide. */
    public static final int BAD_GATEWAY = 1014;

    /** Échec du handshake TLS — réservé, NE DOIT PAS être envoyé dans une frame Close. */
    public static final int TLS_HANDSHAKE = 1015;

    private CloseCodes() {}

    /**
     * Indique si le code est valide en tant que status de frame Close envoyée sur le fil.
     * Les codes 1005, 1006 et 1015 sont réservés pour usage local et NE DOIVENT PAS apparaître
     * sur le réseau (RFC 6455 §7.4.1).
     */
    public static boolean isValidOnWire(int code) {
        if (code < 1000 || code > 4999) return false;
        if (code == NO_STATUS_RCVD || code == ABNORMAL_CLOSURE || code == TLS_HANDSHAKE) return false;
        // 1016-2999 réservés mais pas utilisés
        if (code >= 1016 && code <= 2999) return false;
        return true;
    }
}
