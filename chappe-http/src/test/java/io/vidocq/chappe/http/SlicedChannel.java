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

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

/** A test channel handing its ISO-8859-1 bytes over at most {@code sliceSize} at a time, then EOF. */
final class SlicedChannel implements ReadableByteChannel {

    private final ByteBuffer source;
    private final int sliceSize;

    SlicedChannel(String bytes, int sliceSize) {
        this.source = ByteBuffer.wrap(bytes.getBytes(StandardCharsets.ISO_8859_1));
        this.sliceSize = sliceSize;
    }

    @Override
    public int read(ByteBuffer dst) {
        if (!source.hasRemaining()) return -1;
        int n = Math.min(Math.min(sliceSize, source.remaining()), dst.remaining());
        dst.put(source.slice(source.position(), n));
        source.position(source.position() + n);
        return n;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
