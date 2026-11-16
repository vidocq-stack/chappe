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
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The classpath fast path: entries of {@code META-INF/chappe-static-index.properties} (written by
 * {@code chappe-static-index-maven-plugin}) are served with the size and modification time of the
 * index, without {@code URLConnection}; a malformed entry is ignored and the class loader lookup
 * takes over. The index a handler read goes with the handler: nothing keeps its class loader.
 */
class StaticFileHandlerIndexTest {

    /** The modification time the index records, distinct from the files' own. */
    private static final long INDEXED_MTIME = 1_700_000_000_000L;

    private static final String INDEXED_DATE = "Tue, 14 Nov 2023 22:13:20 GMT";

    /** The files' own modification time, read by the class loader lookup. */
    private static final long FILE_MTIME = 1_600_000_000_000L;

    private static final String FILE_DATE = "Sun, 13 Sep 2020 12:26:40 GMT";

    @TempDir
    static Path root;

    /** A class path without an index file: every lookup takes the class loader path. */
    @TempDir
    static Path unindexed;

    private static URLClassLoader loader;
    private static Handler handler;

    @BeforeAll
    static void writeIndexedResources() throws IOException {
        write("static/app.js", "console.log(1)");
        write("static/index.html", "root index");
        write("static/docs/index.html", "docs index");
        write("static/corrupt.txt", "plain");
        write("static/short.txt", "plain");
        String index = entry("static/app.js", "console.log(1)")
                + entry("static/index.html", "root index")
                + entry("static/docs/index.html", "docs index")
                + "static/corrupt.txt=x|1|text/plain|e\n"
                + "static/short.txt=5|1\n";
        write("META-INF/chappe-static-index.properties", index);
        Files.createDirectories(unindexed.resolve("static"));
        Files.writeString(unindexed.resolve("static/app.js"), "console.log(1)", StandardCharsets.UTF_8);

        loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
        handler = StaticFileHandler.builder().addClasspath(loader, "static").build();
    }

    @AfterAll
    static void closeLoader() throws IOException {
        loader.close();
    }

    @ParameterizedTest
    @CsvSource({"/app.js, console.log(1)", "/docs, docs index", "/, root index"})
    void anIndexedResourceIsServedWithTheMetadataOfTheIndex(String pathInfo, String body) throws Exception {
        Response r = handler.handle(req(pathInfo));

        assertEquals(StatusCode.OK, r.status());
        assertEquals(INDEXED_DATE, r.headers().firstOrNull("Last-Modified"));
        assertEquals(body, bodyAsString(r));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/corrupt.txt", "/short.txt"})
    void aMalformedIndexEntryFallsBackToTheClassLoader(String pathInfo) throws Exception {
        Response r = handler.handle(req(pathInfo));

        assertEquals(StatusCode.OK, r.status());
        assertEquals(FILE_DATE, r.headers().firstOrNull("Last-Modified"));
        assertEquals("plain", bodyAsString(r));
    }

    /**
     * CHAPPE-061: what a handler knows of the index of a class loader goes with the handler. A static map kept
     * every loader that had served a file, for the life of the JVM: a host that replaces its application loader
     * while the server's classes stay, as the Vidocq dev mode does at each hot reload, kept every one of them.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aDroppedHandlerDoesNotKeepItsClassLoader(boolean indexed) throws Exception {
        WeakReference<ClassLoader> dropped = serveFromALoaderOfItsOwn(indexed ? root : unindexed);

        assertNull(collected(dropped), "the class loader is still reachable once its handler is dropped");
    }

    /** Serves one file through a loader and a handler that nothing references once this method returns. */
    private static WeakReference<ClassLoader> serveFromALoaderOfItsOwn(Path directory) throws Exception {
        try (URLClassLoader own =
                new URLClassLoader(new URL[] {directory.toUri().toURL()}, null)) {
            Handler served =
                    StaticFileHandler.builder().addClasspath(own, "static").build();
            assertEquals("console.log(1)", bodyAsString(served.handle(req("/app.js"))));
            return new WeakReference<>(own);
        }
    }

    /** The loader after up to a second of collections, or {@code null} once it is collected. */
    private static ClassLoader collected(WeakReference<ClassLoader> reference) {
        for (int i = 0; i < 40 && reference.get() != null; i++) {
            System.gc();
            LockSupport.parkNanos(25_000_000L);
        }
        return reference.get();
    }

    private static String entry(String name, String content) {
        int size = content.getBytes(StandardCharsets.UTF_8).length;
        return name + "=" + size + "|" + INDEXED_MTIME + "|text/plain|0123456789abcdef\n";
    }

    private static void write(String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(FILE_MTIME));
    }

    private static String bodyAsString(Response r) throws IOException {
        try (var in = r.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Request req(String pathInfo) {
        return new Request() {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public URI uri() {
                return URI.create("http://test" + pathInfo);
            }

            @Override
            public String path() {
                return pathInfo;
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public HttpVersion version() {
                return HttpVersion.HTTP_1_1;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public Body body() {
                return Body.empty();
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParams() {
                return Map.of();
            }

            @Override
            public String pathInfo() {
                return pathInfo;
            }
        };
    }
}
