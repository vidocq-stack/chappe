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
import java.nio.file.*;
import java.util.concurrent.Flow;

public final class FileBody implements Body {
    private final Path path;
    private final long offset;
    private final long length;

    FileBody(Path path, long offset, long length) {
        this.path = path;
        this.offset = offset;
        try {
            this.length = length >= 0 ? length : Files.size(path) - offset;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long contentLength() {
        return length;
    }

    public Path path() {
        return path;
    }

    public long offset() {
        return offset;
    }

    @Override
    public InputStream asInputStream() {
        try {
            var fis = new FileInputStream(path.toFile());
            if (offset > 0) fis.skipNBytes(offset); // skipNBytes garantit le skip complet, contrairement à skip()
            return fis;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Flow.Publisher<ByteBuffer> asPublisher() {
        return Body.of(asInputStream(), length).asPublisher();
    }
}
