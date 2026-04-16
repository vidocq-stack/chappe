package fr.vidocq.chappe.api;

import java.nio.file.Path;
import java.util.Map;

/**
 * Détection de type MIME basée sur l'extension de fichier.
 * Couvre les types les plus courants pour un serveur HTTP.
 */
public final class MimeTypes {

    private MimeTypes() {}

    // ---- Constants ----

    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_CSS = "text/css";
    public static final String TEXT_JAVASCRIPT = "text/javascript";
    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_XML = "application/xml";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_SVG = "image/svg+xml";

    // ---- Extension → MIME mapping ----

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", TEXT_HTML),
            Map.entry("htm", TEXT_HTML),
            Map.entry("css", TEXT_CSS),
            Map.entry("js", TEXT_JAVASCRIPT),
            Map.entry("mjs", TEXT_JAVASCRIPT),
            Map.entry("json", APPLICATION_JSON),
            Map.entry("xml", APPLICATION_XML),
            Map.entry("png", IMAGE_PNG),
            Map.entry("jpg", IMAGE_JPEG),
            Map.entry("jpeg", IMAGE_JPEG),
            Map.entry("svg", IMAGE_SVG),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm")
    );

    /**
     * Détecte le type MIME d'un fichier à partir de son chemin.
     *
     * @return le type MIME détecté, ou {@code application/octet-stream} par défaut
     */
    public static String detect(Path path) {
        return detect(path.getFileName().toString());
    }

    /**
     * Détecte le type MIME à partir d'un nom de fichier.
     *
     * @return le type MIME détecté, ou {@code application/octet-stream} par défaut
     */
    public static String detect(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return APPLICATION_OCTET_STREAM;
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        return TYPES.getOrDefault(ext, APPLICATION_OCTET_STREAM);
    }
}
