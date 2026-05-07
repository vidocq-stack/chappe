package io.vidocq.chappe.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parsing et négociation du header {@code Accept-Encoding} (RFC 9110, §12.5.3).
 *
 * <p>Subset géré : codings nommés (gzip, br, deflate, identity, …), q-values
 * ({@code ;q=0}…{@code ;q=1}), wildcard {@code *}.</p>
 */
public final class AcceptEncoding {

    private AcceptEncoding() {}

    /** Une entrée du header : nom du coding (lowercase) + q-value. */
    public record Entry(String name, double qvalue) {}

    /**
     * Parse un header {@code Accept-Encoding}. Retourne la liste des entrées
     * dans l'ordre où elles apparaissent (pas de tri, le caller décide).
     *
     * @param header valeur brute du header, ou {@code null} (retourne liste vide)
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
                        try { q = Double.parseDouble(v); }
                        catch (NumberFormatException _) { q = 0.0; }
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
     * Indique si un coding particulier est acceptable par le client.
     *
     * <p>Règles :</p>
     * <ul>
     *   <li>{@code header == null} : seul {@code identity} est acceptable.</li>
     *   <li>Une entrée {@code name;q=0} interdit ce coding.</li>
     *   <li>Une entrée explicite {@code name} (q&gt;0) l'autorise — prioritaire sur le wildcard.</li>
     *   <li>Wildcard {@code *} : autorise les codings non listés (selon son q-value).</li>
     *   <li>{@code identity} est implicitement acceptable sauf {@code identity;q=0} ou {@code *;q=0}.</li>
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
