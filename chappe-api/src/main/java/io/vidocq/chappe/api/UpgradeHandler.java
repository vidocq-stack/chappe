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
     * is called or the peer closes it; an exception closes it.
     * <p>
     * The application owns the connection from now on: reaching end of stream on
     * {@link UpgradedConnection#input()} does not close it, so the application must call
     * {@link UpgradedConnection#close()} when it is done. The server's idle timeout no longer
     * applies.
     *
     * @param connection the raw byte streams of the upgraded connection
     * @throws Exception any failure; the transport then closes the connection
     */
    void onUpgrade(UpgradedConnection connection) throws Exception;
}
