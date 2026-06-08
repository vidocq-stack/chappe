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
package io.vidocq.chappe.examples;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StaticFileHandler;

/**
 * Demonstration of {@link StaticFileHandler} served from the classpath with the index
 * generated at build time by {@code chappe-static-index-maven-plugin}.
 *
 * <p>The plugin scans {@code src/main/resources/static/**} and writes
 * {@code META-INF/chappe-static-index.properties}. On the first lookup, {@code StaticFileHandler}
 * loads that index and resolves each request in O(1) — without {@code URLConnection.openConnection()}.
 *
 * <p>If the plugin is not enabled, the handler falls back to the classic
 * {@code loader.getResource()} path — zero functional regression.
 */
public final class StaticIndexedExample {

    public static void main(String[] args) throws Exception {
        var router = Router.builder()
                .mount(
                        "/assets",
                        StaticFileHandler.builder()
                                .addClasspath("static")
                                .cacheInMemory(true)
                                .cacheControl("public, max-age=3600")
                                .build())
                .build();

        var server = Server.builder().port(8080).handler(router).build();

        server.start();
        System.out.println("http://localhost:8080/assets/index.html");
        Thread.currentThread().join();
    }

    private StaticIndexedExample() {}
}
