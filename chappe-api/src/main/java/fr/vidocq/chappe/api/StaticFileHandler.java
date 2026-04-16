package fr.vidocq.chappe.api;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public final class StaticFileHandler implements Handler {
    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private final Path root;
    private final String indexFile;

    private StaticFileHandler(Path root, String indexFile) {
        this.root = root.toAbsolutePath().normalize();
        this.indexFile = indexFile;
    }

    public static Handler of(Path root) { return new StaticFileHandler(root, "index.html"); }
    public static Handler of(Path root, String indexFile) { return new StaticFileHandler(root, indexFile); }

    @Override
    public Response handle(Request request) throws Exception {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return Response.of(StatusCode.METHOD_NOT_ALLOWED);
        }

        String requestPath = request.pathInfo();
        if (requestPath.contains("..") || requestPath.contains("\0")) {
            return Response.of(StatusCode.FORBIDDEN);
        }

        // Remove leading slash and resolve against root
        String relative = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        Path file = root.resolve(relative).normalize();

        if (!file.startsWith(root)) {
            return Response.of(StatusCode.FORBIDDEN);
        }

        if (Files.isDirectory(file)) {
            file = file.resolve(indexFile);
        }

        if (!Files.isRegularFile(file)) {
            return Response.of(StatusCode.NOT_FOUND);
        }

        // If-Modified-Since / Last-Modified
        Instant lastModified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
        String ims = request.header("If-Modified-Since").orElse(null);
        if (ims != null) {
            try {
                Instant clientTime = ZonedDateTime.parse(ims, IMF_FIXDATE).toInstant();
                if (!lastModified.isAfter(clientTime)) {
                    return Response.of(StatusCode.NOT_MODIFIED);
                }
            } catch (Exception _) { /* invalid date, serve normally */ }
        }

        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", MimeTypes.detect(file))
                .header("Last-Modified", IMF_FIXDATE.format(lastModified))
                .body(Body.ofFile(file))
                .build();
    }
}
