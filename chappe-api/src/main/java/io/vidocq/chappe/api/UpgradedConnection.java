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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The raw byte streams of an upgraded HTTP/1.1 connection.
 * <p>
 * The streams may be used from any thread: one thread may read {@link #input()} while another
 * writes {@link #output()}; concurrent writes are serialised. {@link #close()} may be called from
 * any thread and wakes a blocked read or write. Closing {@link #input()} or {@link #output()}
 * themselves does not close the connection; only {@link #close()} does.
 * <p>
 * The connection stays open until {@link #close()} is called or the server stops — the peer
 * closing its side does not close it (a half-close is legitimate). The application must call
 * {@link #close()} when it is done, in particular after reading {@code -1} and on any I/O error.
 * Once closed, the streams throw {@link IOException}.
 *
 * @see ConnectionUpgrade
 */
public interface UpgradedConnection extends Closeable {

    /**
     * Bytes sent by the peer after the request head, including any already buffered by the
     * transport. {@link InputStream#available()} reports the bytes the transport had buffered
     * and that are not read yet (readable without blocking). Reaching end of stream
     * ({@code -1}) does not close the connection: the application must call {@link #close()}.
     */
    InputStream input();

    /** Unbuffered-by-contract output: {@code flush()} puts the bytes on the wire. */
    OutputStream output();

    /** The request that asked for the upgrade. */
    Request request();

    /** Closes the connection; idempotent. */
    @Override
    void close() throws IOException;
}
