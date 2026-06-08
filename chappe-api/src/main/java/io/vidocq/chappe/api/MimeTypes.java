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
package io.vidocq.chappe.api;

import java.nio.file.Path;

/**
 * MIME type detection based on the file extension.
 * Covers the most common types for an HTTP server.
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

    // Ordered by approximate web frequency (fast hits on html/css/js/png/jpg).
    // Zero-allocation lookup via String.regionMatches(true, ...) — avoids substring + toLowerCase.
    private static final String[][] ENTRIES = {
        {"html", TEXT_HTML},
        {"css", TEXT_CSS},
        {"js", TEXT_JAVASCRIPT},
        {"png", IMAGE_PNG},
        {"jpg", IMAGE_JPEG},
        {"svg", IMAGE_SVG},
        {"json", APPLICATION_JSON},
        {"woff2", "font/woff2"},
        {"ico", "image/x-icon"},
        {"webp", "image/webp"},
        {"htm", TEXT_HTML},
        {"mjs", TEXT_JAVASCRIPT},
        {"jpeg", IMAGE_JPEG},
        {"gif", "image/gif"},
        {"woff", "font/woff"},
        {"ttf", "font/ttf"},
        {"otf", "font/otf"},
        {"xml", APPLICATION_XML},
        {"txt", "text/plain"},
        {"csv", "text/csv"},
        {"pdf", "application/pdf"},
        {"zip", "application/zip"},
        {"gz", "application/gzip"},
        {"wasm", "application/wasm"},
        {"mp4", "video/mp4"},
        {"webm", "video/webm"},
    };

    /**
     * Detects the MIME type of a file from its path.
     *
     * @return the detected MIME type, or {@code application/octet-stream} by default
     */
    public static String detect(Path path) {
        return detect(path.getFileName().toString());
    }

    /**
     * Detects the MIME type from a file name.
     *
     * @return the detected MIME type, or {@code application/octet-stream} by default
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
            if (ext.length() == extLen && filename.regionMatches(true, off, ext, 0, extLen)) {
                return e[1];
            }
        }
        return APPLICATION_OCTET_STREAM;
    }
}
