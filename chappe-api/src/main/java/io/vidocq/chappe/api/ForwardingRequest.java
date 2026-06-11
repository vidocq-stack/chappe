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

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Request} wrapper that forwards <em>every</em> method to
 * {@link #delegate()}. Wrappers (router mount, path params, test harnesses,
 * Servlet/JAX-RS adapters) should extend this and override only what they
 * change.
 *
 * <p>Rationale: a hand-rolled anonymous {@code Request} wrapper silently
 * falls back to the interface defaults for any method added to
 * {@code Request} after it was written — {@code attribute()} (cassini
 * CASSINI-002), then {@code trailers()}/{@code onDisconnect()} (foy
 * BUG-20260611-01) were all lost this way. Every {@code Request} method must
 * be redeclared here; {@code ForwardingRequestParityTest} enforces it
 * reflectively, so adding a method to {@code Request} without forwarding it
 * fails the build instead of silently breaking wrapped transports.</p>
 */
public interface ForwardingRequest extends Request {

    /** The wrapped request every method forwards to. */
    Request delegate();

    @Override
    default HttpMethod method() {
        return delegate().method();
    }

    @Override
    default URI uri() {
        return delegate().uri();
    }

    @Override
    default String path() {
        return delegate().path();
    }

    @Override
    default String query() {
        return delegate().query();
    }

    @Override
    default HttpVersion version() {
        return delegate().version();
    }

    @Override
    default Headers headers() {
        return delegate().headers();
    }

    @Override
    default Body body() {
        return delegate().body();
    }

    @Override
    default Headers trailers() {
        return delegate().trailers();
    }

    @Override
    default Optional<String> header(String name) {
        return delegate().header(name);
    }

    @Override
    default Map<String, String> pathParams() {
        return delegate().pathParams();
    }

    @Override
    default Map<String, String> queryParams() {
        return delegate().queryParams();
    }

    @Override
    default Optional<String> queryParam(String name) {
        return delegate().queryParam(name);
    }

    @Override
    default String contextPath() {
        return delegate().contextPath();
    }

    @Override
    default String pathInfo() {
        return delegate().pathInfo();
    }

    @Override
    default Object attribute(String key) {
        return delegate().attribute(key);
    }

    @Override
    default Request attribute(String key, Object value) {
        delegate().attribute(key, value);
        return this;
    }

    @Override
    default boolean onDisconnect(Runnable callback) {
        return delegate().onDisconnect(callback);
    }

    @Override
    default java.net.InetSocketAddress remoteAddress() {
        return delegate().remoteAddress();
    }

    @Override
    default java.net.InetSocketAddress localAddress() {
        return delegate().localAddress();
    }

    @Override
    default boolean isSecure() {
        return delegate().isSecure();
    }

    @Override
    default String scheme() {
        return delegate().scheme();
    }
}
