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

/**
 * Default implementation of {@link Response.Builder}.
 */
final class DefaultResponseBuilder implements Response.Builder {

    private StatusCode status = StatusCode.OK;
    private final Headers.Builder headersBuilder = Headers.builder();
    private Headers headers;
    private Headers.Builder trailersBuilder;
    private Headers trailers;
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
    public Response.Builder trailer(String name, String value) {
        if (trailersBuilder == null) trailersBuilder = Headers.builder();
        trailersBuilder.add(name, value);
        return this;
    }

    @Override
    public Response.Builder trailers(Headers trailers) {
        this.trailers = trailers;
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
        Headers t;
        if (trailers != null) t = trailers;
        else if (trailersBuilder != null) t = trailersBuilder.build();
        else t = Headers.empty();
        return new DefaultResponse(status, h, body, t);
    }
}
