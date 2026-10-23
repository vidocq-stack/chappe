/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.http.grpc;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vidocq.chappe.api.GrpcCall;
import io.vidocq.chappe.api.GrpcHandler;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.http.h2.Http2Stream;

/**
 * The handler execution loop shared by {@link GrpcCallImpl} and {@link GrpcWebCallImpl}: the
 * handler runs, a call it leaves open is completed, and an optional deadline watchdog ends a call
 * that outlives its {@code grpc-timeout}. The CAS on the call's {@code completed} flag guarantees
 * that at most one final status is emitted.
 */
final class GrpcCallRunner {

    /** Header carrying the codec of a message body. */
    static final String GRPC_ENCODING = "grpc-encoding";

    /** Codecs advertised by the server in {@code grpc-accept-encoding}. */
    static final String SERVER_ACCEPT_ENCODING = "identity, gzip";

    private GrpcCallRunner() {}

    /** The message of the UNIMPLEMENTED status sent for a request codec the server cannot read. */
    static String unsupportedEncodingMessage(String clientEncoding) {
        return GRPC_ENCODING + " '" + clientEncoding + "' not supported (accepted: " + SERVER_ACCEPT_ENCODING + ")";
    }

    /** Completes {@code call}; a lost connection leaves nothing to report it to. */
    static void completeQuietly(GrpcCall call, int status, String message) {
        try {
            call.complete(status, message);
        } catch (IOException _) {
            // lost connection: nothing more can reach the client
        }
    }

    /**
     * Runs {@code handler} on {@code call}. A handler that returns without completing the call
     * completes it with OK; one that throws completes it with INTERNAL and the exception message.
     * <p>
     * With a deadline ({@code deadlineNanoTime >= 0}), a watchdog virtual thread runs in parallel:
     * on expiry it cancels the stream (unblocking {@link GrpcCall#receive()}) and then attempts
     * {@link GrpcCall#complete} with {@link GrpcStatus#DEADLINE_EXCEEDED}.
     *
     * @param watchdogName the name prefix of the watchdog thread, followed by the stream id
     */
    static void run(
            GrpcCall call,
            AtomicBoolean completed,
            Http2Stream stream,
            long deadlineNanoTime,
            String watchdogName,
            GrpcHandler handler) {
        Thread watchdog =
                deadlineNanoTime >= 0L ? startWatchdog(call, completed, stream, deadlineNanoTime, watchdogName) : null;
        try {
            handler.handle(call);
            if (!completed.get()) {
                call.complete(GrpcStatus.OK, "");
            }
        } catch (Exception e) {
            if (!completed.get()) {
                completeQuietly(call, GrpcStatus.INTERNAL, String.valueOf(e.getMessage()));
            }
        } finally {
            if (watchdog != null) watchdog.interrupt();
        }
    }

    private static Thread startWatchdog(
            GrpcCall call, AtomicBoolean completed, Http2Stream stream, long deadline, String watchdogName) {
        return Thread.ofVirtual()
                .name(watchdogName + stream.streamId())
                .start(() -> enforceDeadline(call, completed, stream, deadline));
    }

    /** Waits for the deadline, then ends a call still open; an interrupt means the handler is done. */
    private static void enforceDeadline(GrpcCall call, AtomicBoolean completed, Http2Stream stream, long deadline) {
        try {
            long sleepNanos = deadline - System.nanoTime();
            if (sleepNanos > 0L) Thread.sleep(Duration.ofNanos(sleepNanos));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        if (completed.get()) return;
        // Cancel the stream first: it unblocks a handler stuck in receive() and toggles
        // isCancelled() for polling loops.
        stream.cancel();
        completeQuietly(call, GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
    }
}
