package io.vidocq.chappe.cli.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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
        assertEquals(Boolean.TRUE, m.bool("gzip").orElseThrow());
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
        assertEquals(Boolean.FALSE, m.bool("access_log").orElseThrow());
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
        assertTrue(ex.getMessage().toLowerCase().contains("tab"));
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
        assertEquals(Boolean.TRUE, st.bool("gzip").orElseThrow());
        var always = m.map("headers").orElseThrow().map("always").orElseThrow();
        assertEquals("nosniff", always.string("X-Content-Type-Options").orElseThrow());
        assertEquals("INFO", m.map("logging").orElseThrow().string("level").orElseThrow());
        assertEquals(
                Boolean.FALSE, m.map("logging").orElseThrow().bool("access-log").orElseThrow());
    }
}
