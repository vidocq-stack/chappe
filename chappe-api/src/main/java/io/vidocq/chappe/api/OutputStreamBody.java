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
