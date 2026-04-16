package fr.vidocq.chappe.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Corps basé sur un tableau d'octets en mémoire.
 */
final class ByteArrayBody implements Body {

    private final byte[] bytes;

    ByteArrayBody(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public long contentLength() {
        return bytes.length;
    }

    @Override
    public InputStream asInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public Flow.Publisher<ByteBuffer> asPublisher() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long n) {
                    if (!done && n > 0) {
                        done = true;
                        subscriber.onNext(ByteBuffer.wrap(bytes).asReadOnlyBuffer());
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        };
    }
}
