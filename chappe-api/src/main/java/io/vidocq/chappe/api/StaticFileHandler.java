package io.vidocq.chappe.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static file handler with fallback chain, classpath support, and in-memory cache.
 *
 * <pre>{@code
 * // Simple filesystem
 * StaticFileHandler.of(Path.of("./public"))
 *
 * // Builder with fallback chain
 * StaticFileHandler.builder()
 *     .addPath(Path.of("./public"))              // filesystem first
 *     .addClasspath("static")                     // then classpath
 *     .addClasspath("META-INF/resources")         // then META-INF
 *     .cacheInMemory(true)                        // cache small resources
 *     .cacheControl("max-age=3600")               // Cache-Control header
 *     .indexFile("index.html")                    // index file for directories
 *     .build()
 * }</pre>
 */
public final class StaticFileHandler implements Handler {

    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private static final int MAX_CACHE_ENTRY_SIZE = 64 * 1024; // max 64 KB per entry

    private final List<ResourceSource> sources;
    private final String indexFile;
    private final String cacheControl;

    @SuppressWarnings("UnusedVariable") // API exposée via Builder.cacheInMemory(), implémentation à compléter
    private final boolean cacheInMemory;

    private final String notFoundFile;
    private final String spaFallback;
    private final boolean preferPrecompressed;
    private final boolean cleanUrls;
    private final ConcurrentHashMap<String, CachedResource> cache;

    private StaticFileHandler(
            List<ResourceSource> sources,
            String indexFile,
            String cacheControl,
            boolean cacheInMemory,
            String notFoundFile,
            String spaFallback,
            boolean preferPrecompressed,
            boolean cleanUrls) {
        this.sources = List.copyOf(sources);
        this.indexFile = indexFile;
        this.cacheControl = cacheControl;
        this.cacheInMemory = cacheInMemory;
        this.notFoundFile = notFoundFile;
        this.spaFallback = spaFallback;
        this.preferPrecompressed = preferPrecompressed;
        this.cleanUrls = cleanUrls;
        this.cache = cacheInMemory ? new ConcurrentHashMap<>() : null;
    }

    // -- Simple factories --

    /** Serves files from a filesystem directory. */
    public static Handler of(Path root) {
        return builder().addPath(root).build();
    }

    /** Serves files from a directory with a custom index file. */
    public static Handler of(Path root, String indexFile) {
        return builder().addPath(root).indexFile(indexFile).build();
    }

    /** Creates a builder for advanced configuration. */
    public static Builder builder() {
        return new Builder();
    }

    // -- Handler --

    @Override
    public Response handle(Request request) throws Exception {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return Response.of(StatusCode.METHOD_NOT_ALLOWED);
        }

        String requestPath = request.pathInfo();
        if (requestPath.contains("..") || requestPath.contains("\0")) {
            return Response.of(StatusCode.FORBIDDEN);
        }

        String relative = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

        // Precompression: try .br then .gz if client is compatible
        if (preferPrecompressed && !relative.isEmpty() && !relative.endsWith("/")) {
            String acceptEnc = request.header("Accept-Encoding").orElse(null);
            String[][] sidecars = {{"br", ".br"}, {"gzip", ".gz"}};
            for (var s : sidecars) {
                if (!AcceptEncoding.accepts(acceptEnc, s[0])) continue;
                String sidecarPath = relative + s[1];
                for (var source : sources) {
                    var resource = source.resolve(sidecarPath, indexFile);
                    if (resource != null) {
                        return serveEncoded(resource, relative, s[0]);
                    }
                }
            }
        }

        // Cache hit?
        if (cache != null) {
            var cached = cache.get(relative);
            if (cached != null) {
                return serveCached(request, cached, StatusCode.OK);
            }
        }

        // Search through sources (fallback chain)
        for (var source : sources) {
            var resource = source.resolve(relative, indexFile);
            if (resource != null) {
                // Cache if applicable
                if (cache != null && resource.size() >= 0 && resource.size() <= MAX_CACHE_ENTRY_SIZE) {
                    var cached = cacheResource(relative, resource);
                    return serveCached(request, cached, StatusCode.OK);
                }
                return serveResource(request, resource, StatusCode.OK);
            }
        }

        // Clean URLs ("pretty URLs", GitHub Pages / Netlify style): an extensionless request
        // (e.g. /admin or /admin/) resolves to its .html sibling (admin.html) once no file or
        // directory index matched. Lets a multi-page build expose /admin without the .html suffix
        // and without a per-app redirect. Skipped when the last path segment already carries an
        // extension, so /style.css is never looked up as /style.css.html.
        if (cleanUrls) {
            String candidate = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
            if (!candidate.isEmpty() && lastSegmentHasNoExtension(candidate)) {
                String htmlPath = candidate + ".html";
                for (var source : sources) {
                    var resource = source.resolve(htmlPath, indexFile);
                    if (resource != null) {
                        return serveResource(request, resource, StatusCode.OK);
                    }
                }
            }
        }

