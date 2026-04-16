package fr.vidocq.chappe.http;

import fr.vidocq.chappe.api.HttpMethod;
import fr.vidocq.chappe.api.HttpVersion;
import fr.vidocq.chappe.api.ServerConfig;
import fr.vidocq.chappe.api.StatusCode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Parser incrémental HTTP/1.1 (RFC 9112) — state machine sur {@link ByteBuffer}.
 * <p>
 * Lit depuis le buffer et remplit directement un {@link HttpRequestImpl}.
 * Bloque sur le channel quand le buffer est épuisé (virtual threads).
 */
public final class HttpRequestParser {

    private static final int CR = '\r';
    private static final int LF = '\n';
    private static final int SP = ' ';
    private static final int HTAB = '\t';
    private static final int COLON = ':';

    private static final int MAX_URI_LENGTH = 8192;
    private static final int MAX_HEADER_COUNT = 100;

    // Headers courants internés pour éviter les allocations
    private static final String H_HOST = "Host";
    private static final String H_CONTENT_LENGTH = "Content-Length";
    private static final String H_CONTENT_TYPE = "Content-Type";
    private static final String H_CONNECTION = "Connection";
    private static final String H_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String H_ACCEPT = "Accept";
    private static final String H_USER_AGENT = "User-Agent";

    enum State {
        REQUEST_LINE_METHOD,
        REQUEST_LINE_URI,
        REQUEST_LINE_VERSION,
        HEADER_LINE_START,
        HEADER_NAME,
        HEADER_VALUE_OWS,
        HEADER_VALUE,
        COMPLETE
    }

    private State state;
    private final StringBuilder token = new StringBuilder(256);
    private String currentHeaderName;
    private int headerBytesRead;

    public HttpRequestParser() {
        reset();
    }

    /** Réinitialise le parser pour la prochaine requête. */
    public void reset() {
        state = State.REQUEST_LINE_METHOD;
        token.setLength(0);
        currentHeaderName = null;
        headerBytesRead = 0;
    }

