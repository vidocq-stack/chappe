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
package io.vidocq.chappe.bench.oop;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

/**
 * Variant of {@link ChappeMain} that serves a prebuilt {@link Response}
 * shared across all requests (zero-allocation on the response side).
 *
 * <p>Lets you measure the impact of the per-request allocations
 * {@code Builder → Headers$Entry → DefaultHeaders → DefaultResponse}
 * identified in the JFR profile.
 */
public final class ChappeMainCached {
    private ChappeMainCached() {}

    /** Prebuilt once — Response is documented as immutable. */
    private static final Response CACHED_OK = Response.ok("ok");

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = Server.builder()
                .port(port)
                .host("0.0.0.0")
                .handler(_ -> CACHED_OK)
                .build();
        server.start();
        System.out.println("chappe-jvm-cached listening on :" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "chappe-shutdown"));
        Thread.currentThread().join();
    }
}
