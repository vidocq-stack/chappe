package io.vidocq.chappe.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Represents an in-progress gRPC call on the server side.
 * <p>
 * Blocking synchronous API — a gRPC call runs on a dedicated virtual thread.
 * Covers all 4 modes: unary, server-streaming, client-streaming, bidi-streaming.
 * <p>
 * Typical lifecycle:
 * <pre>{@code
 * // unary
 * byte[] req = call.receive();
 * byte[] resp = ...;
 * call.send(resp);
 * call.complete(GrpcStatus.OK, "");
 *
 * // server-streaming
 * byte[] req = call.receive();
 * for (var msg : stream) call.send(serialize(msg));
 * call.complete(GrpcStatus.OK, "");
 *
 * // client-streaming
 * byte[] msg;
 * while ((msg = call.receive()) != null) accumulate(msg);
 * call.send(reduce());
 * call.complete(GrpcStatus.OK, "");
 *
 * // bidi-streaming (the handler can spawn another virtual thread for writes)
 * }</pre>
 *
 * The bytes exchanged are opaque to Chappe — serialisation (protobuf or other)
 * is the extension's responsibility.
 */
public interface GrpcCall {

    /**
     * Reads the next message from the client.
     *
     * @return the message bytes, or {@code null} if the client half-closed (END_STREAM)
     * @throws IOException if I/O or framing fails
     */
    byte[] receive() throws IOException;

    /**
     * Sends a message to the client. Blocks if HTTP/2 flow control is saturated.
     * <p>
     * The first call to {@code send} triggers the sending of the initial server headers
     * if not yet done ({@code :status 200}, {@code content-type: application/grpc}).
     */
    void send(byte[] message) throws IOException;

    /**
     * Terminates the call with a final gRPC status.
     * <p>
     * Emits HTTP/2 trailers with {@code grpc-status} (and {@code grpc-message} if non-empty),
     * with END_STREAM. If no {@link #send} has been called and the initial headers have not
     * been emitted, merges everything into a trailers-only HEADERS frame (RFC §8.1).
     */
    void complete(int grpcStatus, String message) throws IOException;

    /** Headers received from the client at stream start (before DATA frames). */
    Headers metadata();

    /**
     * Adds a header to the initial response (Initial-Metadata).
     * Must be called <b>before</b> the first {@link #send}.
     * @throws IllegalStateException if the initial headers have already been emitted
     */
    void addHeader(String name, String value);

    /**
     * Adds an additional trailer. Must be called before {@link #complete}.
     * @throws IllegalStateException if {@code complete} has already been called
     */
    void addTrailer(String name, String value);

    /** {@code true} if the client cancelled the stream (RST_STREAM or disconnect). */
    boolean isCancelled();

    /** Negotiated content-type, e.g. {@code application/grpc}, {@code application/grpc+proto}. */
    String contentType();

    /**
     * Enables compression of outgoing messages (gRPC RFC §"Compression").
     * <p>
     * Must be called <b>before</b> the first {@link #send(byte[])} otherwise the
     * initial headers will already have been emitted. The transport layer then adds:
     * <ul>
     *   <li>{@code grpc-encoding: <encoding>} to the initial server headers</li>
     *   <li>the {@code compressed=1} flag in the 5-byte prefix of each sent message</li>
     * </ul>
     * On the receive side, the server always advertises {@code grpc-accept-encoding: identity,gzip}
     * and automatically decompresses incoming messages compressed according to the
     * client's {@code grpc-encoding}.
     *
     * @param encoding codec — currently {@code "identity"} (no-op) or {@code "gzip"}
     * @throws IllegalStateException         if the initial headers have already been emitted
     * @throws UnsupportedOperationException if {@code encoding} is not supported
     */
    void useResponseEncoding(String encoding);

    /**
     * Deadline propagated by the client via the {@code grpc-timeout} header (gRPC RFC §"Requests").
     * <p>
     * If present, the Chappe transport layer automatically arms a watchdog: on expiry,
     * the stream is cancelled (unblocks pending {@link #receive()} calls) and a
     * {@code grpc-status: 4 (DEADLINE_EXCEEDED)} trailer is emitted to the client if the
     * handler has not yet called {@link #complete(int, String)}.
     * <p>
     * The handler can read this value to adapt its behaviour — for example to shorten
     * the deadline of a downstream call.
     *
     * @return remaining duration since {@link System#nanoTime()}, or {@link Optional#empty()} if
     *         the client did not send a {@code grpc-timeout}. May be {@link Duration#ZERO}
     *         if the deadline has already expired at the time of the call.
     */
    Optional<Duration> deadline();
}
