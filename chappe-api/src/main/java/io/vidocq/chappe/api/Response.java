package io.vidocq.chappe.api;

/**
 * Réponse HTTP — immutable une fois construite.
 * <p>
 * Créer via les factories statiques ou le {@link Builder}.
 */
public interface Response {

    /** Code de statut HTTP. */
    StatusCode status();

    /** En-têtes de la réponse. */
    Headers headers();

    /** Corps de la réponse. */
    Body body();

    /**
     * Trailers HTTP envoyés après le corps (HTTP/2 §8.1, RFC 9113 §8.1, chunked HTTP/1.1 §7.1.2).
     * <p>
     * Vide par défaut. Appelé par la couche transport <b>après</b> que {@link #body()}
     * ait été entièrement consommé : une implémentation custom peut donc calculer les
     * trailers à la volée (ex. {@code grpc-status} en gRPC streaming).
     * <p>
     * En HTTP/1.1, l'application doit explicitement annoncer les noms via le header
     * {@code Trailer:} et utiliser l'encodage chunked.
     */
    default Headers trailers() {
        return Headers.empty();
    }

    // --- Factories ---

    /** Réponse 200 OK "ok" pré-allouée (hot path benchmark). */
    Response OK_TEXT = Response.ok("ok");

    /** 200 OK sans corps. */
    static Response ok() {
        return builder().status(StatusCode.OK).build();
    }

    /** 200 OK avec un corps texte UTF-8. */
    static Response ok(String text) {
        return builder()
                .status(StatusCode.OK)
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(text)
                .build();
    }

    /** 200 OK avec un corps {@link Body}. */
    static Response ok(Body body) {
        return builder().status(StatusCode.OK).body(body).build();
    }

    /** Réponse avec le statut donné, sans corps. */
    static Response of(StatusCode status) {
        return builder().status(status).build();
    }

    /** Réponse avec le statut et le corps donnés. */
    static Response of(StatusCode status, Body body) {
        return builder().status(status).body(body).build();
    }

    /** Crée un nouveau builder. */
    static Builder builder() {
        return new DefaultResponseBuilder();
    }

    /** Builder fluide pour construire une {@link Response}. */
    interface Builder {

        Builder status(StatusCode status);

        Builder header(String name, String value);

        Builder headers(Headers headers);

        /** Ajoute un trailer (HTTP/2 ou chunked HTTP/1.1). */
        Builder trailer(String name, String value);

        /** Remplace l'ensemble des trailers. */
        Builder trailers(Headers trailers);

        Builder body(Body body);

        Builder body(String text);

        Builder body(byte[] bytes);

        Response build();
    }
}
