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
package io.vidocq.chappe.http;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.vidocq.chappe.api.*;

/**
 * Mutable concrete implementation of {@link Request}.
 * <p>
 * Fields are written directly by {@link HttpRequestParser},
 * then the object is exposed read-only to the {@link Handler}.
 * Reusable via {@link #reset()} for keep-alive connections.
 */
public final class HttpRequestImpl implements Request, Headers {

    private static final int INITIAL_HEADER_CAPACITY = 16;

    // --- Fields written by parser or Http2Connection ---
    HttpMethod method;
    String rawUri;
    HttpVersion version;
    String[] headerNames;
    String[] headerValues;
    int headerCount;
    Body body;
    Headers trailers = Headers.empty();

    // --- Public setters for access from io.vidocq.chappe.http.h2 ---
    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public void setRawUri(String uri) {
        this.rawUri = uri;
    }

    public void setVersion(HttpVersion version) {
        this.version = version;
    }

    public void setBody(Body body) {
        this.body = body;
    }

    public void setTrailers(Headers trailers) {
        this.trailers = trailers != null ? trailers : Headers.empty();
    }

    public int headerCount() {
        return headerCount;
    }

    public String headerName(int i) {
        return headerNames[i];
    }

    public String headerValue(int i) {
        return headerValues[i];
    }

    public void setHeaderCount(int count) {
        this.headerCount = count;
    }

    public void setHeaderName(int i, String name) {
        this.headerNames[i] = name;
    }

    public void setHeaderValue(int i, String value) {
        this.headerValues[i] = value;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
        this.pathInfoCache = null;
    }

    public void setRemoteAddress(java.net.InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public void setLocalAddress(java.net.InetSocketAddress localAddress) {
        this.localAddress = localAddress;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
        this.scheme = secure ? "https" : "http";
    }

    /**
     * Initializes connection information from the socket channel.
     * Called only once when the connection is created.
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

    // --- Enrichment fields (connection + request) ---
    private String contextPath = "";
    private String pathInfoCache;
    private final java.util.LinkedHashMap<String, Object> attributes = new java.util.LinkedHashMap<>();
    private java.net.InetSocketAddress remoteAddress;
    private java.net.InetSocketAddress localAddress;
    private boolean secure;
    private String scheme = "http";

    // --- Lazily computed fields ---
    private URI uri;
    private String path;
    private String query;
    private boolean pathQueryParsed;
    private Map<String, String> queryParams;
    private Map<String, String> pathParams = Collections.emptyMap();

    public HttpRequestImpl() {
        this.headerNames = new String[INITIAL_HEADER_CAPACITY];
        this.headerValues = new String[INITIAL_HEADER_CAPACITY];
        this.body = Body.empty();
    }

    // --- Written by parser ---

    public void addHeader(String name, String value) {
        if (headerCount == headerNames.length) {
            grow();
        }
        headerNames[headerCount] = name;
        headerValues[headerCount] = value;
        headerCount++;
    }

    /** Injected by the router after matching. */
    public void setPathParams(Map<String, String> params) {
        this.pathParams = params;
    }

    /** Resets for reuse on the same connection. */
    void reset() {
        method = null;
        rawUri = null;
        version = null;
        headerCount = 0;
        body = Body.empty();
        trailers = Headers.empty();
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
        // Absolute-form (e.g. proxy): use request-target as-is.
        if (rawUri.regionMatches(true, 0, "http://", 0, 7) || rawUri.regionMatches(true, 0, "https://", 0, 8)) {
            return URI.create(rawUri);
        }
        // Origin-form (RFC 9112 §3.2.1) or HTTP/2 :path: rebuild through Host.
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
        return this;
    }

    // --- Headers interface implementation (avoids ArrayHeaders allocation) ---

    @Override
    public Optional<String> first(String name) {
        var v = firstOrNull(name);
        return v != null ? Optional.of(v) : Optional.empty();
    }

    @Override
    public String firstOrNull(String name) {
        for (int i = 0; i < headerCount; i++) {
            if (headerNames[i] != null && headerNames[i].equalsIgnoreCase(name)) {
                return headerValues[i];
            }
        }
        return null;
    }

    @Override
    public java.util.List<String> all(String name) {
        var result = new java.util.ArrayList<String>();
        for (int i = 0; i < headerCount; i++) {
            if (headerNames[i] != null && headerNames[i].equalsIgnoreCase(name)) {
                result.add(headerValues[i]);
            }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    @Override
    public boolean contains(String name) {
        for (int i = 0; i < headerCount; i++) {
            if (headerNames[i] != null && headerNames[i].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return headerCount;
    }

    @Override
    public java.util.Iterator<Entry> iterator() {
        return new java.util.Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < headerCount;
            }

            @Override
            public Entry next() {
                if (index >= headerCount) throw new java.util.NoSuchElementException();
                var entry = new Entry(headerNames[index], headerValues[index]);
                index++;
                return entry;
            }
        };
    }

    @Override
    public Body body() {
        return body;
    }

    @Override
    public Headers trailers() {
        return trailers;
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
    public String contextPath() {
        return contextPath;
    }

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
    public Object attribute(String key) {
        return attributes.get(key);
    }

    @Override
    public Request attribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        return this;
    }

    /** Connection-provided hook arming the client-disconnect probe (may be null). */
    @FunctionalInterface
    public interface DisconnectArmer {
        boolean arm(Runnable callback);
    }

    private DisconnectArmer disconnectArmer;

    /** Installed once per connection by {@code HttpConnection}. */
    public void disconnectArmer(DisconnectArmer armer) {
        this.disconnectArmer = armer;
    }

    @Override
    public boolean onDisconnect(Runnable callback) {
        var armer = this.disconnectArmer;
        return armer != null && callback != null && armer.arm(callback);
    }

    @Override
    public java.net.InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public java.net.InetSocketAddress localAddress() {
        return localAddress;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String scheme() {
        return scheme;
    }

    // --- Private helpers ---

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
