package io.vidocq.chappe.api;

/**
 * Handler invoked by the router for a gRPC call.
 * <p>
 * Executed on a virtual thread dedicated to the HTTP/2 stream. The handler must
 * orchestrate the lifecycle via {@link GrpcCall}: {@link GrpcCall#receive()},
 * {@link GrpcCall#send(byte[])}, then {@link GrpcCall#complete(int, String)}.
 * <p>
 * If the handler throws an exception and has not called {@code complete}, the
 * transport layer automatically emits {@code grpc-status: 13 (INTERNAL)}.
 */
@FunctionalInterface
public interface GrpcHandler {

    void handle(GrpcCall call) throws Exception;
}
