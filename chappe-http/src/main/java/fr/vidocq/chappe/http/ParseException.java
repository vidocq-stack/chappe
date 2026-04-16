package fr.vidocq.chappe.http;

import fr.vidocq.chappe.api.StatusCode;

/**
 * Exception levée lors du parsing d'une requête HTTP malformée.
 * <p>
 * Porte le {@link StatusCode} approprié pour la réponse d'erreur
 * (400, 413, 414, 431…).
 */
public final class ParseException extends Exception {

    private final StatusCode statusCode;

    public ParseException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /** Code de statut HTTP à retourner au client. */
    public StatusCode statusCode() {
        return statusCode;
    }
}
