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
package io.vidocq.chappe.http.h2;

/**
 * The ids of the last streams this endpoint reset or refused. The peer may have sent frames on them
 * before it saw the RST_STREAM; RFC 9113 §5.1 requires ignoring those frames, not failing the
 * connection. Bounded: a frame on a stream forgotten since is handled as on any closed stream.
 * Written by stream threads and read by the connection thread; {@code synchronized} does not pin a
 * virtual thread since JDK 24 (JEP 491).
 */
final class ResetStreams {

    private final int[] ids;
    private int next;
    private int size;

    ResetStreams(int capacity) {
        this.ids = new int[capacity];
    }

    synchronized void add(int streamId) {
        ids[next] = streamId;
        next = (next + 1) % ids.length;
        if (size < ids.length) size++;
    }

    synchronized boolean contains(int streamId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == streamId) return true;
        }
        return false;
    }
}
