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
