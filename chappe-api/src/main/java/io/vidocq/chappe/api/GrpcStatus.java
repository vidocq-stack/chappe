package io.vidocq.chappe.api;

/**
 * Codes de statut gRPC standardisés.
 * <p>
 * Référence : <a href="https://grpc.io/docs/guides/status-codes/">grpc.io status codes</a>
 * et <a href="https://github.com/grpc/grpc/blob/master/doc/statuscodes.md">grpc/grpc status codes</a>.
 */
public final class GrpcStatus {

    private GrpcStatus() {}

    public static final int OK = 0;
    public static final int CANCELLED = 1;
    public static final int UNKNOWN = 2;
    public static final int INVALID_ARGUMENT = 3;
    public static final int DEADLINE_EXCEEDED = 4;
    public static final int NOT_FOUND = 5;
    public static final int ALREADY_EXISTS = 6;
    public static final int PERMISSION_DENIED = 7;
    public static final int RESOURCE_EXHAUSTED = 8;
    public static final int FAILED_PRECONDITION = 9;
    public static final int ABORTED = 10;
    public static final int OUT_OF_RANGE = 11;
    public static final int UNIMPLEMENTED = 12;
    public static final int INTERNAL = 13;
    public static final int UNAVAILABLE = 14;
    public static final int DATA_LOSS = 15;
    public static final int UNAUTHENTICATED = 16;
}
