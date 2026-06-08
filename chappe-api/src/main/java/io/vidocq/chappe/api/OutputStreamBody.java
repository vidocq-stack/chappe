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

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

final class OutputStreamBody implements Body {
    private final Consumer<OutputStream> writer;

    OutputStreamBody(Consumer<OutputStream> writer) {
        this.writer = writer;
    }

    @Override
    public long contentLength() {
        return -1;
    }

    @Override
    public InputStream asInputStream() {
        try {
            var pis = new PipedInputStream(8192);
            var pos = new PipedOutputStream(pis);
            // pos is handed to the virtual thread, which closes it via try-with-resources
            Thread.startVirtualThread(() -> {
                try (pos) {
                    writer.accept(pos);
                } catch (IOException _) {
                    // PipedInputStream consumer closed early: just stop writer
                }
            });
            return pis;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Flow.Publisher<ByteBuffer> asPublisher() {
        return Body.of(asInputStream()).asPublisher();
    }
}