    /**
     * Parse une requête depuis le channel dans l'objet request.
     *
     * @param buffer  le buffer de lecture (partagé avec la connexion)
     * @param channel le channel socket pour lire plus de données
     * @param target  l'objet requête à remplir
     * @param config  la configuration serveur (limites)
     * @return résultat du parsing
     * @throws ParseException si la requête est malformée
     * @throws IOException    si une erreur I/O survient
     */
    public ParseResult parse(ByteBuffer buffer, ReadableByteChannel channel,
                             HttpRequestImpl target, ServerConfig config)
            throws ParseException, IOException {

        while (true) {
            if (!buffer.hasRemaining()) {
                buffer.compact();
                int read = channel.read(buffer);
                if (read == -1) {
                    return ParseResult.CONNECTION_CLOSED;
                }
                buffer.flip();
            }

            while (buffer.hasRemaining()) {
                int b = buffer.get() & 0xFF;
                headerBytesRead++;

                if (headerBytesRead > config.maxHeaderSize()) {
                    throw new ParseException(
                            StatusCode.of(431, "Request Header Fields Too Large"),
                            "Headers exceed " + config.maxHeaderSize() + " bytes");
                }

                switch (state) {
                    case REQUEST_LINE_METHOD -> {
                        if (b == SP) {
                            target.method = resolveMethod(token);
                            token.setLength(0);
                            state = State.REQUEST_LINE_URI;
                        } else if (b == CR || b == LF) {
                            // Lignes vides avant la request-line : ignorer (RFC 9112 §2.2)
                            if (token.isEmpty()) {
                                headerBytesRead = 0; // ne pas compter les lignes vides
                            } else {
                                throw badRequest("Unexpected line break in request method");
                            }
                        } else {
                            token.append((char) b);
                        }
                    }

                    case REQUEST_LINE_URI -> {
                        if (b == SP) {
                            if (token.length() > MAX_URI_LENGTH) {
                                throw new ParseException(StatusCode.URI_TOO_LONG,
                                        "URI exceeds " + MAX_URI_LENGTH + " bytes");
                            }
                            target.rawUri = token.toString();
                            token.setLength(0);
                            state = State.REQUEST_LINE_VERSION;
                        } else if (b == CR || b == LF) {
                            throw badRequest("Unexpected line break in URI");
                        } else {
                            token.append((char) b);
                        }
                    }

                    case REQUEST_LINE_VERSION -> {
                        if (b == CR) {
                            // Version terminée, attend LF
                        } else if (b == LF) {
                            target.version = resolveVersion(token);
                            token.setLength(0);
                            state = State.HEADER_LINE_START;
                        } else {
                            token.append((char) b);
                        }
                    }

                    case HEADER_LINE_START -> {
                        if (b == CR) {
                            // Début de la ligne vide terminale
                        } else if (b == LF) {
                            // Fin des headers
                            state = State.COMPLETE;
                            return ParseResult.COMPLETE;
                        } else if (b == SP || b == HTAB) {
                            // Obsolete line folding (RFC 9112 §5.2)
                            throw badRequest("Obsolete header line folding not supported");
                        } else {
                            token.setLength(0);
                            token.append((char) b);
                            state = State.HEADER_NAME;
                        }
                    }

                    case HEADER_NAME -> {
                        if (b == COLON) {
                            currentHeaderName = internHeaderName(token);
                            token.setLength(0);
                            state = State.HEADER_VALUE_OWS;
                        } else if (b == SP || b == HTAB) {
                            throw badRequest("Space before colon in header name");
                        } else if (b == CR || b == LF) {
                            throw badRequest("Unexpected line break in header name");
                        } else {
                            token.append((char) b);
                        }
                    }

                    case HEADER_VALUE_OWS -> {
                        if (b == SP || b == HTAB) {
                            // Skip OWS
                        } else if (b == CR) {
                            // Valeur vide
                        } else if (b == LF) {
                            // Header avec valeur vide
                            addHeader(target, currentHeaderName, "");
                            state = State.HEADER_LINE_START;
                        } else {
                            token.setLength(0);
                            token.append((char) b);
                            state = State.HEADER_VALUE;
                        }
                    }

                    case HEADER_VALUE -> {
                        if (b == CR) {
                            // Fin de la valeur, attend LF
                        } else if (b == LF) {
                            // Trim trailing OWS
                            var value = trimTrailingOws(token);
                            addHeader(target, currentHeaderName, value);
                            state = State.HEADER_LINE_START;
                        } else {
                            token.append((char) b);
                        }
                    }

                    case COMPLETE -> {
                        // Ne devrait pas arriver ici
                        return ParseResult.COMPLETE;
                    }
                }
            }
        }
    }

    // --- Helpers ---

    private static HttpMethod resolveMethod(StringBuilder sb) throws ParseException {
        // Fast path pour les méthodes courantes
        return switch (sb.length()) {
            case 3 -> {
                if (matches(sb, "GET")) yield HttpMethod.GET;
                if (matches(sb, "PUT")) yield HttpMethod.PUT;
                yield parseMethodSlow(sb);
            }
            case 4 -> {
                if (matches(sb, "POST")) yield HttpMethod.POST;
                if (matches(sb, "HEAD")) yield HttpMethod.HEAD;
                yield parseMethodSlow(sb);
            }
            case 5 -> {
                if (matches(sb, "PATCH")) yield HttpMethod.PATCH;
                if (matches(sb, "TRACE")) yield HttpMethod.TRACE;
                yield parseMethodSlow(sb);
            }
            case 6 -> {
                if (matches(sb, "DELETE")) yield HttpMethod.DELETE;
                yield parseMethodSlow(sb);
            }
            case 7 -> {
                if (matches(sb, "OPTIONS")) yield HttpMethod.OPTIONS;
                if (matches(sb, "CONNECT")) yield HttpMethod.CONNECT;
                yield parseMethodSlow(sb);
            }
            default -> parseMethodSlow(sb);
        };
    }

    private static HttpMethod parseMethodSlow(StringBuilder sb) throws ParseException {
        try {
            return HttpMethod.of(sb.toString());
        } catch (IllegalArgumentException e) {
            throw new ParseException(StatusCode.NOT_IMPLEMENTED,
                    "Unknown method: " + sb);
        }
    }

    private static boolean matches(StringBuilder sb, String expected) {
        if (sb.length() != expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (sb.charAt(i) != expected.charAt(i)) return false;
        }
        return true;
    }

