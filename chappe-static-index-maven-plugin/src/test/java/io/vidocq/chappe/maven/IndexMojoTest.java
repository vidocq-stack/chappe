package io.vidocq.chappe.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class IndexMojoTest {

    @Test
    void indexesFilesUnderStaticRoot(@TempDir Path outputDir) throws Exception {
        Path staticDir = Files.createDirectories(outputDir.resolve("static"));
        Files.writeString(staticDir.resolve("index.html"), "<html>hi</html>");
        Files.writeString(staticDir.resolve("app.css"), "body{color:red}");
        Files.createDirectories(staticDir.resolve("js"));
        Files.writeString(staticDir.resolve("js/main.js"), "console.log(1)");

        IndexMojo mojo = new IndexMojo();
        set(mojo, "outputDirectory", outputDir.toString());
        set(mojo, "rootPrefix", "static");
        set(mojo, "skip", false);

        mojo.execute();

        Path indexFile = outputDir.resolve("META-INF/chappe-static-index.properties");
        assertTrue(Files.exists(indexFile), "index file must exist");

        Properties props = new Properties();
        try (var in = Files.newInputStream(indexFile)) {
            props.load(in);
        }
        assertEquals(3, props.size());

        String html = props.getProperty("static/index.html");
        assertNotNull(html);
        String[] parts = html.split("\\|");
        assertEquals(4, parts.length, "size|mtime|mime|etag");
        assertEquals(Long.toString(Files.size(staticDir.resolve("index.html"))), parts[0]);
        assertEquals("text/html", parts[2]);
        assertEquals(16, parts[3].length(), "etag = 16 hex chars");

        assertEquals("text/css", props.getProperty("static/app.css").split("\\|")[2]);
        assertEquals("text/javascript", props.getProperty("static/js/main.js").split("\\|")[2]);
    }

    @Test
    void skipsSilentlyWhenRootMissing(@TempDir Path outputDir) throws Exception {
        IndexMojo mojo = new IndexMojo();
        set(mojo, "outputDirectory", outputDir.toString());
        set(mojo, "rootPrefix", "static");
        set(mojo, "skip", false);

        mojo.execute();

        assertFalse(Files.exists(outputDir.resolve("META-INF/chappe-static-index.properties")));
    }

    @Test
    void honorsSkipFlag(@TempDir Path outputDir) throws Exception {
        Files.createDirectories(outputDir.resolve("static"));
        Files.writeString(outputDir.resolve("static/x.html"), "x");

        IndexMojo mojo = new IndexMojo();
        set(mojo, "outputDirectory", outputDir.toString());
        set(mojo, "rootPrefix", "static");
        set(mojo, "skip", true);

        mojo.execute();

        assertFalse(Files.exists(outputDir.resolve("META-INF/chappe-static-index.properties")));
    }

    private static void set(IndexMojo mojo, String field, Object value) throws Exception {
        Field f = IndexMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(mojo, value);
    }
}
