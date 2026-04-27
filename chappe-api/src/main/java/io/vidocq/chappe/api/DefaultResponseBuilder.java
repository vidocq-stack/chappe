package io.vidocq.chappe.api;

/**
 * Implémentation par défaut de {@link Response.Builder}.
 */
final class DefaultResponseBuilder implements Response.Builder {

    private StatusCode status = StatusCode.OK;
    private final Headers.Builder headersBuilder = Headers.builder();
    private Headers headers;
    private Body body = Body.empty();

    @Override
    public Response.Builder status(StatusCode status) {
        this.status = status;
        return this;
    }

    @Override
    public Response.Builder header(String name, String value) {
        headersBuilder.add(name, value);
        return this;
    }

    @Override
    public Response.Builder headers(Headers headers) {
        this.headers = headers;
        return this;
    }

    @Override
    public Response.Builder body(Body body) {
        this.body = body;
        return this;
    }

    @Override
    public Response.Builder body(String text) {
        this.body = Body.of(text);
        return this;
    }

    @Override
    public Response.Builder body(byte[] bytes) {
        this.body = Body.of(bytes);
        return this;
    }

    @Override
    public Response build() {
        var h = headers != null ? headers : headersBuilder.build();
        return new DefaultResponse(status, h, body);
    }
}
