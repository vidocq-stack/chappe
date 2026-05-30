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
