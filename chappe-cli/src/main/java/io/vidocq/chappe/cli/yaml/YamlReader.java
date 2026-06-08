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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * In-house mini YAML parser for {@code chappe-cli} configuration.
 *
 * <h2>Supported subset</h2>
 * <ul>
 *   <li>Nested maps (space indentation).</li>
 *   <li>Block lists ({@code - item}) and inline lists ({@code [a, b, c]}).</li>
 *   <li>Scalars: strings (quoted {@code "…"} / {@code '…'} or unquoted),
 *       integers, booleans ({@code true|false|yes|no|on|off}).</li>
 *   <li>{@code #} comments until end of line (outside quotes).</li>
 * </ul>
 *
 * <h2>Not supported</h2>
 * <ul>
 *   <li>Tabs in indentation (explicit rejection with line:col).</li>
 *   <li>Anchors {@code &}, aliases {@code *}, tags {@code !!type}.</li>
 *   <li>Multiline values {@code |} / {@code >}.</li>
 *   <li>Inline maps {@code {a: 1}}.</li>
 *   <li>Multiple documents ({@code ---}).</li>
 * </ul>
 */
public final class YamlReader {

    private YamlReader() {}

    /**
     * Parses the YAML document. Returns a {@link YamlNode.Map} (potentially empty).
     *
     * @throws YamlParseException if the document does not conform to the supported subset.
     */
    public static YamlNode parse(String input) {
        List<Line> lines = tokenize(input);
        if (lines.isEmpty()) return new YamlNode.Map(new LinkedHashMap<>());
        Cursor c = new Cursor(lines);
        return parseMap(c, lines.getFirst().indent);
    }

    // -- Tokenization: line -> (lineNo, indent, content) --

    private record Line(int lineNo, int indent, String content) {}

    private static List<Line> tokenize(String input) {
        List<Line> out = new ArrayList<>();
        String[] raw = input.split("\\r?\\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            int lineNo = i + 1;
            int indent = 0;
            while (indent < line.length()) {
                char ch = line.charAt(indent);
                if (ch == ' ') {
                    indent++;
                    continue;
                }
                if (ch == '\t') {
                    throw new YamlParseException(lineNo, indent + 1, "tab indentation forbidden — use spaces only");
                }
                break;
            }
            String body = stripComment(line.substring(indent), lineNo, indent);
            if (body.isBlank()) continue;
            out.add(new Line(lineNo, indent, body.stripTrailing()));
        }
        return out;
    }

    /** Strip {@code # …} from the line content, preserving quoted strings. */
    private static String stripComment(String s, int lineNo, int indentCols) {
        int n = s.length();
        char quote = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#') {
                // Comment if `#` is at start or preceded by whitespace.
                if (i == 0 || Character.isWhitespace(s.charAt(i - 1))) {
                    return s.substring(0, i);
                }
            }
        }
        if (quote != 0) {
            throw new YamlParseException(lineNo, indentCols + n + 1, "unterminated quoted string");
        }
        return s;
    }

    // -- Cursor over the line list --

    private static final class Cursor {
        private final List<Line> lines;
        private int pos;

        Cursor(List<Line> lines) {
            this.lines = lines;
        }

        Line peek() {
            return pos < lines.size() ? lines.get(pos) : null;
        }

        Line next() {
            return lines.get(pos++);
        }

        boolean hasMore() {
            return pos < lines.size();
        }
    }

    // -- Recursive parser --

    private static YamlNode.Map parseMap(Cursor c, int indent) {
        LinkedHashMap<String, YamlNode> entries = new LinkedHashMap<>();
        while (c.hasMore()) {
            Line line = Objects.requireNonNull(c.peek()); // guaranteed non-null by hasMore()
            if (line.indent < indent) break;
            if (line.indent > indent) {
                throw new YamlParseException(
                        line.lineNo,
                        line.indent + 1,
                        "unexpected indentation (expected " + indent + ", got " + line.indent + ")");
            }
            if (line.content.startsWith("- ")) {
                throw new YamlParseException(
                        line.lineNo, line.indent + 1, "list item not expected here — looks like a map context");
            }
            c.next();

            int colonAt = findUnquotedColon(line.content);
            if (colonAt < 0) {
                throw new YamlParseException(line.lineNo, line.indent + 1, "missing ':' separator in map entry");
            }
            String key = line.content.substring(0, colonAt).trim();
            if (key.isEmpty()) {
                throw new YamlParseException(line.lineNo, line.indent + 1, "empty key");
            }
            String rest = line.content.substring(colonAt + 1).trim();

            YamlNode value;
            if (rest.isEmpty()) {
                // Block child: map or list, with greater indentation.
                Line peek = c.peek();
                if (peek == null || peek.indent <= indent) {
                    value = new YamlNode.Scalar("");
                } else if (peek.content.startsWith("- ")) {
                    value = parseList(c, peek.indent);
                } else {
                    value = parseMap(c, peek.indent);
                }
            } else if (rest.startsWith("[")) {
                value = parseInlineList(rest, line.lineNo, line.indent + colonAt + 2);
            } else {
                value = new YamlNode.Scalar(unquote(rest, line.lineNo, line.indent + colonAt + 2));
            }
            entries.put(unquote(key, line.lineNo, line.indent + 1), value);
        }
        return new YamlNode.Map(entries);
    }

    private static YamlNode.Seq parseList(Cursor c, int indent) {
        List<YamlNode> items = new ArrayList<>();
        while (c.hasMore()) {
            Line line = Objects.requireNonNull(c.peek()); // guaranteed non-null by hasMore()
            if (line.indent < indent) break;
            if (line.indent > indent) {
                throw new YamlParseException(
                        line.lineNo, line.indent + 1, "unexpected indentation in list (expected " + indent + ")");
            }
            if (!line.content.startsWith("- ") && !line.content.equals("-")) break;
            c.next();
            String rest =
                    line.content.length() <= 2 ? "" : line.content.substring(2).trim();
            if (rest.isEmpty()) {
                Line peek = c.peek();
                if (peek == null || peek.indent <= indent) {
                    items.add(new YamlNode.Scalar(""));
                } else if (peek.content.startsWith("- ")) {
                    items.add(parseList(c, peek.indent));
                } else {
                    items.add(parseMap(c, peek.indent));
                }
            } else if (rest.startsWith("[")) {
                items.add(parseInlineList(rest, line.lineNo, line.indent + 3));
            } else {
                items.add(new YamlNode.Scalar(unquote(rest, line.lineNo, line.indent + 3)));
            }
        }
        return new YamlNode.Seq(items);
    }

    private static YamlNode.Seq parseInlineList(String s, int lineNo, int colHint) {
        String t = s.trim();
        if (!t.startsWith("[") || !t.endsWith("]")) {
            throw new YamlParseException(lineNo, colHint, "invalid inline list — must start with '[' and end with ']'");
        }
        String inner = t.substring(1, t.length() - 1).trim();
        List<YamlNode> items = new ArrayList<>();
        if (inner.isEmpty()) return new YamlNode.Seq(items);
        // Split by ',' respecting quotes.
        List<String> parts = splitInlineListItems(inner, lineNo, colHint);
        for (String p : parts) {
            items.add(new YamlNode.Scalar(unquote(p.trim(), lineNo, colHint)));
        }
        return new YamlNode.Seq(items);
    }

    private static List<String> splitInlineListItems(String s, int lineNo, int colHint) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                buf.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                buf.append(c);
                continue;
            }
            if (c == ',') {
                out.add(buf.toString());
                buf.setLength(0);
                continue;
            }
            buf.append(c);
        }
        if (quote != 0) {
            throw new YamlParseException(lineNo, colHint, "unterminated quoted string in inline list");
        }
        if (!buf.isEmpty()) out.add(buf.toString());
        return out;
    }

    /** First {@code ':'} not inside quotes, or {@code -1}. */
    private static int findUnquotedColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == ':') return i;
        }
        return -1;
    }

    private static String unquote(String s, int lineNo, int colHint) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
            if (first == '"' || first == '\'') {
                throw new YamlParseException(lineNo, colHint, "unterminated quoted string");
            }
        }
        return s;
    }
}
