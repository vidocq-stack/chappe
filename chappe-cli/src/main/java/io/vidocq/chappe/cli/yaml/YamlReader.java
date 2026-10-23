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

    /** Quote state outside any quoted string. */
    private static final char NO_QUOTE = 0;
    /** Prefix of a block list item that carries a value on its line. */
    private static final String LIST_ITEM_PREFIX = "- ";
    /** A block list item with nothing after the dash: its value is the indented block below. */
    private static final String BARE_LIST_ITEM = "-";

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
        int rootIndent = lines.getFirst().indent;
        YamlNode.Map root = parseMap(c, rootIndent);
        // Only a line indented less than the first one ends the root map before the last line.
        Line dedented = c.peek();
        if (dedented != null) {
            throw unexpectedIndentation(dedented, rootIndent);
        }
        return root;
    }

    // -- Tokenization: line -> (lineNo, indent, content) --

    private record Line(int lineNo, int indent, String content) {}

    private static List<Line> tokenize(String input) {
        List<Line> out = new ArrayList<>();
        String[] raw = input.split("\\r?\\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            int lineNo = i + 1;
            int indent = indentOf(line, lineNo);
            String body = stripComment(line.substring(indent), lineNo, indent);
            if (!body.isBlank()) {
                out.add(new Line(lineNo, indent, body.stripTrailing()));
            }
        }
        return out;
    }

    /** Number of leading spaces; a tab right after them is rejected. */
    private static int indentOf(String line, int lineNo) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent < line.length() && line.charAt(indent) == '\t') {
            throw new YamlParseException(lineNo, indent + 1, "tab indentation forbidden — use spaces only");
        }
        return indent;
    }

    /** Strip {@code # …} from the line content, preserving quoted strings. */
    private static String stripComment(String s, int lineNo, int indentCols) {
        int n = s.length();
        char quote = NO_QUOTE;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            // A comment starts at a '#' outside quotes that opens the line or follows whitespace.
            if (quote == NO_QUOTE && c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return s.substring(0, i);
            }
            quote = quoteStateAfter(quote, c);
        }
        if (quote != NO_QUOTE) {
            throw new YamlParseException(lineNo, indentCols + n + 1, "unterminated quoted string");
        }
        return s;
    }

    /**
     * Quote state after reading {@code c}: the quote character that opened the current string, or
     * {@link #NO_QUOTE} outside quotes.
     */
    private static char quoteStateAfter(char quote, char c) {
        if (quote != NO_QUOTE) {
            return c == quote ? NO_QUOTE : quote;
        }
        return c == '"' || c == '\'' ? c : NO_QUOTE;
    }

    // -- Cursor over the line list --

    private static final class Cursor {
        private final List<Line> lines;
        private int pos;

        Cursor(List<Line> lines) {
            this.lines = lines;
        }

        /** The next line, or {@code null} at the end of the document. */
        Line peek() {
            return pos < lines.size() ? lines.get(pos) : null;
        }

        Line next() {
            return lines.get(pos++);
        }
    }

    // -- Recursive parser --

    private static YamlNode.Map parseMap(Cursor c, int indent) {
        LinkedHashMap<String, YamlNode> entries = new LinkedHashMap<>();
        while (mapContinues(c.peek(), indent)) {
            Line line = c.next();
            int colonAt = findUnquotedColon(line.content);
            if (colonAt < 0) {
                throw new YamlParseException(line.lineNo, line.indent + 1, "missing ':' separator in map entry");
            }
            String key = line.content.substring(0, colonAt).trim();
            if (key.isEmpty()) {
                throw new YamlParseException(line.lineNo, line.indent + 1, "empty key");
            }
            String rest = line.content.substring(colonAt + 1).trim();
            YamlNode value = rest.isEmpty()
                    ? parseBlockChild(c, indent)
                    : parseInlineValue(rest, line.lineNo, line.indent + colonAt + 2);
            entries.put(unquote(key, line.lineNo, line.indent + 1), value);
        }
        return new YamlNode.Map(entries);
    }

    /**
     * Whether {@code line} is the next entry of the map indented by {@code indent}: {@code false} at the end of
     * the document or on a dedent, an exception on a deeper line or a list item.
     */
    private static boolean mapContinues(Line line, int indent) {
        if (line == null || line.indent < indent) return false;
        if (line.indent > indent) {
            throw unexpectedIndentation(line, indent);
        }
        if (isListItem(line.content)) {
            throw new YamlParseException(
                    line.lineNo, line.indent + 1, "list item not expected here — looks like a map context");
        }
        return true;
    }

    private static YamlNode.Seq parseList(Cursor c, int indent) {
        List<YamlNode> items = new ArrayList<>();
        while (listContinues(c.peek(), indent)) {
            Line line = c.next();
            String rest =
                    line.content.length() <= 2 ? "" : line.content.substring(2).trim();
            items.add(
                    rest.isEmpty() ? parseBlockChild(c, indent) : parseInlineValue(rest, line.lineNo, line.indent + 3));
        }
        return new YamlNode.Seq(items);
    }

    /**
     * Whether {@code line} is the next item of the list indented by {@code indent}: {@code false} at the end of
     * the document, on a dedent or on a line that is not a list item, an exception on a deeper line.
     */
    private static boolean listContinues(Line line, int indent) {
        if (line == null || line.indent < indent) return false;
        if (line.indent > indent) {
            throw new YamlParseException(
                    line.lineNo, line.indent + 1, "unexpected indentation in list (expected " + indent + ")");
        }
        return isListItem(line.content);
    }

    private static boolean isListItem(String content) {
        return content.startsWith(LIST_ITEM_PREFIX) || content.equals(BARE_LIST_ITEM);
    }

    /**
     * Value of a key or a list item with nothing after it on its line: the more indented block below (a list or a
     * map), or an empty scalar when there is none.
     */
    private static YamlNode parseBlockChild(Cursor c, int parentIndent) {
        Line child = c.peek();
        if (child == null || child.indent <= parentIndent) {
            return new YamlNode.Scalar("");
        }
        return isListItem(child.content) ? parseList(c, child.indent) : parseMap(c, child.indent);
    }

    /** Value written on the same line as its key or dash: an inline list or a scalar. */
    private static YamlNode parseInlineValue(String rest, int lineNo, int colHint) {
        return rest.startsWith("[")
                ? parseInlineList(rest, lineNo, colHint)
                : new YamlNode.Scalar(unquote(rest, lineNo, colHint));
    }

    private static YamlParseException unexpectedIndentation(Line line, int expected) {
        return new YamlParseException(
                line.lineNo,
                line.indent + 1,
                "unexpected indentation (expected " + expected + ", got " + line.indent + ")");
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
        char quote = NO_QUOTE;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == NO_QUOTE && c == ',') {
                out.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(c);
                quote = quoteStateAfter(quote, c);
            }
        }
        if (quote != NO_QUOTE) {
            throw new YamlParseException(lineNo, colHint, "unterminated quoted string in inline list");
        }
        if (!buf.isEmpty()) out.add(buf.toString());
        return out;
    }

    /** First {@code ':'} not inside quotes, or {@code -1}. */
    private static int findUnquotedColon(String s) {
        char quote = NO_QUOTE;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == NO_QUOTE && c == ':') return i;
            quote = quoteStateAfter(quote, c);
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
