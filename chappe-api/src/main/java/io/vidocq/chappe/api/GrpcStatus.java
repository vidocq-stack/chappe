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
 * Standardized gRPC status codes.
 * <p>
 * Reference: <a href="https://grpc.io/docs/guides/status-codes/">grpc.io status codes</a>
 * and <a href="https://github.com/grpc/grpc/blob/master/doc/statuscodes.md">grpc/grpc status codes</a>.
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