        // Fallback (SPA ou 404 page)
        if (spaFallback != null) {
            Response fb = serveFallback(request, spaFallback, StatusCode.OK);
            if (fb != null) return fb;
        } else if (notFoundFile != null) {
            Response fb = serveFallback(request, notFoundFile, StatusCode.NOT_FOUND);
            if (fb != null) return fb;
        }

        return Response.of(StatusCode.NOT_FOUND);
    }

    /** Serves a pre-compressed sidecar: Content-Type based on original extension, adds Content-Encoding + Vary. */
    private Response serveEncoded(ResolvedResource resource, String originalRelative, String encoding) {
        var builder = Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", MimeTypes.detect(originalRelative))
                .header("Content-Encoding", encoding)
                .header("Vary", "Accept-Encoding");
        if (resource.lastModified() != null) {
            builder.header("Last-Modified", IMF_FIXDATE.format(resource.lastModified()));
        }
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }
        builder.body(resource.toBody());
        return builder.build();
    }

    /** True when the last path segment has no {@code .ext} suffix (so it is a candidate for clean-URL .html resolution). */
    private static boolean lastSegmentHasNoExtension(String path) {
        int slash = path.lastIndexOf('/');
        String segment = slash < 0 ? path : path.substring(slash + 1);
        return segment.indexOf('.') < 0;
    }

    private Response serveFallback(Request request, String fallbackPath, StatusCode status) throws IOException {
        String fb = fallbackPath.startsWith("/") ? fallbackPath.substring(1) : fallbackPath;
        for (var source : sources) {
            var resource = source.resolve(fb, indexFile);
            if (resource != null) {
                return serveResource(request, resource, status);
            }
        }
        return null;
    }

    // -- Serve helpers --

    private Response serveResource(Request request, ResolvedResource resource, StatusCode status) throws IOException {
        // If-Modified-Since (only for OK responses — fallbacks always serve fresh)
        if (status == StatusCode.OK && resource.lastModified() != null) {
            String ims = request.header("If-Modified-Since").orElse(null);
            if (ims != null) {
                try {
                    Instant clientTime = ZonedDateTime.parse(ims, IMF_FIXDATE).toInstant();
                    if (!resource.lastModified().isAfter(clientTime)) {
                        return Response.of(StatusCode.NOT_MODIFIED);
                    }
                } catch (Exception _) {
                    // Malformed If-Modified-Since — ignore and serve resource (RFC 9110 §13.1.3)
                }
            }
        }

        var builder = Response.builder().status(status).header("Content-Type", MimeTypes.detect(resource.name()));

        if (resource.lastModified() != null) {
            builder.header("Last-Modified", IMF_FIXDATE.format(resource.lastModified()));
        }
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }

        builder.body(resource.toBody());
        return builder.build();
    }

    private Response serveCached(Request request, CachedResource cached, StatusCode status) {
        // ETag / If-None-Match (only for OK responses)
        if (status == StatusCode.OK) {
            String inm = request.header("If-None-Match").orElse(null);
            if (inm != null && inm.equals(cached.etag)) {
                return Response.of(StatusCode.NOT_MODIFIED);
            }
        }

        var builder = Response.builder()
                .status(status)
                .header("Content-Type", cached.contentType)
                .header("ETag", cached.etag);

        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }

        builder.body(Body.of(cached.data));
        return builder.build();
    }

    private CachedResource cacheResource(String key, ResolvedResource resource) throws IOException {
        byte[] data;
        try (var is = resource.inputStream()) {
            data = is.readAllBytes();
        }
        String etag = "\"" + computeEtag(data) + "\"";
        String contentType = MimeTypes.detect(resource.name());
        var cached = new CachedResource(data, contentType, etag);
        cache.put(key, cached);
        return cached;
    }

    private static String computeEtag(byte[] data) {
        try {
            // MD5 is used for HTTP ETag identifier, not for cryptographic integrity.
            // Content is never validated through this hash — no attack surface.
            var md = MessageDigest.getInstance("MD5");
            var hash = md.digest(data);
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException _) {
            return Long.toHexString(data.length);
        }
    }

    // -- Internal types --

    @SuppressWarnings("ArrayRecordComponent") // record interne, jamais comparé via equals/hashCode
    private record CachedResource(byte[] data, String contentType, String etag) {}

    /** Resolved resource from a source. */
    private record ResolvedResource(
            String name,
            long size,
            Instant lastModified,
            java.util.function.Supplier<InputStream> streamSupplier,
            Path filePath) {
        InputStream inputStream() {
            return streamSupplier.get();
        }

        Body toBody() {
            if (filePath != null) {
                return Body.ofFile(filePath);
            }
            return Body.of(streamSupplier.get(), size);
        }
    }

    /** Resource source (filesystem or classpath). */
    private sealed interface ResourceSource {
        ResolvedResource resolve(String relative, String indexFile);
    }

    /** Filesystem source. */
    private record PathSource(Path root) implements ResourceSource {
        @Override
        public ResolvedResource resolve(String relative, String indexFile) {
            try {
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root)) return null;

                if (Files.isDirectory(file)) {
                    file = file.resolve(indexFile);
                }
                if (!Files.isRegularFile(file)) return null;

                Instant lastMod = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
                long size = Files.size(file);
                Path f = file;
                return new ResolvedResource(
                        file.getFileName().toString(),
                        size,
                        lastMod,
                        () -> {
                            try {
                                return Files.newInputStream(f);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        f);
            } catch (IOException _) {
                return null;
            }
        }
    }

    /** Classpath source (jar, module, classpath directory). */
    private record ClasspathSource(ClassLoader loader, String basePath) implements ResourceSource {
        @Override
        public ResolvedResource resolve(String relative, String indexFile) {
            String resourcePath = basePath.isEmpty() ? relative : basePath + "/" + relative;

            // If request targets a directory (empty relative path or trailing /),
            // resolve directly to indexFile: otherwise getResource() returns
            // the jar-directory URL on slow path and URLConnection cannot
            // serve it correctly (size = -1, openStream returns a text listing).
            if (relative.isEmpty() || relative.endsWith("/")) {
                resourcePath = resourcePath.isEmpty() || resourcePath.endsWith("/")
                        ? resourcePath + indexFile
                        : resourcePath + "/" + indexFile;
            }

            // Fast path: entry precomputed at build time by chappe-static-index-maven-plugin.
            // Skip URLConnection.openConnection() entirely — everything is already known.
            IndexedEntry idx = StaticIndex.lookup(loader, resourcePath);
            if (idx == null) {
                String indexPath =
                        resourcePath.endsWith("/") ? resourcePath + indexFile : resourcePath + "/" + indexFile;
                idx = StaticIndex.lookup(loader, indexPath);
                if (idx != null) resourcePath = indexPath;
            }
            if (idx != null) {
                String name = lastSegment(resourcePath);
                String path = resourcePath;
                Instant lastMod =
                        idx.mtime() > 0 ? Instant.ofEpochMilli(idx.mtime()).truncatedTo(ChronoUnit.SECONDS) : null;
                return new ResolvedResource(
                        name,
                        idx.size(),
                        lastMod,
                        () -> {
                            InputStream in = loader.getResourceAsStream(path);
                            if (in == null)
                                throw new UncheckedIOException(new IOException("Indexed resource vanished: " + path));
                            return in;
                        },
                        null);
            }

            // Slow path: classic lookup via URL.openConnection (outside index).
            URL url = loader.getResource(resourcePath);
            if (url == null) {
                String indexPath =
                        resourcePath.endsWith("/") ? resourcePath + indexFile : resourcePath + "/" + indexFile;
                url = loader.getResource(indexPath);
                if (url != null) resourcePath = indexPath;
            }
            if (url == null) return null;

            try {
                URLConnection conn = url.openConnection();
                conn.setUseCaches(false); // avoids lock contention on jar files
                long size = conn.getContentLengthLong();
                long lastMod = conn.getLastModified();
                Instant lastModified =
                        lastMod > 0 ? Instant.ofEpochMilli(lastMod).truncatedTo(ChronoUnit.SECONDS) : null;

                String name = lastSegment(resourcePath);

                URL finalUrl = url;
                return new ResolvedResource(
                        name,
                        size,
                        lastModified,
                        () -> {
                            try {
                                return finalUrl.openStream();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        null); // no filePath for classpath (no zero-copy)
            } catch (IOException _) {
                return null;
            }
        }

        private static String lastSegment(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /** Classpath resource metadata pre-computed by the Maven plugin. */
    private record IndexedEntry(long size, long mtime, String mime, String etag) {}

    /**
     * Index loaded once per ClassLoader from {@code META-INF/chappe-static-index.properties}.
     * Absent ⇒ empty map ⇒ fallback to the classic runtime path lookup (no regression).
     */
    private static final class StaticIndex {
        private static final String RESOURCE = "META-INF/chappe-static-index.properties";
        private static final ConcurrentHashMap<ClassLoader, Map<String, IndexedEntry>> PER_LOADER =
                new ConcurrentHashMap<>();

        static IndexedEntry lookup(ClassLoader loader, String path) {
            ClassLoader key = loader == null ? ClassLoader.getSystemClassLoader() : loader;
            Map<String, IndexedEntry> map = PER_LOADER.computeIfAbsent(key, StaticIndex::load);
            return map.get(path);
        }

        private static Map<String, IndexedEntry> load(ClassLoader loader) {
            Map<String, IndexedEntry> merged = new HashMap<>();
            try {
                Enumeration<URL> urls = loader.getResources(RESOURCE);
                if (!urls.hasMoreElements()) return Collections.emptyMap();
                for (URL url : Collections.list(urls)) {
                    Properties p = new Properties();
                    try (InputStream in = url.openStream()) {
                        p.load(in);
                    }
                    for (String key : p.stringPropertyNames()) {
                        String[] parts = p.getProperty(key).split("\\|", 4);
                        if (parts.length != 4) continue;
                        try {
                            merged.putIfAbsent(
                                    key,
                                    new IndexedEntry(
                                            Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2], parts[3]));
                        } catch (NumberFormatException _) {
                            // corrupted entry, ignore
                        }
                    }
                }
            } catch (IOException _) {
                return Collections.emptyMap();
            }
            return merged.isEmpty() ? Collections.emptyMap() : Map.copyOf(merged);
        }
    }

    // -- Builder --

    public static final class Builder {
        private final List<ResourceSource> sources = new ArrayList<>();
        private String indexFile = "index.html";
        private String cacheControl;
        private boolean cacheInMemory;
        private String notFoundFile;
        private String spaFallback;
        private boolean preferPrecompressed;
        private boolean cleanUrls;

        private Builder() {}

        /** Adds a filesystem directory as a source (zero-copy via sendfile). */
        public Builder addPath(Path root) {
            sources.add(new PathSource(root.toAbsolutePath().normalize()));
            return this;
        }

        /** Adds a classpath path as a source (jar, module, META-INF/resources). */
        public Builder addClasspath(String basePath) {
            return addClasspath(Thread.currentThread().getContextClassLoader(), basePath);
        }

        /** Adds a classpath path with a specific ClassLoader. */
        public Builder addClasspath(ClassLoader loader, String basePath) {
            // Normalize: no leading/trailing /
            String normalized = basePath;
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            sources.add(new ClasspathSource(loader, normalized));
            return this;
        }

        /** Index file for directories (default: "index.html"). */
        public Builder indexFile(String indexFile) {
            this.indexFile = indexFile;
            return this;
        }

        /** Cache-Control header added to every response (e.g. "max-age=3600, public"). */
        public Builder cacheControl(String cacheControl) {
            this.cacheControl = cacheControl;
            return this;
        }

        /** Enables in-memory cache for small classpath resources (< 64 KB). */
        public Builder cacheInMemory(boolean enabled) {
            this.cacheInMemory = enabled;
            return this;
        }

        /**
         * File served with status 404 when the requested resource does not exist.
         * Mutually exclusive with {@link #spaFallback(String)}.
         */
        public Builder notFoundFile(String path) {
            this.notFoundFile = path;
            return this;
        }

        /**
         * File served with status 200 when the requested resource does not exist
         * (typically {@code /index.html} for client-side-routed SPAs).
         * Mutually exclusive with {@link #notFoundFile(String)}.
         */
        public Builder spaFallback(String path) {
            this.spaFallback = path;
            return this;
        }

        /**
         * Enables clean ("pretty") URLs: an extensionless request that matches no file or directory
         * index is retried with a {@code .html} suffix — {@code /admin} and {@code /admin/} both serve
         * {@code admin.html}. Lets a multi-page build expose suffix-free URLs without a per-app redirect
         * (GitHub Pages / Netlify behaviour). Paths whose last segment already has an extension
         * (e.g. {@code /style.css}) are never rewritten. Default: {@code false}.
         */
        public Builder cleanUrls(boolean enabled) {
            this.cleanUrls = enabled;
            return this;
        }

        /**
         * Serves pre-compressed sidecars ({@code path.br}, {@code path.gz}) in preference
         * over the original when the client accepts them. No runtime generation —
         * sidecars must exist on the filesystem (typically produced by
         * {@code chappe-static-index-maven-plugin}).
         */
        public Builder preferPrecompressed(boolean enabled) {
            this.preferPrecompressed = enabled;
            return this;
        }

        /** Builds the handler. At least one source must be configured. */
        public Handler build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one source (addPath or addClasspath) is required");
            }
            if (notFoundFile != null && spaFallback != null) {
                throw new IllegalStateException("notFoundFile and spaFallback are mutually exclusive");
            }
            return new StaticFileHandler(
                    sources,
                    indexFile,
                    cacheControl,
                    cacheInMemory,
                    notFoundFile,
                    spaFallback,
                    preferPrecompressed,
                    cleanUrls);
        }
    }
}
