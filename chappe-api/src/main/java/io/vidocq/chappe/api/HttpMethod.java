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

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standard HTTP methods defined by RFC 9110.
 */
public enum HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    CONNECT,
    OPTIONS,
    TRACE,
    PATCH;

    private static final Map<String, HttpMethod> LOOKUP = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(m -> m.name().toUpperCase(Locale.ROOT), m -> m));

    /**
     * Resolves an HTTP method from its textual representation.
     * Comparison is case-insensitive.
     *
     * @param method the method name (for example {@code "GET"})
     * @return the matching constant
     * @throws IllegalArgumentException if the method is unknown
     */
    public static HttpMethod of(String method) {
        var m = LOOKUP.get(method.toUpperCase(Locale.ROOT));
        if (m == null) {
            throw new IllegalArgumentException("Unknown HTTP method: " + method);
        }
        return m;
    }
}
