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

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Supported HTTP protocol versions.
 */
public enum HttpVersion {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2");

    private static final Map<String, HttpVersion> LOOKUP =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(HttpVersion::wireFormat, v -> v));

    private final String wire;

    HttpVersion(String wire) {
        this.wire = wire;
    }

    /** Format as it appears on the wire (for example {@code "HTTP/1.1"}). */
    public String wireFormat() {
        return wire;
    }

    /**
     * Resolves an HTTP version from its wire representation.
     *
     * @param wire the wire format (for example {@code "HTTP/1.1"})
     * @return the matching constant
     * @throws IllegalArgumentException if the version is unknown
     */
    public static HttpVersion of(String wire) {
        var v = LOOKUP.get(wire);
        if (v == null) {
            throw new IllegalArgumentException("Unknown HTTP version: " + wire);
        }
        return v;
    }
}
