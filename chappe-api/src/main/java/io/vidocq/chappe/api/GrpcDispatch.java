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

import java.util.Objects;

/**
 * Marker response telling the HTTP/2 transport that a gRPC call must be dispatched
 * to a {@link GrpcHandler}.
 * <p>
 * Built by {@link Router.Builder#grpc(String, GrpcHandler)} and recognized by
 * {@code chappe-http} via {@code instanceof}. A normal application does not need
 * to instantiate it directly.
 */
public final class GrpcDispatch implements Response {

    private final GrpcHandler handler;

    public GrpcDispatch(GrpcHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public GrpcHandler handler() {
        return handler;
    }

    @Override
    public StatusCode status() {
        return StatusCode.OK;
    }

    @Override
    public Headers headers() {
        return Headers.empty();
    }

    @Override
    public Body body() {
        return Body.empty();
    }
}
