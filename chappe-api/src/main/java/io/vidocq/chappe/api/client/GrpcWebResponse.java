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
package io.vidocq.chappe.api.client;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@link GrpcWebClient#unary} call or of the last iteration of a
 * {@link GrpcWebClient#serverStream}.
 *
 * @param messages decoded payloads (already deframed from the 5-byte prefix and Base64-decoded
 *                 in TEXT mode)
 * @param status   {@code grpc-status} extracted from the inline trailer frame (0 = OK)
 * @param message  {@code grpc-message} extracted from the trailer frame (may be null/empty
 *                 if OK or if the server did not send it)
 * @param trailers all parsed trailers (lowercase names) for access to custom
 *                 metadata — includes {@code grpc-status}/{@code grpc-message}
 */
public record GrpcWebResponse(List<byte[]> messages, int status, String message, Map<String, String> trailers) {

    /** {@code true} if {@code grpc-status == 0}. */
    public boolean isOk() {
        return status == 0;
    }

    /** First message received; convenient for unary calls. */
    public byte[] firstMessage() {
        if (messages.isEmpty()) return null;
        return messages.get(0);
    }
}
