package io.vidocq.chappe.api;

import java.nio.file.Path;

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

    // Ordonné par fréquence approximative sur le web (hit rapide sur html/css/js/png/jpg).
    // Lookup zero-alloc via String.regionMatches(true, ...) — évite substring + toLowerCase.
    private static final String[][] ENTRIES = {
            { "html",  TEXT_HTML },
            { "css",   TEXT_CSS },
            { "js",    TEXT_JAVASCRIPT },
            { "png",   IMAGE_PNG },
            { "jpg",   IMAGE_JPEG },
            { "svg",   IMAGE_SVG },
            { "json",  APPLICATION_JSON },
            { "woff2", "font/woff2" },
            { "ico",   "image/x-icon" },
            { "webp",  "image/webp" },
            { "htm",   TEXT_HTML },
            { "mjs",   TEXT_JAVASCRIPT },
            { "jpeg",  IMAGE_JPEG },
            { "gif",   "image/gif" },
            { "woff",  "font/woff" },
            { "ttf",   "font/ttf" },
            { "otf",   "font/otf" },
            { "xml",   APPLICATION_XML },
            { "txt",   "text/plain" },
            { "csv",   "text/csv" },
            { "pdf",   "application/pdf" },
            { "zip",   "application/zip" },
            { "gz",    "application/gzip" },
            { "wasm",  "application/wasm" },
            { "mp4",   "video/mp4" },
            { "webm",  "video/webm" },
    };

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
        int len = filename.length();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == len - 1) {
            return APPLICATION_OCTET_STREAM;
        }
        int off = dot + 1;
        int extLen = len - off;
        for (String[] e : ENTRIES) {
            String ext = e[0];
            if (ext.length() == extLen
                    && filename.regionMatches(true, off, ext, 0, extLen)) {
                return e[1];
            }
        }
        return APPLICATION_OCTET_STREAM;
    }
}
