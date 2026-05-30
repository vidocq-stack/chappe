package io.vidocq.chappe.api;

/**
 * Chappe exception hierarchy — sealed to allow
 * exhaustive pattern matching.
 */
public sealed class ChappeException extends RuntimeException {

    protected ChappeException(String message) {
        super(message);
    }

    protected ChappeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Malformed request or request exceeding configured limits.
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
     * Error raised by a {@link Handler} during processing.
     */
    public static final class HandlerException extends ChappeException {

        public HandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Error related to the server lifecycle (bind, start, stop).
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
