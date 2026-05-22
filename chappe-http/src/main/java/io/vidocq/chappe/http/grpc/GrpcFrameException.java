package io.vidocq.chappe.http.grpc;

import java.io.IOException;

/** Erreur de framing gRPC (format invalide, taille excessive, compression non supportée). */
public final class GrpcFrameException extends IOException {

    public GrpcFrameException(String message) {
        super(message);
    }
}
