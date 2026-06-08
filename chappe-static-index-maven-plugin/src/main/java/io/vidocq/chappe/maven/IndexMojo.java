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
package io.vidocq.chappe.maven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Scans a configured resource root under the project build output and writes an
 * index file listing each static resource with its size, last-modified timestamp,
 * MIME type and content-hash ETag.
 *
 * <p>At runtime, {@code StaticFileHandler} loads this index to resolve resources
 * in O(1) without calling {@code URLConnection.openConnection()} per request.
 */
@Mojo(name = "index", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public final class IndexMojo extends AbstractMojo {

    private static final String INDEX_RESOURCE_PATH = "META-INF/chappe-static-index.properties";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory, relative to the build output, that contains static resources
     * to index. Defaults to {@code static}, so files under
     * {@code src/main/resources/static/**} are indexed.
     */
    @Parameter(defaultValue = "static")
    private String rootPrefix;

    /**
     * Root directory to scan. Defaults to {@code ${project.build.outputDirectory}}
     * so the scan sees all resources already copied by maven-resources-plugin.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private String outputDirectory;

    /** Skip execution without failing the build. */
    @Parameter(defaultValue = "false", property = "chappe.staticIndex.skip")
    private boolean skip;

    /**
     * Pre-compression scheme for compressible resources. Accepted values: {@code none}
     * (default) or {@code gzip}. When {@code gzip}, a sidecar {@code <path>.gz} is
     * generated next to each compressible resource and indexed in
     * {@code chappe-static-index.properties}, allowing
     * {@code StaticFileHandler.preferPrecompressed(true)} to serve it zero-copy.
     */
    @Parameter(defaultValue = "none", property = "chappe.staticIndex.compress")
    private String compress;

    /** Minimum size (bytes) below which a resource is not pre-compressed. */
    @Parameter(defaultValue = "1024", property = "chappe.staticIndex.compressThreshold")
    private int compressThreshold;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("chappe-static-index: skipped via chappe.staticIndex.skip");
            return;
        }

        Path outputDir = Paths.get(outputDirectory);
        Path root = outputDir.resolve(rootPrefix).normalize();
        if (!Files.isDirectory(root)) {
            getLog().info("chappe-static-index: no static root at " + root + " — skipping");
            return;
        }

        boolean doGzip = "gzip".equalsIgnoreCase(compress);

        Path indexFile = outputDir.resolve(INDEX_RESOURCE_PATH);
        try {
            Files.createDirectories(indexFile.getParent());
            Properties props = new Properties();
            int[] gzCount = {0};
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    indexEntry(props, outputDir, p);
                    if (doGzip && shouldCompress(p, compressThreshold)) {
                        Path sidecar = generateGzipSidecar(p);
                        if (sidecar != null) {
                            indexEntry(props, outputDir, sidecar);
                            gzCount[0]++;
                        }
                    }
                });
            }
            try (BufferedWriter out = Files.newBufferedWriter(indexFile)) {
                props.store(out, "Chappe static resources index — generated at build time");
            }
            String suffix = doGzip ? " (+" + gzCount[0] + " .gz sidecars)" : "";
            getLog().info("chappe-static-index: indexed " + props.size() + " resources" + suffix + " → " + indexFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate " + INDEX_RESOURCE_PATH, e);
        }
    }

    private static boolean shouldCompress(Path file, int threshold) {
        try {
            if (Files.size(file) < threshold) return false;
        } catch (IOException ignored) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase();
        // Skip already-compressed sidecars and non-compressible types.
        if (name.endsWith(".gz")
                || name.endsWith(".br")
                || name.endsWith(".zip")
                || name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp")
                || name.endsWith(".gif")
                || name.endsWith(".woff")
                || name.endsWith(".woff2")
                || name.endsWith(".mp4")
                || name.endsWith(".webm")
                || name.endsWith(".pdf")
                || name.endsWith(".wasm")) {
            return false;
        }
        return true;
    }

    private Path generateGzipSidecar(Path source) {
        Path target = source.resolveSibling(source.getFileName() + ".gz");
        try (InputStream in = Files.newInputStream(source);
                OutputStream out = Files.newOutputStream(target);
                GZIPOutputStream gz = new GZIPOutputStream(out)) {
            in.transferTo(gz);
        } catch (IOException e) {
            getLog().warn("Failed to gzip " + source + ": " + e.getMessage());
            return null;
        }
        return target;
    }

    // Keys = classpath paths (with rootPrefix included), e.g. "static/index.html".
    // Runtime does not need to know rootPrefix: it looks up resourcePath directly.
    private void indexEntry(Properties props, Path outputDir, Path file) {
        String rel = outputDir.relativize(file).toString().replace('\\', '/');
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            String mime = detectMime(rel);
            String etag = sha256Hex(file).substring(0, 16);
            props.setProperty(rel, size + "|" + mtime + "|" + mime + "|" + etag);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IndexingException("Failed to index " + file, e);
        }
    }

    private static String sha256Hex(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // Local mini-table — avoids depending on chappe-api from this Maven plugin.
    // Kept aligned with io.vidocq.chappe.api.MimeTypes.
    private static String detectMime(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "svg" -> "image/svg+xml";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "gz" -> "application/gzip";
            case "wasm" -> "application/wasm";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }

    static final class IndexingException extends RuntimeException {
        IndexingException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
