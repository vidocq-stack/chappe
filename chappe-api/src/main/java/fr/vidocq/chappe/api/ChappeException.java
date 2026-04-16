package fr.vidocq.chappe.api;

/**
 * Hiérarchie d'exceptions de Chappe — sealed pour permettre
 * le pattern matching exhaustif.
 */
public sealed class ChappeException extends RuntimeException {

    protected ChappeException(String message) {
        super(message);
    }

    protected ChappeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Requête malformée ou dépassant les limites configurées.
     */
    public static final class BadRequestException extends ChappeException {

        public BadRequestException(String message) {
            super(message);
        }

        public BadRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Erreur levée par un {@link Handler} lors du traitement.
     */
    public static final class HandlerException extends ChappeException {

        public HandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Erreur liée au cycle de vie du serveur (bind, start, stop).
     */
    public static final class ServerException extends ChappeException {

        public ServerException(String message) {
            super(message);
        }

        public ServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
