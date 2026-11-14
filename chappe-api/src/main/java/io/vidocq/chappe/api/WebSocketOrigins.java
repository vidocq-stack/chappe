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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.vidocq.chappe.api.internal.HeaderFields;

/**
 * The origins allowed to open the native WebSocket routes ({@link Router.Builder#webSocket}): the
 * defence against cross-site WebSocket hijacking, where a page of another site opens a WebSocket
 * that the browser authenticates with the user's cookies.
 * <p>
 * {@link #allows} decides, in this order:
 * <ol>
 *   <li>a handshake without {@code Origin} is allowed: it does not come from a browser, and
 *       hijacking needs one;</li>
 *   <li>{@link #any()} allows every origin;</li>
 *   <li>an {@code Origin} that is not {@code http(s)://host[:port]} is refused: the {@code null}
 *       origin of a sandboxed page or of a {@code file:} document among them;</li>
 *   <li>the server's own origin is allowed: the same host, ignoring case, and the same port. A
 *       {@code Host} without a port stands for the origin's scheme default, so the scheme never
 *       decides: a page served over HTTPS by a TLS-terminating proxy that keeps {@code Host} still
 *       reaches a server listening on plain HTTP;</li>
 *   <li>a {@linkplain #parse listed} origin is allowed: the same scheme, host and port;</li>
 *   <li>any other origin is refused.</li>
 * </ol>
 * Instances are immutable.
 */
public final class WebSocketOrigins {

    /** {@code host[:port]}: a registered name or an IPv4 address, or an IPv6 literal in brackets. */
    private static final String AUTHORITY = "(\\[[\\da-f:.]+\\]|[a-z\\d._~-]+)(?::(\\d{1,5}))?";

    private static final Pattern ORIGIN = Pattern.compile("(?i)(https?)://" + AUTHORITY);

    private static final Pattern HOST = Pattern.compile("(?i)" + AUTHORITY);

    private static final int MAX_PORT = 65_535;

    private static final WebSocketOrigins SAME_ORIGIN = new WebSocketOrigins(false, List.of());

    private static final WebSocketOrigins ANY = new WebSocketOrigins(true, List.of());

    /** An origin with a lower-case scheme and host, and its port: the scheme's default when it has none. */
    private record Origin(String scheme, String host, int port) {

        @Override
        public String toString() {
            return port == defaultPort(scheme) ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        }
    }

    private final boolean any;
    private final List<Origin> listed;

    private WebSocketOrigins(boolean any, List<Origin> listed) {
        this.any = any;
        this.listed = listed;
    }

    /** The default: the server's own origin only, and handshakes without {@code Origin}. */
    public static WebSocketOrigins sameOrigin() {
        return SAME_ORIGIN;
    }

    /** Every origin: the protection is off. */
    public static WebSocketOrigins any() {
        return ANY;
    }

    /**
     * The server's own origin plus the origins of a comma-separated list, for example
     * {@code https://admin.example.com, http://localhost:5173}. Entries are trimmed and empty ones
     * ignored; a {@code *} among them gives {@link #any()}.
     *
     * @param list the origins, or {@code *}; {@code null} or blank gives {@link #sameOrigin()}
     * @throws IllegalArgumentException if an entry is neither {@code *} nor {@code http(s)://host[:port]}:
     *         another scheme, a path, a query, a fragment, user info, or no host
     */
    public static WebSocketOrigins parse(String list) {
        if (list == null || list.isBlank()) {
            return SAME_ORIGIN;
        }
        boolean star = false;
        var origins = new ArrayList<Origin>();
        for (String raw : list.split(",")) {
            String entry = raw.trim();
            if ("*".equals(entry)) {
                star = true;
            } else if (!entry.isEmpty()) {
                origins.add(requireOrigin(entry));
            }
        }
        if (star) {
            return ANY;
        }
        return origins.isEmpty() ? SAME_ORIGIN : new WebSocketOrigins(false, List.copyOf(origins));
    }

    /**
     * Whether a handshake may open a WebSocket.
     *
     * @param origin the handshake's {@code Origin} header, {@code null} when it has none
     * @param host the handshake's {@code Host} header, {@code null} when it has none
     */
    public boolean allows(String origin, String host) {
        if (origin == null || any) {
            return true;
        }
        Origin parsed = parseOrigin(origin);
        if (parsed == null) {
            return false;
        }
        return isServerOrigin(parsed, host) || listed.contains(parsed);
    }

    /** Whether this policy allows every origin: {@link #any()}, or a {@link #parse parsed} list holding {@code *}. */
    public boolean allowsAny() {
        return any;
    }

    @Override
    public String toString() {
        if (any) {
            return "WebSocketOrigins[*]";
        }
        var text = new StringBuilder("WebSocketOrigins[same origin");
        listed.forEach(origin -> text.append(", ").append(origin));
        return text.append(']').toString();
    }

    private static Origin requireOrigin(String entry) {
        Origin origin = parseOrigin(entry);
        if (origin == null) {
            throw new IllegalArgumentException("Invalid WebSocket origin '" + HeaderFields.printable(entry)
                    + "': expected http(s)://host[:port] or *");
        }
        return origin;
    }

    /** {@code value} as an origin, or {@code null} when it is not {@code http(s)://host[:port]}. */
    private static Origin parseOrigin(String value) {
        Matcher m = ORIGIN.matcher(value);
        if (!m.matches()) {
            return null;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        int port = m.group(3) == null ? defaultPort(scheme) : Integer.parseInt(m.group(3));
        return port > MAX_PORT ? null : new Origin(scheme, m.group(2).toLowerCase(Locale.ROOT), port);
    }

    /** Whether {@code origin} is the server's: the host of {@code host}, and its port or the origin's scheme default. */
    private static boolean isServerOrigin(Origin origin, String host) {
        if (host == null) {
            return false;
        }
        Matcher m = HOST.matcher(host);
        if (!m.matches()) {
            return false;
        }
        int port = m.group(2) == null ? defaultPort(origin.scheme()) : Integer.parseInt(m.group(2));
        return origin.host().equalsIgnoreCase(m.group(1)) && origin.port() == port;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }
}
