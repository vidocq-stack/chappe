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
 * Handler de fichiers statiques avec fallback chain, support classpath, cache mémoire.
 *
 * <pre>{@code
 * // Filesystem simple
 * StaticFileHandler.of(Path.of("./public"))
 *
 * // Builder avec fallback chain
 * StaticFileHandler.builder()
 *     .addPath(Path.of("./public"))              // filesystem d'abord
 *     .addClasspath("static")                     // puis classpath
 *     .addClasspath("META-INF/resources")         // puis META-INF
 *     .cacheInMemory(true)                        // cache les petites ressources
 *     .cacheControl("max-age=3600")               // header Cache-Control
 *     .indexFile("index.html")                    // fichier index pour les répertoires
 *     .build()
 * }</pre>
 */
public final class StaticFileHandler implements Handler {

    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private static final int MAX_CACHE_ENTRY_SIZE = 64 * 1024; // 64 Ko max par entrée

    private final List<ResourceSource> sources;
    private final String indexFile;
    private final String cacheControl;

    @SuppressWarnings("UnusedVariable") // API exposée via Builder.cacheInMemory(), implémentation à compléter
    private final boolean cacheInMemory;

    private final String notFoundFile;
    private final String spaFallback;
    private final boolean preferPrecompressed;
    private final ConcurrentHashMap<String, CachedResource> cache;

    private StaticFileHandler(
            List<ResourceSource> sources,
            String indexFile,
            String cacheControl,
            boolean cacheInMemory,
            String notFoundFile,
            String spaFallback,
            boolean preferPrecompressed) {
        this.sources = List.copyOf(sources);
        this.indexFile = indexFile;
        this.cacheControl = cacheControl;
        this.cacheInMemory = cacheInMemory;
        this.notFoundFile = notFoundFile;
        this.spaFallback = spaFallback;
        this.preferPrecompressed = preferPrecompressed;
        this.cache = cacheInMemory ? new ConcurrentHashMap<>() : null;
    }

    // ── Factories simples ──

    /** Sert les fichiers depuis un répertoire du filesystem. */
    public static Handler of(Path root) {
        return builder().addPath(root).build();
    }

    /** Sert les fichiers depuis un répertoire avec un fichier index custom. */
    public static Handler of(Path root, String indexFile) {
        return builder().addPath(root).indexFile(indexFile).build();
    }

    /** Crée un builder pour une configuration avancée. */
    public static Builder builder() {
        return new Builder();
    }

    // ── Handler ──

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

        // Précompression : tente .br puis .gz si client compatible
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

        // Cache hit ?
        if (cache != null) {
            var cached = cache.get(relative);
            if (cached != null) {
                return serveCached(request, cached, StatusCode.OK);
            }
        }

        // Chercher dans les sources (fallback chain)
        for (var source : sources) {
            var resource = source.resolve(relative, indexFile);
            if (resource != null) {
                // Mettre en cache si applicable
                if (cache != null && resource.size() >= 0 && resource.size() <= MAX_CACHE_ENTRY_SIZE) {
                    var cached = cacheResource(relative, resource);
                    return serveCached(request, cached, StatusCode.OK);
                }
                return serveResource(request, resource, StatusCode.OK);
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

    /** Sert un sidecar pré-compressé : Content-Type basé sur l'extension d'origine, ajoute Content-Encoding + Vary. */
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

    // ── Serve helpers ──

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
                    // If-Modified-Since mal formé — on ignore et on sert la ressource (RFC 9110 §13.1.3)
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
            var md = MessageDigest.getInstance("MD5");
            var hash = md.digest(data);
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException _) {
            return Long.toHexString(data.length);
        }
    }

    // ── Types internes ──

    @SuppressWarnings("ArrayRecordComponent") // record interne, jamais comparé via equals/hashCode
    private record CachedResource(byte[] data, String contentType, String etag) {}

    /** Ressource résolue depuis une source. */
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

    /** Source de ressources (filesystem ou classpath). */
    private sealed interface ResourceSource {
        ResolvedResource resolve(String relative, String indexFile);
    }

    /** Source filesystem. */
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

