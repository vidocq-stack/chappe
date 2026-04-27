package io.vidocq.chappe.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache des codes de statut HTTP standard — accès O(1).
 */
final class StatusCodeCache {

    private static final Map<Integer, StatusCode.Standard> CACHE;

    static {
        var map = new HashMap<Integer, StatusCode.Standard>();
        // Forcer l'initialisation des constantes de StatusCode,
        // puis les indexer par code.
        var constants = new StatusCode[] {
                StatusCode.OK, StatusCode.CREATED, StatusCode.ACCEPTED, StatusCode.NO_CONTENT,
                StatusCode.MOVED_PERMANENTLY, StatusCode.FOUND, StatusCode.NOT_MODIFIED,
                StatusCode.TEMPORARY_REDIRECT, StatusCode.PERMANENT_REDIRECT,
                StatusCode.BAD_REQUEST, StatusCode.UNAUTHORIZED, StatusCode.FORBIDDEN,
                StatusCode.NOT_FOUND, StatusCode.METHOD_NOT_ALLOWED, StatusCode.NOT_ACCEPTABLE,
                StatusCode.REQUEST_TIMEOUT, StatusCode.CONFLICT, StatusCode.GONE,
                StatusCode.LENGTH_REQUIRED, StatusCode.PAYLOAD_TOO_LARGE, StatusCode.URI_TOO_LONG,
                StatusCode.UNSUPPORTED_MEDIA_TYPE, StatusCode.TOO_MANY_REQUESTS,
                StatusCode.INTERNAL_SERVER_ERROR, StatusCode.NOT_IMPLEMENTED,
                StatusCode.BAD_GATEWAY, StatusCode.SERVICE_UNAVAILABLE, StatusCode.GATEWAY_TIMEOUT
        };
        for (var sc : constants) {
            if (sc instanceof StatusCode.Standard std) {
                map.put(std.code(), std);
            }
        }
        CACHE = Map.copyOf(map);
    }

    private StatusCodeCache() {}

    static StatusCode.Standard get(int code) {
        return CACHE.get(code);
    }
}
