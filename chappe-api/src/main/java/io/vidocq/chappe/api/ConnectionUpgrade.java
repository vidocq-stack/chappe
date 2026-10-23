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

import io.vidocq.chappe.api.internal.HeaderFields;

/**
 * Response telling the HTTP/1.1 transport to write {@code status} and {@code headers} (no
 * Content-Length, no Transfer-Encoding, no body) and then hand the connection to {@code handler}.
 * HTTP/2 answers 505 (RFC 9113 section 8.6 forbids the Upgrade mechanism).
 * <p>
 * Unlike {@link WebSocketUpgrade}, the transport neither checks nor adds any header: the
 * application chooses the status (normally {@link StatusCode#SWITCHING_PROTOCOLS}) and writes
 * {@code Upgrade} / {@code Connection} itself. No {@code Date}, {@code Server} or framing header
 * is added.
 * <p>
 * Deciding whether to upgrade is the caller's job: the transport performs the takeover for any
 * HTTP/1.x request, so refusing {@code Upgrade} on an HTTP/1.0 request (RFC 9110 §7.8: a server
 * must ignore it there) or checking the requested protocol is left to the code that builds this
 * response.
 * <p>
 * The status and headers are validated, and the headers copied, when the upgrade is built:
 * <ul>
 *   <li>a status code outside {@code 100-999}, or a reason phrase holding CR, LF or another
 *       char outside {@code HTAB / SP / VCHAR / obs-text} (RFC 9112 §4), throws
 *       {@link IllegalArgumentException};</li>
 *   <li>a name that is not an RFC 9110 §5.6.2 token, or a value holding anything but
 *       field-vchar, obs-text, SP and HTAB (RFC 9110 §5.5) — in particular CR or LF, or a char
 *       above {@code U+00FF} — throws {@link IllegalArgumentException}, so no header can be
 *       injected into the response head (the same rule the transport applies to trailers);</li>
 *   <li>{@code Content-Length} and {@code Transfer-Encoding} are dropped: the upgraded stream
 *       has no HTTP framing, and a 101 response must not carry them (RFC 9110 §8.6, RFC 9112
 *       §6.1).</li>
 * </ul>
 *
 * @param status  the status line to write, normally {@link StatusCode#SWITCHING_PROTOCOLS}
 * @param headers the response headers, written as is (framing headers dropped); the accessor
 *                returns the validated copy, without {@code Content-Length} /
 *                {@code Transfer-Encoding}
 * @param handler takes over the connection once the head is on the wire
 */
public record ConnectionUpgrade(StatusCode status, Headers headers, UpgradeHandler handler) implements Response {

    /**
     * Validates the status and the headers, and keeps a copy of the headers without the framing ones.
     *
     * @throws IllegalArgumentException if a header name or value is invalid (see class doc)
     */
    public ConnectionUpgrade {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(headers, "headers");
        if (status.code() < 100 || status.code() > 999) {
            throw new IllegalArgumentException("Invalid upgrade status code: " + status.code());
        }
        if (status.reason() == null || !HeaderFields.isFieldValue(status.reason())) {
            throw new IllegalArgumentException("Invalid upgrade reason phrase for status " + status.code());
        }
        var copy = Headers.builder();
        for (var entry : headers) {
            String name = entry.name();
            String value = entry.value();
            if (name == null || !HeaderFields.isToken(name)) {
                throw new IllegalArgumentException("Invalid upgrade header name: " + name);
            }
            if (value == null || !HeaderFields.isFieldValue(value)) {
                throw new IllegalArgumentException("Invalid value for upgrade header " + name);
            }
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            copy.add(name, value);
        }
        headers = copy.build();
    }

    @Override
    public Body body() {
        return Body.empty();
    }
}
