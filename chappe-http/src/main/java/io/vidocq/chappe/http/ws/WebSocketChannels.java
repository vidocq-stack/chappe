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
package io.vidocq.chappe.http.ws;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * The I/O a {@link WebSocketConnection} runs on, once the HTTP connection is upgraded.
 *
 * @param readChannel where the peer's frames are read from
 * @param writeChannel where this side's frames are written to
 * @param closeable what closes the underlying connection
 * @param readBuffer the buffer the frames are read through, already holding any bytes the HTTP parser read past
 *     the handshake
 */
public record WebSocketChannels(
        ReadableByteChannel readChannel,
        WritableByteChannel writeChannel,
        Closeable closeable,
        ByteBuffer readBuffer) {}
