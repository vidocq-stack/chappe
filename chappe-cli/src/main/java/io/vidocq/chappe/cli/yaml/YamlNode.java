package io.vidocq.chappe.cli.yaml;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Arbre YAML résultant du parsing — sealed sur trois variantes.
 * Subset supporté : maps imbriquées, listes (bloc et inline), scalaires
 * (string, int, bool). Pas d'anchors, pas de tags, pas de multilines.
 */
public sealed interface YamlNode {

    /** Map ordonnée préservant l'ordre d'insertion. */
    record Map(LinkedHashMap<String, YamlNode> entries) implements YamlNode {
        public Optional<YamlNode> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        public Optional<String> string(String key) {
            return get(key).flatMap(n -> n instanceof Scalar s ? Optional.of(s.value()) : Optional.empty());
        }

        public Optional<Integer> integer(String key) {
            return string(key).map(s -> {
                try { return Integer.parseInt(s.trim()); }
                catch (NumberFormatException e) { return null; }
            });
        }

        public Optional<Boolean> bool(String key) {
            return string(key).map(s -> {
                String v = s.trim().toLowerCase(java.util.Locale.ROOT);
                if (v.equals("true") || v.equals("yes") || v.equals("on")) return Boolean.TRUE;
                if (v.equals("false") || v.equals("no") || v.equals("off")) return Boolean.FALSE;
                return null;
            });
        }

        public Optional<Map> map(String key) {
            return get(key).flatMap(n -> n instanceof Map m ? Optional.of(m) : Optional.empty());
        }

        public Optional<java.util.List<String>> stringList(String key) {
            return get(key).flatMap(n -> n instanceof Seq l ? Optional.of(l.asStringList()) : Optional.empty());
        }
    }

    /** Séquence ordonnée (liste). */
    record Seq(java.util.List<YamlNode> items) implements YamlNode {
        public java.util.List<String> asStringList() {
            return items.stream()
                    .map(n -> n instanceof Scalar s ? s.value() : "")
                    .toList();
        }
    }

    /** Scalaire textuel (les conversions int/bool se font via accesseurs typés). */
    record Scalar(String value) implements YamlNode {}
}
