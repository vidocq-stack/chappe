package io.vidocq.chappe.http;

import io.vidocq.chappe.api.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implémentation concrète mutable de {@link Request}.
 * <p>
 * Les champs sont écrits directement par {@link HttpRequestParser},
 * puis l'objet est exposé en lecture seule au {@link Handler}.
 * Recyclable via {@link #reset()} pour les connexions keep-alive.
 */
public final class HttpRequestImpl implements Request {

    private static final int INITIAL_HEADER_CAPACITY = 16;

    // --- Champs écrits par le parser ou Http2Connection ---
    HttpMethod method;
    String rawUri;
    HttpVersion version;
    String[] headerNames;
    String[] headerValues;
    int headerCount;
    Body body;

    // --- Setters publics pour accès depuis io.vidocq.chappe.http.h2 ---
    public void setMethod(HttpMethod method) { this.method = method; }
    public void setRawUri(String uri) { this.rawUri = uri; }
    public void setVersion(HttpVersion version) { this.version = version; }
    public void setBody(Body body) { this.body = body; }
    public int headerCount() { return headerCount; }
    public String headerName(int i) { return headerNames[i]; }
    public String headerValue(int i) { return headerValues[i]; }
    public void setHeaderCount(int count) { this.headerCount = count; }
    public void setHeaderName(int i, String name) { this.headerNames[i] = name; }
    public void setHeaderValue(int i, String value) { this.headerValues[i] = value; }

    public void setContextPath(String contextPath) { this.contextPath = contextPath; this.pathInfoCache = null; }
    public void setRemoteAddress(java.net.InetSocketAddress remoteAddress) { this.remoteAddress = remoteAddress; }
    public void setLocalAddress(java.net.InetSocketAddress localAddress) { this.localAddress = localAddress; }
    public void setSecure(boolean secure) { this.secure = secure; this.scheme = secure ? "https" : "http"; }

    /**
     * Initialise les informations de connexion à partir du canal socket.
     * Appelé une seule fois à la création de la connexion.
     */
    public void initConnectionInfo(java.nio.channels.SocketChannel channel, boolean secure) {
        if (channel != null) {
            try {
                this.remoteAddress = (java.net.InetSocketAddress) channel.getRemoteAddress();
                this.localAddress = (java.net.InetSocketAddress) channel.getLocalAddress();
            } catch (java.io.IOException _) {
                // Ignore — addresses remain null
            }
        }
        setSecure(secure);
    }

    // --- Champs d'enrichissement (connexion + requête) ---
    private String contextPath = "";
    private String pathInfoCache;
    private final java.util.LinkedHashMap<String, Object> attributes = new java.util.LinkedHashMap<>();
    private java.net.InetSocketAddress remoteAddress;
    private java.net.InetSocketAddress localAddress;
    private boolean secure;
    private String scheme = "http";

    // --- Champs calculés paresseusement ---
    private URI uri;
    private String path;
    private String query;
    private boolean pathQueryParsed;
    private Map<String, String> queryParams;
    private Headers headersView;
    private Map<String, String> pathParams = Collections.emptyMap();

    public HttpRequestImpl() {
        this.headerNames = new String[INITIAL_HEADER_CAPACITY];
        this.headerValues = new String[INITIAL_HEADER_CAPACITY];
        this.body = Body.empty();
    }

    // --- Écriture par le parser ---

    public void addHeader(String name, String value) {
        if (headerCount == headerNames.length) {
            grow();
        }
        headerNames[headerCount] = name;
        headerValues[headerCount] = value;
        headerCount++;
    }

    /** Injecté par le routeur après le matching. */
    public void setPathParams(Map<String, String> params) {
        this.pathParams = params;
    }

    /** Réinitialise pour réutilisation sur la même connexion. */
    void reset() {
        method = null;
        rawUri = null;
        version = null;
        headerCount = 0;
        headersView = null;
        body = Body.empty();
        uri = null;
        path = null;
        query = null;
        pathQueryParsed = false;
        queryParams = null;
        pathParams = Collections.emptyMap();
        // Per-request fields (NOT connection-level: remoteAddress, localAddress, secure, scheme)
        contextPath = "";
        pathInfoCache = null;
        attributes.clear();
    }

    // --- Request interface ---

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public URI uri() {
        if (uri == null) {
            uri = buildUri();
        }
        return uri;
    }

    private URI buildUri() {
        if (rawUri == null) return URI.create("/");
        // Absolute-form (ex. proxy) : on prend la request-target telle quelle.
        if (rawUri.regionMatches(true, 0, "http://", 0, 7)
                || rawUri.regionMatches(true, 0, "https://", 0, 8)) {
            return URI.create(rawUri);
        }
        // Origin-form (RFC 9112 §3.2.1) ou :path HTTP/2 : reconstruire via Host.
        String host = null;
        for (int i = 0; i < headerCount; i++) {
            if (headerNames[i] != null && headerNames[i].equalsIgnoreCase("Host")) {
                host = headerValues[i];
                break;
            }
        }
        if (host == null || host.isEmpty()) {
            return URI.create(rawUri);
        }
        String path = rawUri.isEmpty() ? "/" : (rawUri.charAt(0) == '/' ? rawUri : "/" + rawUri);
        return URI.create(scheme + "://" + host + path);
    }

    @Override
    public String path() {
        ensurePathQueryParsed();
        return path;
    }

    @Override
    public String query() {
        ensurePathQueryParsed();
        return query;
    }

    @Override
    public HttpVersion version() {
        return version;
    }

    @Override
    public Headers headers() {
        if (headersView == null) {
            headersView = new ArrayHeaders(headerNames, headerValues, headerCount);
        }
        return headersView;
    }

    @Override
    public Body body() {
        return body;
    }

    @Override
    public Map<String, String> pathParams() {
        return pathParams;
    }

    @Override
    public Map<String, String> queryParams() {
        if (queryParams == null) {
            queryParams = parseQueryString(query());
        }
        return queryParams;
    }

    @Override
    public String contextPath() { return contextPath; }

    @Override
    public String pathInfo() {
        if (pathInfoCache == null) {
            var p = path();
            if (contextPath.isEmpty() || !p.startsWith(contextPath)) {
                pathInfoCache = p;
            } else {
                var info = p.substring(contextPath.length());
                pathInfoCache = info.isEmpty() ? "/" : info;
            }
        }
        return pathInfoCache;
    }

    @Override
    public Object attribute(String key) { return attributes.get(key); }

    @Override
    public Request attribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        return this;
    }

    @Override
    public java.net.InetSocketAddress remoteAddress() { return remoteAddress; }

    @Override
    public java.net.InetSocketAddress localAddress() { return localAddress; }

    @Override
    public boolean isSecure() { return secure; }

    @Override
    public String scheme() { return scheme; }

    // --- Helpers privés ---

    private void ensurePathQueryParsed() {
        if (pathQueryParsed) return;
        pathQueryParsed = true;
        if (rawUri == null) {
            path = "/";
            query = null;
            return;
        }
        int qIdx = rawUri.indexOf('?');
        if (qIdx == -1) {
            path = rawUri;
            query = null;
        } else {
            path = rawUri.substring(0, qIdx);
            query = rawUri.substring(qIdx + 1);
        }
    }

    private static Map<String, String> parseQueryString(String qs) {
        if (qs == null || qs.isEmpty()) {
            return Collections.emptyMap();
        }
        var map = new LinkedHashMap<String, String>();
        int start = 0;
        while (start <= qs.length()) {
            int ampIdx = qs.indexOf('&', start);
            if (ampIdx == -1) ampIdx = qs.length();
            var pair = qs.substring(start, ampIdx);
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0) {
                var key = URLDecoder.decode(pair.substring(0, eqIdx), StandardCharsets.UTF_8);
                var value = URLDecoder.decode(pair.substring(eqIdx + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            } else if (!pair.isEmpty()) {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
            start = ampIdx + 1;
        }
        return Collections.unmodifiableMap(map);
    }

    private void grow() {
        int newLen = headerNames.length * 2;
        var newNames = new String[newLen];
        var newValues = new String[newLen];
        System.arraycopy(headerNames, 0, newNames, 0, headerCount);
        System.arraycopy(headerValues, 0, newValues, 0, headerCount);
        headerNames = newNames;
        headerValues = newValues;
    }
}
