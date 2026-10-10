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
 * Takes over a connection after the transport has written the upgrade response.
 *
 * @see ConnectionUpgrade
 */
@FunctionalInterface
public interface UpgradeHandler {

    /**
     * Called on the connection's thread once the status line and headers are on the wire. The
     * connection stays open after this method returns, until {@link UpgradedConnection#close()}
     * is called or the server stops; an exception thrown by this method closes it (and is logged
     * at {@code WARNING}).
     * <p>
     * The application owns the connection from now on. The peer closing its side does not close
     * the connection: end of stream ({@code -1}) on {@link UpgradedConnection#input()} may be a
     * legitimate half-close after which the application still writes. The application
     * <b>must</b> therefore call {@link UpgradedConnection#close()} when it is done, in
     * particular after reading {@code -1} and on any I/O error; otherwise the connection (and
     * its thread) stays open until the server stops. The server's idle and write timeouts no
     * longer apply.
     *
     * @param connection the raw byte streams of the upgraded connection
     * @throws Exception any failure; the transport then closes the connection
     */
    void onUpgrade(UpgradedConnection connection) throws Exception;
}
