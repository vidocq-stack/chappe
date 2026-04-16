package fr.vidocq.chappe.http;

/**
 * Résultat d'une tentative de parsing.
 */
public enum ParseResult {

    /** Requête complètement parsée, prête pour le dispatch. */
    COMPLETE,

    /** Le pair distant a fermé la connexion. */
    CONNECTION_CLOSED
}
