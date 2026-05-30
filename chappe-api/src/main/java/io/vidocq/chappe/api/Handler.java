package io.vidocq.chappe.api;

/**
 * HTTP request handler — functional interface {@code Request → Response}.
 * <p>
 * This is Chappe's central contract. Each handler receives an immutable request
 * and returns a response.
 *
 * <pre>{@code
 * Handler hello = request -> Response.ok("Hello, Chappe!");
 * }</pre>
 */
@FunctionalInterface
public interface Handler {

    /**
     * Handles an HTTP request and returns a response.
     *
     * @param request the incoming request (read-only)
     * @return the response to send to the client
     * @throws Exception if processing fails (it will be converted to 500 by the server)
     */
    Response handle(Request request) throws Exception;
}
