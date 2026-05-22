package io.vidocq.chappe.api;

/**
 * Handler invoqué par le routeur pour un appel gRPC.
 * <p>
 * Exécuté sur un virtual thread dédié au stream HTTP/2. Le handler doit
 * orchestrer le cycle de vie via {@link GrpcCall} : {@link GrpcCall#receive()},
 * {@link GrpcCall#send(byte[])}, puis {@link GrpcCall#complete(int, String)}.
 * <p>
 * Si le handler lève une exception et n'a pas appelé {@code complete}, la couche
 * transport émet automatiquement {@code grpc-status: 13 (INTERNAL)}.
 */
@FunctionalInterface
public interface GrpcHandler {

    void handle(GrpcCall call) throws Exception;
}
