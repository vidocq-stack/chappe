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
 * Chappe exception hierarchy — sealed to allow
 * exhaustive pattern matching.
 */
public sealed class ChappeException extends RuntimeException {

    protected ChappeException(String message) {
        super(message);
    }

    protected ChappeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Malformed request or request exceeding configured limits.
     */
    public static final class BadRequestException extends ChappeException {

        public BadRequestException(String message) {
            super(message);
        }

        public BadRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Error raised by a {@link Handler} during processing.
     */
    public static final class HandlerException extends ChappeException {

        public HandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Error related to the server lifecycle (bind, start, stop).
     */
    public static final class ServerException extends ChappeException {

        public ServerException(String message) {
            super(message);
        }

        public ServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
