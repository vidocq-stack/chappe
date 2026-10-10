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
package io.vidocq.chappe.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * A request body stream reading the connection's own buffer and channel. Once a read failed (a
 * truncated body, a reset, an interrupt that closed the channel) the position in the byte stream
 * is unknown: {@link #failed()} tells the connection to close instead of draining the body and
 * parsing a next request (CHAPPE-013).
 */
abstract class RequestBodyInputStream extends InputStream {

    private volatile boolean failed;

    /** Whether a read of this body threw. */
    final boolean failed() {
        return failed;
    }

    @Override
    public final int read() throws IOException {
        try {
            return readByte();
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public final int read(byte[] b, int off, int len) throws IOException {
        try {
            return readBytes(b, off, len);
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** {@link InputStream#read()}. */
    abstract int readByte() throws IOException;

    /** {@link InputStream#read(byte[], int, int)}. */
    abstract int readBytes(byte[] b, int off, int len) throws IOException;
}
