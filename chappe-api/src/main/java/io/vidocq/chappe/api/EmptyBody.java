package io.vidocq.chappe.api;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Empty body — singleton.
 */
final class EmptyBody implements Body {

    static final EmptyBody INSTANCE = new EmptyBody();

    private EmptyBody() {}

    @Override
    public long contentLength() {
        return 0;
    }

    @Override
    public InputStream asInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public Flow.Publisher<ByteBuffer> asPublisher() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        };
    }
}
