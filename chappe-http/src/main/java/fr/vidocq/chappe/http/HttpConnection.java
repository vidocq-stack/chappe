package fr.vidocq.chappe.http;

import fr.vidocq.chappe.api.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Gestion d'une connexion HTTP/1.1 — boucle keep-alive.
 * <p>
 * Exécuté sur un virtual thread par {@code chappe-core}.
 * Orchestre : parse → body setup → dispatch handler → write response → loop.
 */
public final class HttpConnection {

    private static final int BUFFER_SIZE = 16 * 1024; // 16 Ko

    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Closeable closeable;
    private final Handler handler;
    private final ServerConfig config;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final HttpRequestParser parser;
    private final HttpResponseWriter writer;
    private final HttpRequestImpl request;
    private volatile boolean open = true;

    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config) {
        this(channel, channel, channel, handler, config, null, null);
    }

    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config,
                          ByteBuffer prefilledBuffer) {
        this(channel, channel, channel, handler, config, prefilledBuffer, null);
    }

    /**
     * Constructeur pour TLS — accepte des channels séparés (SslHandler).
     */
    public HttpConnection(ReadableByteChannel readChannel, WritableByteChannel writeChannel,
                          Closeable closeable, Handler handler, ServerConfig config,
                          ByteBuffer prefilledBuffer, ByteBuffer writeBuffer) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.closeable = closeable;
        this.handler = handler;
        this.config = config;
        if (prefilledBuffer != null) {
            this.readBuffer = prefilledBuffer;
        } else {
            this.readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            readBuffer.flip();
        }
        this.writeBuffer = writeBuffer != null ? writeBuffer : ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.parser = new HttpRequestParser();
        this.writer = new HttpResponseWriter();
        this.request = new HttpRequestImpl();

        // Populate connection-level metadata once
        if (closeable instanceof SocketChannel sc) {
            request.initConnectionInfo(sc, false);
        }
    }

    /**
     * Point d'entrée — appelé par chappe-core sur un virtual thread.
     * Boucle jusqu'à fermeture de la connexion ou erreur.
     */
    public void run() {
        try {
            while (open) {
                parser.reset();
                request.reset();

                // 1. Parser la requête (request-line + headers)
                ParseResult result;
                try {
                    result = parser.parse(readBuffer, readChannel, request, config);
                } catch (ParseException e) {
                    sendError(e.statusCode(), e.getMessage());
                    break;
                }

                if (result == ParseResult.CONNECTION_CLOSED) {
                    break;
                }

                // 2. Validation Host header (RFC 9112 §3.2)
                if (request.version() == HttpVersion.HTTP_1_1) {
                    var hostValues = request.headers().all("Host");
                    if (hostValues.isEmpty()) {
                        sendError(StatusCode.BAD_REQUEST, "Missing Host header");
                        break;
                    }
                    if (hostValues.size() > 1) {
                        sendError(StatusCode.BAD_REQUEST, "Multiple Host headers");
                        break;
                    }
                }

                // 3. Expect: 100-continue (RFC 9110 §10.1.1)
                if ("100-continue".equalsIgnoreCase(
                        request.headers().firstOrNull("Expect"))) {
                    // Envoyer 100 Continue avant la lecture du body
                    var continueBytes = "HTTP/1.1 100 Continue\r\n\r\n".getBytes();
                    var buf = ByteBuffer.wrap(continueBytes);
                    while (buf.hasRemaining()) {
                        writeChannel.write(buf);
                    }
                }

                // 4. Setup du body
                InputStream bodyStream;
                try {
                    bodyStream = setupBody();
                } catch (BadBodyException e) {
                    sendError(StatusCode.BAD_REQUEST, e.getMessage());
                    break;
                }
                if (bodyStream != null) {
                    request.body = Body.of(bodyStream, contentLength());
                }

                // 3. Dispatch au handler avec ScopedValue binding
                boolean keepAlive = isKeepAlive();
                Response response;
                try {
                    var ctx = new RequestContext(request);
                    response = ScopedValue.where(RequestContext.CURRENT, ctx)
                            .call(() -> handler.handle(request));
                } catch (Exception e) {
                    response = Response.builder()
                            .status(StatusCode.INTERNAL_SERVER_ERROR)
                            .body("Internal Server Error")
                            .build();
                }

                // 4. Écrire la réponse
                writer.write(response, writeBuffer, writeChannel, keepAlive, request.method());

                // 5. Drainer le body non lu (pour keep-alive)
                if (bodyStream != null) {
                    HttpBodyReader.drain(bodyStream);
                }

                // 6. Vérifier keep-alive
                if (!keepAlive) {
                    break;
                }
            }
        } catch (IOException _) {
            // Connexion perdue — silencieux
        } finally {
            close();
        }
    }

    /** Ferme la connexion. */
    public void close() {
        open = false;
        try {
            closeable.close();
        } catch (IOException _) {
            // Ignore
        }
    }

    // --- Helpers internes ---

    /**
     * Setup du body de la requête.
     * @return l'InputStream du body, ou null si pas de body
     * @throws BadBodyException si le Content-Length est invalide ou dépasse la limite
     */
    private InputStream setupBody() throws BadBodyException {
        var headers = request.headers();
        var transferEncoding = headers.firstOrNull("Transfer-Encoding");
        var contentLengthStr = headers.firstOrNull("Content-Length");

        // Transfer-Encoding: chunked gagne sur Content-Length (RFC 9112 §6.3)
        if ("chunked".equalsIgnoreCase(transferEncoding)) {
            return HttpBodyReader.chunked(readBuffer, readChannel);
        }

        if (contentLengthStr != null) {
            long cl;
            try {
                cl = Long.parseLong(contentLengthStr);
            } catch (NumberFormatException _) {
                throw new BadBodyException("Invalid Content-Length: " + contentLengthStr);
            }
            if (cl < 0) {
                throw new BadBodyException("Negative Content-Length: " + cl);
            }
            if (cl == 0) return null;
            if (cl > config.maxRequestSize()) {
                throw new BadBodyException("Content-Length " + cl + " exceeds max " + config.maxRequestSize());
            }
            return HttpBodyReader.fixedLength(readBuffer, readChannel, cl);
        }

        return null;
    }

    private static final class BadBodyException extends Exception {
        BadBodyException(String message) { super(message); }
    }

    private long contentLength() {
        var headers = request.headers();
        var transferEncoding = headers.firstOrNull("Transfer-Encoding");
        if ("chunked".equalsIgnoreCase(transferEncoding)) return -1;

        return headers.first("Content-Length")
                .map(s -> {
                    try { return Long.parseLong(s); }
                    catch (NumberFormatException _) { return -1L; }
                })
                .orElse(-1L);
    }

    private boolean isKeepAlive() {
        var connection = request.headers().firstOrNull("Connection");
        if (request.version() == HttpVersion.HTTP_1_1) {
            // HTTP/1.1 : keep-alive par défaut, sauf si Connection: close
            return !"close".equalsIgnoreCase(connection);
        }
        // HTTP/1.0 : close par défaut, sauf si Connection: keep-alive
        return "keep-alive".equalsIgnoreCase(connection);
    }

    private void sendError(StatusCode status, String message) {
        try {
            writer.writeError(status, message, writeBuffer, writeChannel);
        } catch (IOException _) {
            // Connexion déjà perdue
        }
    }
}
