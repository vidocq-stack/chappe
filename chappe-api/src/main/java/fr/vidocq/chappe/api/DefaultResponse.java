package fr.vidocq.chappe.api;

/**
 * Implémentation immutable de {@link Response} — utilisée par le builder API.
 */
record DefaultResponse(StatusCode status, Headers headers, Body body) implements Response {}
