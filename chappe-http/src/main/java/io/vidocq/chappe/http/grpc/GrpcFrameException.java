package io.vidocq.chappe.http.grpc;

import java.io.IOException;

/** gRPC framing error (invalid format, excessive size, unsupported compression). */
public final class GrpcFrameException extends IOException {

    public GrpcFrameException(String message) {
        super(message);
    }
}
