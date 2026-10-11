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

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown by a request body read that would go past the server's {@link ServerConfig#maxRequestSize()
 * maxRequestSize}. A handler can tell it from a cut or malformed body and answer {@code 413}. The
 * body is unusable afterwards: over HTTP/1.1 the connection is closed after the response, over
 * HTTP/2 the stream is reset.
 */
public final class RequestBodyTooLargeException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long limit;

    /** @param limit the cap the body went past, in bytes */
    public RequestBodyTooLargeException(long limit) {
        super("Request body exceeds the limit of " + limit + " bytes");
        this.limit = limit;
    }

    /** The cap the body went past, in bytes. */
    public long limit() {
        return limit;
    }
}