    /** Source classpath (jar, module, classpath directory). */
    private record ClasspathSource(ClassLoader loader, String basePath) implements ResourceSource {
        @Override
        public ResolvedResource resolve(String relative, String indexFile) {
            String resourcePath = basePath.isEmpty() ? relative : basePath + "/" + relative;

            // Si la requête vise un répertoire (relative vide ou se termine par /),
            // résoudre directement vers indexFile : sans ça, getResource() retourne
            // l'URL du dir jar dans le slow path et URLConnection ne sait pas la
            // servir (size = -1, openStream renvoie un listing texte).
            if (relative.isEmpty() || relative.endsWith("/")) {
                resourcePath = resourcePath.isEmpty() || resourcePath.endsWith("/")
                        ? resourcePath + indexFile
                        : resourcePath + "/" + indexFile;
            }

            // Fast path : entrée précalculée au build par chappe-static-index-maven-plugin.
            // Skip URLConnection.openConnection() entièrement — tout est déjà connu.
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

            // Slow path : lookup classique via URL.openConnection (hors index).
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
                conn.setUseCaches(false); // évite le lock sur les jar files
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
                        null); // pas de filePath pour classpath (pas de zero-copy)
            } catch (IOException _) {
                return null;
            }
        }

        private static String lastSegment(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /** Métadonnées d'une ressource classpath précalculées par le plugin Maven. */
    private record IndexedEntry(long size, long mtime, String mime, String etag) {}

    /**
     * Index chargé 1× par ClassLoader depuis {@code META-INF/chappe-static-index.properties}.
     * Absent ⇒ map vide ⇒ fallback sur le chemin runtime classique (aucune régression).
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
                            // entrée corrompue, on ignore
                        }
                    }
                }
            } catch (IOException _) {
                return Collections.emptyMap();
            }
            return merged.isEmpty() ? Collections.emptyMap() : Map.copyOf(merged);
        }
    }

    // ── Builder ──

    public static final class Builder {
        private final List<ResourceSource> sources = new ArrayList<>();
        private String indexFile = "index.html";
        private String cacheControl;
        private boolean cacheInMemory;
        private String notFoundFile;
        private String spaFallback;
        private boolean preferPrecompressed;

        private Builder() {}

        /** Ajoute un répertoire filesystem comme source (zero-copy via sendfile). */
        public Builder addPath(Path root) {
            sources.add(new PathSource(root.toAbsolutePath().normalize()));
            return this;
        }

        /** Ajoute un chemin classpath comme source (jar, module, META-INF/resources). */
        public Builder addClasspath(String basePath) {
            return addClasspath(Thread.currentThread().getContextClassLoader(), basePath);
        }

        /** Ajoute un chemin classpath avec un ClassLoader spécifique. */
        public Builder addClasspath(ClassLoader loader, String basePath) {
            // Normaliser : pas de / en début/fin
            String normalized = basePath;
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            sources.add(new ClasspathSource(loader, normalized));
            return this;
        }

        /** Fichier index pour les répertoires (défaut : "index.html"). */
        public Builder indexFile(String indexFile) {
            this.indexFile = indexFile;
            return this;
        }

        /** Header Cache-Control ajouté à chaque réponse (ex: "max-age=3600, public"). */
        public Builder cacheControl(String cacheControl) {
            this.cacheControl = cacheControl;
            return this;
        }

        /** Active le cache en mémoire pour les petites ressources classpath (< 64 Ko). */
        public Builder cacheInMemory(boolean enabled) {
            this.cacheInMemory = enabled;
            return this;
        }

        /**
         * Fichier servi avec status 404 quand la ressource demandée n'existe pas.
         * Mutuellement exclusif avec {@link #spaFallback(String)}.
         */
        public Builder notFoundFile(String path) {
            this.notFoundFile = path;
            return this;
        }

        /**
         * Fichier servi avec status 200 quand la ressource demandée n'existe pas
         * (typiquement {@code /index.html} pour les SPA à routing client).
         * Mutuellement exclusif avec {@link #notFoundFile(String)}.
         */
        public Builder spaFallback(String path) {
            this.spaFallback = path;
            return this;
        }

        /**
         * Sert les sidecars pré-compressés ({@code path.br}, {@code path.gz}) en
         * priorité sur l'original quand le client les accepte. Aucune génération
         * runtime — les sidecars doivent exister sur le filesystem (typiquement
         * produits par {@code chappe-static-index-maven-plugin}).
         */
        public Builder preferPrecompressed(boolean enabled) {
            this.preferPrecompressed = enabled;
            return this;
        }

        /** Construit le handler. Au moins une source doit être configurée. */
        public Handler build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one source (addPath or addClasspath) is required");
            }
            if (notFoundFile != null && spaFallback != null) {
                throw new IllegalStateException("notFoundFile and spaFallback are mutually exclusive");
            }
            return new StaticFileHandler(
                    sources, indexFile, cacheControl, cacheInMemory, notFoundFile, spaFallback, preferPrecompressed);
        }
    }
}
