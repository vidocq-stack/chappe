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

import java.util.HashMap;
import java.util.Map;

/**
 * Cache of standard HTTP status codes — O(1) access.
 */
final class StatusCodeCache {

    private static final Map<Integer, StatusCode.Standard> CACHE;

    static {
        var map = new HashMap<Integer, StatusCode.Standard>();
        // Force initialization of StatusCode constants,
        // then index them by code.
        var constants = new StatusCode[] {
            StatusCode.OK,
            StatusCode.CREATED,
            StatusCode.ACCEPTED,
            StatusCode.NO_CONTENT,
            StatusCode.MOVED_PERMANENTLY,
            StatusCode.FOUND,
            StatusCode.NOT_MODIFIED,
            StatusCode.TEMPORARY_REDIRECT,
            StatusCode.PERMANENT_REDIRECT,
            StatusCode.BAD_REQUEST,
            StatusCode.UNAUTHORIZED,
            StatusCode.FORBIDDEN,
            StatusCode.NOT_FOUND,
            StatusCode.METHOD_NOT_ALLOWED,
            StatusCode.NOT_ACCEPTABLE,
            StatusCode.REQUEST_TIMEOUT,
            StatusCode.CONFLICT,
            StatusCode.GONE,
            StatusCode.LENGTH_REQUIRED,
            StatusCode.PAYLOAD_TOO_LARGE,
            StatusCode.URI_TOO_LONG,
            StatusCode.UNSUPPORTED_MEDIA_TYPE,
            StatusCode.TOO_MANY_REQUESTS,
            StatusCode.INTERNAL_SERVER_ERROR,
            StatusCode.NOT_IMPLEMENTED,
            StatusCode.BAD_GATEWAY,
            StatusCode.SERVICE_UNAVAILABLE,
            StatusCode.GATEWAY_TIMEOUT
        };
        for (var sc : constants) {
            if (sc instanceof StatusCode.Standard std) {
                map.put(std.code(), std);
            }
        }
        CACHE = Map.copyOf(map);
    }

    private StatusCodeCache() {}

    static StatusCode.Standard get(int code) {
        return CACHE.get(code);
    }
}
