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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Body backed by an InputStream — suited for streaming reads.
 */
final class InputStreamBody implements Body {

    private final InputStream stream;
    private final long contentLength;

    InputStreamBody(InputStream stream, long contentLength) {
        this.stream = stream;
        this.contentLength = contentLength;
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public InputStream asInputStream() {
        return stream;
    }

    @Override
    public Flow.Publisher<ByteBuffer> asPublisher() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private volatile boolean cancelled;

                @Override
                public void request(long n) {
                    if (cancelled) return;
                    try {
                        var buf = new byte[8192];
                        long remaining = n;
                        while (remaining-- > 0 && !cancelled) {
                            int read = stream.read(buf);
                            if (read == -1) {
                                subscriber.onComplete();
                                return;
                            }
                            subscriber.onNext(ByteBuffer.wrap(buf, 0, read));
                        }
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        };
    }
}
