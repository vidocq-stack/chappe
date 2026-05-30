package io.vidocq.chappe.api.client;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@link GrpcWebClient#unary} call or of the last iteration of a
 * {@link GrpcWebClient#serverStream}.
 *
 * @param messages decoded payloads (already deframed from the 5-byte prefix and Base64-decoded
 *                 in TEXT mode)
 * @param status   {@code grpc-status} extracted from the inline trailer frame (0 = OK)
 * @param message  {@code grpc-message} extracted from the trailer frame (may be null/empty
 *                 if OK or if the server did not send it)
 * @param trailers all parsed trailers (lowercase names) for access to custom
 *                 metadata — includes {@code grpc-status}/{@code grpc-message}
 */
public record GrpcWebResponse(List<byte[]> messages, int status, String message, Map<String, String> trailers) {

    /** {@code true} if {@code grpc-status == 0}. */
    public boolean isOk() {
        return status == 0;
    }

    /** First message received; convenient for unary calls. */
    public byte[] firstMessage() {
        if (messages.isEmpty()) return null;
        return messages.get(0);
    }
}
