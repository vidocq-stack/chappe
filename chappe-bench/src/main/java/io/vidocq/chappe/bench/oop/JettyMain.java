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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

public final class JettyMain {
    private JettyMain() {}

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract.NonBlocking() {
            @Override
            public boolean handle(
                    org.eclipse.jetty.server.Request req, org.eclipse.jetty.server.Response res, Callback cb) {
                res.setStatus(200);
                res.getHeaders().put("Content-Type", "text/plain");
                Content.Sink.write(res, true, "ok", cb);
                return true;
            }
        });
        server.start();
        System.out.println("jetty listening on :" + connector.getLocalPort());
        server.join();
    }
}
