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

/**
 * Parsing and negotiation of the {@code Accept-Encoding} header (RFC 9110, §12.5.3).
 *
 * <p>Supported subset: named codings (gzip, br, deflate, identity, …), q-values
 * ({@code ;q=0}…{@code ;q=1}), wildcard {@code *}.</p>
 */
@SuppressWarnings("StringSplitter") // empty elements are skipped explicitly below
public final class AcceptEncoding {

    private AcceptEncoding() {}

    /** A header entry: coding name (lowercase) + q-value. */
    public record Entry(String name, double qvalue) {}

    /**
     * Parses an {@code Accept-Encoding} header. Returns the list of entries
     * in the order they appear (no sorting — the caller decides).
     *
     * @param header raw header value, or {@code null} (returns empty list)
     */
    public static List<Entry> parse(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<Entry> out = new ArrayList<>();
        for (String token : header.split(",")) {
            Entry entry = parseEntry(token.trim());
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /** One trimmed element of the list; {@code null} when it names no coding (an empty element). */
    private static Entry parseEntry(String element) {
        int semi = element.indexOf(';');
        String name = semi < 0 ? element : element.substring(0, semi).trim();
        if (name.isEmpty()) return null;
        double q = semi < 0 ? 1.0 : qvalue(element.substring(semi + 1));
        return new Entry(name.toLowerCase(Locale.ROOT), q);
    }

    /** The q-value of the parameters after a coding name: the last {@code q} wins, 1 when absent. */
    private static double qvalue(String params) {
        double q = 1.0;
        for (String p : params.split(";")) {
            String pt = p.trim();
            int eq = pt.indexOf('=');
            if (eq >= 0 && pt.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals("q")) {
                q = parseQvalue(pt.substring(eq + 1).trim());
            }
        }
        return q;
    }

    /** A q-value that does not parse counts as 0: the coding is then refused. */
    private static double parseQvalue(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException _) {
            return 0.0;
        }
    }

    /**
     * Indicates whether a particular coding is acceptable to the client.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>{@code header == null}: only {@code identity} is acceptable.</li>
     *   <li>An entry {@code name;q=0} forbids that coding.</li>
     *   <li>An explicit entry {@code name} (q&gt;0) allows it — takes priority over the wildcard.</li>
     *   <li>Wildcard {@code *}: allows codings not explicitly listed (according to its q-value).</li>
     *   <li>{@code identity} is implicitly acceptable unless {@code identity;q=0} or {@code *;q=0}.</li>
     * </ul>
     */
    public static boolean accepts(String header, String coding) {
        String c = coding.toLowerCase(Locale.ROOT);
        if (header == null || header.isBlank()) {
            return c.equals("identity");
        }
        List<Entry> entries = parse(header);
        Double explicit = null;
        Double wildcard = null;
        for (Entry e : entries) {
            if (e.name().equals(c)) explicit = e.qvalue();
            else if (e.name().equals("*")) wildcard = e.qvalue();
        }
        if (explicit != null) return explicit > 0.0;
        if (wildcard != null) return wildcard > 0.0;
        // identity is implicitly acceptable when not listed (RFC 9110 §12.5.3.2)
        return c.equals("identity");
    }
}
