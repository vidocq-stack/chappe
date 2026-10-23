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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class YamlReaderTest {

    @Test
    void emptyDocumentParsesToEmptyMap() {
        YamlNode n = YamlReader.parse("");
        assertInstanceOf(YamlNode.Map.class, n);
        assertTrue(((YamlNode.Map) n).entries().isEmpty());
    }

    @Test
    void simpleScalars() {
        var m = (YamlNode.Map) YamlReader.parse("""
                port: 8080
                host: 0.0.0.0
                gzip: true
                """);
        assertEquals(8080, m.integer("port").orElseThrow());
        assertEquals("0.0.0.0", m.string("host").orElseThrow());
        assertTrue(m.bool("gzip").orElseThrow());
    }

    @Test
    void quotedStringsPreservePunctuation() {
        var m = (YamlNode.Map) YamlReader.parse("""
                cache: "max-age=3600, public"
                policy: 'interest-cohort=()'
                """);
        assertEquals("max-age=3600, public", m.string("cache").orElseThrow());
        assertEquals("interest-cohort=()", m.string("policy").orElseThrow());
    }

    @Test
    void nestedMaps() {
        var m = (YamlNode.Map) YamlReader.parse("""
                server:
                  port: 8080
                  bind: 0.0.0.0
                static:
                  root: /var/www
                """);
        var server = m.map("server").orElseThrow();
        assertEquals(8080, server.integer("port").orElseThrow());
        assertEquals("0.0.0.0", server.string("bind").orElseThrow());
        var st = m.map("static").orElseThrow();
        assertEquals("/var/www", st.string("root").orElseThrow());
    }

    @Test
    void inlineList() {
        var m = (YamlNode.Map) YamlReader.parse("""
                index-files: [index.html, index.htm]
                """);
        List<String> items = m.stringList("index-files").orElseThrow();
        assertEquals(List.of("index.html", "index.htm"), items);
    }

    @Test
    void blockList() {
        var m = (YamlNode.Map) YamlReader.parse("""
                items:
                  - alpha
                  - beta
                  - gamma
                """);
        List<String> items = m.stringList("items").orElseThrow();
        assertEquals(List.of("alpha", "beta", "gamma"), items);
    }

    @Test
    void commentsStripped() {
        var m = (YamlNode.Map) YamlReader.parse("""
                # leading comment
                port: 8080  # inline comment
                # mid-doc comment
                host: localhost
                """);
        assertEquals(8080, m.integer("port").orElseThrow());
        assertEquals("localhost", m.string("host").orElseThrow());
    }

    @Test
    void hashInQuotedStringIsLiteral() {
        var m = (YamlNode.Map) YamlReader.parse("""
                tag: "no#index"
                """);
        assertEquals("no#index", m.string("tag").orElseThrow());
    }

    @Test
    void mapWithHyphenAndUnderscoreKeys() {
        var m = (YamlNode.Map) YamlReader.parse("""
                cache-control: "max-age=60"
                access_log: false
                X-Robots-Tag: "noindex"
                """);
        assertEquals("max-age=60", m.string("cache-control").orElseThrow());
        assertFalse(m.bool("access_log").orElseThrow());
        assertEquals("noindex", m.string("X-Robots-Tag").orElseThrow());
    }

    @Test
    void deeplyNestedHeadersBlock() {
        var m = (YamlNode.Map) YamlReader.parse("""
                headers:
                  always:
                    X-Content-Type-Options: nosniff
                    Referrer-Policy: "strict-origin-when-cross-origin"
                  staging:
                    X-Robots-Tag: "noindex, nofollow"
                """);
        var always = m.map("headers").orElseThrow().map("always").orElseThrow();
        assertEquals("nosniff", always.string("X-Content-Type-Options").orElseThrow());
        assertEquals(
                "strict-origin-when-cross-origin",
                always.string("Referrer-Policy").orElseThrow());
        var staging = m.map("headers").orElseThrow().map("staging").orElseThrow();
        assertEquals("noindex, nofollow", staging.string("X-Robots-Tag").orElseThrow());
    }

    @Test
    void tabIndentationRejected() {
        var ex = assertThrows(YamlParseException.class, () -> YamlReader.parse("server:\n\tport: 80\n"));
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("tab"));
    }

    @Test
    void unknownColonInValueParsesAsScalar() {
        var m = (YamlNode.Map) YamlReader.parse("""
                url: "http://localhost:8080/api"
                """);
        assertEquals("http://localhost:8080/api", m.string("url").orElseThrow());
    }

    @Test
    void parsesActualChappeConfigFixture() {
        var m = (YamlNode.Map) YamlReader.parse("""
                server:
                  port: 8080
                  bind: 0.0.0.0
                static:
                  root: /var/www/vidocq-docs
                  fallback: /404.html
                  index-files: [index.html, index.htm]
                  cache-control: "max-age=3600, public"
                  gzip: true
                headers:
                  always:
                    X-Content-Type-Options: "nosniff"
                  staging:
                    X-Robots-Tag: "noindex, nofollow"
                logging:
                  level: INFO
                  access-log: false
                """);
        assertEquals(8080, m.map("server").orElseThrow().integer("port").orElseThrow());
        var st = m.map("static").orElseThrow();
        assertEquals("/var/www/vidocq-docs", st.string("root").orElseThrow());
        assertEquals("/404.html", st.string("fallback").orElseThrow());
        assertEquals(
                List.of("index.html", "index.htm"), st.stringList("index-files").orElseThrow());
        assertTrue(st.bool("gzip").orElseThrow());
        var always = m.map("headers").orElseThrow().map("always").orElseThrow();
        assertEquals("nosniff", always.string("X-Content-Type-Options").orElseThrow());
        assertEquals("INFO", m.map("logging").orElseThrow().string("level").orElseThrow());
        assertFalse(m.map("logging").orElseThrow().bool("access-log").orElseThrow());
    }

    // -- Characterization of the parser's edge cases (structure, comments, inline lists, errors) --

    @Test
    void emptyValueIsAnEmptyScalar() {
        var m = (YamlNode.Map) YamlReader.parse("a:\nb: 1\nc:\n");
        assertEquals("", m.string("a").orElseThrow());
        assertEquals("1", m.string("b").orElseThrow());
        assertEquals("", m.string("c").orElseThrow());
    }

    @Test
    void blockListOfNestedListsAndMaps() {
        var m = (YamlNode.Map) YamlReader.parse("""
                items:
                  - y
                  -
                    - x
                  -
                    k: v
                  -
                """);
        var items = assertInstanceOf(YamlNode.Seq.class, m.get("items").orElseThrow())
                .items();
        assertEquals(4, items.size());
        assertEquals(new YamlNode.Scalar("y"), items.get(0));
        assertEquals(
                List.of("x"), assertInstanceOf(YamlNode.Seq.class, items.get(1)).asStringList());
        assertEquals(
                "v",
                assertInstanceOf(YamlNode.Map.class, items.get(2)).string("k").orElseThrow());
        assertEquals(new YamlNode.Scalar(""), items.get(3));
    }

    /** CHAPPE-052: the lines after a dedent below the first line were silently dropped. */
    @Test
    void lineIndentedLessThanTheFirstOneIsRejected() {
        var ex = assertThrows(YamlParseException.class, () -> YamlReader.parse("  a: 1\nb: 2\n"));
        assertEquals(2, ex.line(), ex::getMessage);
        assertEquals(1, ex.column(), ex::getMessage);
        assertTrue(ex.getMessage().contains("unexpected indentation (expected 2, got 0)"), ex::getMessage);
    }

    /** CHAPPE-053: a block list whose first item was a bare dash failed with "missing ':' separator". */
    @Test
    void blockListMayStartWithABareDash() {
        var m = (YamlNode.Map) YamlReader.parse("""
                items:
                  -
                    - x
                  - y
                """);
        var items = assertInstanceOf(YamlNode.Seq.class, m.get("items").orElseThrow())
                .items();
        assertEquals(2, items.size());
        assertEquals(
                List.of("x"), assertInstanceOf(YamlNode.Seq.class, items.get(0)).asStringList());
        assertEquals(new YamlNode.Scalar("y"), items.get(1));
    }

    @Test
    void listItemWithColonStaysAScalar() {
        var m = (YamlNode.Map) YamlReader.parse("""
                items:
                  - name: a
                  - [b, c]
                """);
        var items = assertInstanceOf(YamlNode.Seq.class, m.get("items").orElseThrow())
                .items();
        assertEquals(new YamlNode.Scalar("name: a"), items.get(0));
        assertEquals(
                List.of("b", "c"),
                assertInstanceOf(YamlNode.Seq.class, items.get(1)).asStringList());
    }

    @Test
    void hashGluedToAWordIsNotAComment() {
        var m = (YamlNode.Map) YamlReader.parse("a: b#c\nd: e #f\n");
        assertEquals("b#c", m.string("a").orElseThrow());
        assertEquals("e", m.string("d").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "a: []                  | ''",
                "a: [x,]                | x",
                "'a: [x, , y ]'         | x;;y",
                "'a: [\"x, y\", ''z'', w]' | x, y;z;w",
                "'a: [ \"#\" ]'         | #"
            })
    void inlineLists(String yaml, String joined) {
        var m = (YamlNode.Map) YamlReader.parse(yaml);
        assertEquals(joined, String.join(";", m.stringList("a").orElseThrow()));
    }

    @Test
    void quotedKeysMayHoldColonsAndUnquotedValuesKeepThem() {
        var m = (YamlNode.Map) YamlReader.parse("\"a:b\": c\nurl: http://x:1/p\n'k k': v\n");
        assertEquals("c", m.string("a:b").orElseThrow());
        assertEquals("http://x:1/p", m.string("url").orElseThrow());
        assertEquals("v", m.string("k k").orElseThrow());
    }

    @Test
    void crlfLineEndingsAndIndentedRoot() {
        var m = (YamlNode.Map) YamlReader.parse("  a: 1\r\n\r\n  # note\r\n  b:\r\n    c: 2\r\n");
        assertEquals("1", m.string("a").orElseThrow());
        assertEquals("2", m.map("b").orElseThrow().string("c").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "'server:\\n\\tport: 80'       | 2 | 1  | tab indentation forbidden",
                "'a:\\n  \\tb: 1'              | 2 | 3  | tab indentation forbidden",
                "'a: 1\\n  b: 2'               | 2 | 3  | unexpected indentation (expected 0, got 2)",
                "'- a'                         | 1 | 1  | list item not expected here",
                "'novalue'                     | 1 | 1  | missing ':' separator",
                "': v'                         | 1 | 1  | empty key",
                "'a: \"open'                   | 1 | 9  | unterminated quoted string",
                "'a: \"x\" y'                  | 1 | 3  | unterminated quoted string",
                "'a: [x'                       | 1 | 3  | invalid inline list",
                "'a:\\n  - x\\n    - y'        | 3 | 5  | unexpected indentation in list (expected 2)",
                "'a:\\n  - x\\n  k: v'         | 3 | 3  | unexpected indentation (expected 0, got 2)"
            })
    void malformedDocumentsAreRejectedWithTheirPosition(String yaml, int line, int column, String message) {
        String input = yaml.replace("\\n", "\n").replace("\\t", "\t");
        var ex = assertThrows(YamlParseException.class, () -> YamlReader.parse(input));
        assertEquals(line, ex.line(), ex::getMessage);
        assertEquals(column, ex.column(), ex::getMessage);
        assertTrue(ex.getMessage().contains(message), ex::getMessage);
    }
}