    private static HttpVersion resolveVersion(StringBuilder sb) throws ParseException {
        if (matches(sb, "HTTP/1.1")) return HttpVersion.HTTP_1_1;
        if (matches(sb, "HTTP/1.0")) return HttpVersion.HTTP_1_0;
        throw badRequest("Unsupported HTTP version: " + sb);
    }

    private static String internHeaderName(StringBuilder sb) {
        // Interning des noms courants pour éviter les allocations
        if (matchesIgnoreCase(sb, H_HOST)) return H_HOST;
        if (matchesIgnoreCase(sb, H_CONTENT_LENGTH)) return H_CONTENT_LENGTH;
        if (matchesIgnoreCase(sb, H_CONTENT_TYPE)) return H_CONTENT_TYPE;
        if (matchesIgnoreCase(sb, H_CONNECTION)) return H_CONNECTION;
        if (matchesIgnoreCase(sb, H_TRANSFER_ENCODING)) return H_TRANSFER_ENCODING;
        if (matchesIgnoreCase(sb, H_ACCEPT)) return H_ACCEPT;
        if (matchesIgnoreCase(sb, H_USER_AGENT)) return H_USER_AGENT;
        return sb.toString();
    }

    private static boolean matchesIgnoreCase(StringBuilder sb, String expected) {
        if (sb.length() != expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (Character.toLowerCase(sb.charAt(i)) != Character.toLowerCase(expected.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // Valeurs de headers courantes internées
    private static final String V_KEEP_ALIVE = "keep-alive";
    private static final String V_CLOSE = "close";
    private static final String V_CHUNKED = "chunked";
    private static final String V_GZIP = "gzip";
    private static final String V_GZIP_DEFLATE = "gzip, deflate";
    private static final String V_TEXT_HTML = "text/html";
    private static final String V_TEXT_PLAIN = "text/plain";
    private static final String V_APP_JSON = "application/json";
    private static final String V_APP_FORM = "application/x-www-form-urlencoded";
    private static final String V_ZERO = "0";

    private static String trimTrailingOws(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == ' ' || sb.charAt(end - 1) == '\t')) {
            end--;
        }
        // Fast path : pas de trimming nécessaire
        if (end == sb.length()) {
            return internValue(sb);
        }
        // Slow path : crée une substring
        sb.setLength(end);
        return internValue(sb);
    }

    /** Intern des valeurs de headers courantes pour éviter les allocations. */
    private static String internValue(StringBuilder sb) {
        return switch (sb.length()) {
            case 1 -> {
                if (sb.charAt(0) == '0') yield V_ZERO;
                yield sb.toString();
            }
            case 4 -> {
                if (matchesIgnoreCase(sb, V_GZIP)) yield V_GZIP;
                yield sb.toString();
            }
            case 5 -> {
                if (matchesIgnoreCase(sb, V_CLOSE)) yield V_CLOSE;
                yield sb.toString();
            }
            case 7 -> {
                if (matchesIgnoreCase(sb, V_CHUNKED)) yield V_CHUNKED;
                yield sb.toString();
            }
            case 9 -> {
                if (matchesIgnoreCase(sb, V_TEXT_HTML)) yield V_TEXT_HTML;
                yield sb.toString();
            }
            case 10 -> {
                if (matchesIgnoreCase(sb, V_KEEP_ALIVE)) yield V_KEEP_ALIVE;
                if (matchesIgnoreCase(sb, V_TEXT_PLAIN)) yield V_TEXT_PLAIN;
                yield sb.toString();
            }
            case 13 -> {
                if (matchesIgnoreCase(sb, V_GZIP_DEFLATE)) yield V_GZIP_DEFLATE;
                yield sb.toString();
            }
            case 16 -> {
                if (matchesIgnoreCase(sb, V_APP_JSON)) yield V_APP_JSON;
                yield sb.toString();
            }
            default -> sb.toString();
        };
    }

    private void addHeader(HttpRequestImpl target, String name, String value)
            throws ParseException {
        if (target.headerCount >= MAX_HEADER_COUNT) {
            throw new ParseException(
                    StatusCode.of(431, "Request Header Fields Too Large"),
                    "Too many headers (max " + MAX_HEADER_COUNT + ")");
        }
        target.addHeader(name, value);
    }

    private static ParseException badRequest(String message) {
        return new ParseException(StatusCode.BAD_REQUEST, message);
    }
}
