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
