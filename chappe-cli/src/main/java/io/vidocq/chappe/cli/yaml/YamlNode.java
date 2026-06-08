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
package io.vidocq.chappe.cli.yaml;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * YAML tree resulting from parsing — sealed across three variants.
 * Supported subset: nested maps, lists (block and inline), scalars
 * (string, int, bool). No anchors, no tags, no multiline values.
 */
public sealed interface YamlNode {

    /** Ordered map preserving insertion order. */
    record Map(LinkedHashMap<String, YamlNode> entries) implements YamlNode {
        public Optional<YamlNode> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        public Optional<String> string(String key) {
            return get(key).flatMap(n -> n instanceof Scalar s ? Optional.of(s.value()) : Optional.empty());
        }

        public Optional<Integer> integer(String key) {
            return string(key).map(s -> {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
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

    /** Ordered sequence (list). */
    record Seq(java.util.List<YamlNode> items) implements YamlNode {
        public java.util.List<String> asStringList() {
            return items.stream()
                    .map(n -> n instanceof Scalar s ? s.value() : "")
                    .toList();
        }
    }

    /** Text scalar (int/bool conversions are done through typed accessors). */
    record Scalar(String value) implements YamlNode {}
}
