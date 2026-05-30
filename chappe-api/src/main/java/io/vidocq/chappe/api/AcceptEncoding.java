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
@SuppressWarnings("StringSplitter") // Les empty strings sont filtrées explicitement plus bas
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
            String t = token.trim();
            if (t.isEmpty()) continue;
            int semi = t.indexOf(';');
            String name;
            double q = 1.0;
            if (semi < 0) {
                name = t;
            } else {
                name = t.substring(0, semi).trim();
                String params = t.substring(semi + 1);
                for (String p : params.split(";")) {
                    String pt = p.trim();
                    if (pt.isEmpty()) continue;
                    int eq = pt.indexOf('=');
                    if (eq < 0) continue;
                    String k = pt.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String v = pt.substring(eq + 1).trim();
                    if (k.equals("q")) {
                        try {
                            q = Double.parseDouble(v);
                        } catch (NumberFormatException _) {
                            q = 0.0;
                        }
                    }
                }
            }
            if (!name.isEmpty()) {
                out.add(new Entry(name.toLowerCase(Locale.ROOT), q));
            }
        }
        return out;
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
