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
package io.vidocq.chappe.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.*;
import io.vidocq.chappe.http.ws.WebSocketConnection;
import io.vidocq.chappe.http.ws.WebSocketHandshake;

/**
 * HTTP/1.1 connection handler — keep-alive loop.
 * <p>
 * Executed on a virtual thread by {@code chappe-core}.
 * Orchestrates: parse → body setup → dispatch handler → write response → loop.
 */
public final class HttpConnection {

    private static final int BUFFER_SIZE = 16 * 1024; // 16 KB

    private final ReadableByteChannel readChannel;
    private final WritableByteChannel writeChannel;
    private final Closeable closeable;
    private final Handler handler;
    private final ServerConfig config;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final HttpRequestParser parser;
    private final HttpResponseWriter writer;
    private final HttpRequestImpl request;
    private final RequestContext context;
    private volatile boolean open = true;

    // --- Client-disconnect probe (Request.onDisconnect) ---
    private volatile boolean bodyPending;
    private volatile boolean probeArmed;
    private volatile Runnable disconnectCallback;
    private volatile Thread probeThread;

    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config) {
        this(channel, channel, channel, handler, config, null, null);
    }

    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config, ByteBuffer prefilledBuffer) {
        this(channel, channel, channel, handler, config, prefilledBuffer, null);
    }

    /**
     * Constructor for TLS — accepts separate channels (SslHandler).
     */
    public HttpConnection(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            Closeable closeable,
            Handler handler,
            ServerConfig config,
            ByteBuffer prefilledBuffer,
            ByteBuffer writeBuffer) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.closeable = closeable;
        this.handler = handler;
        this.config = config;
        if (prefilledBuffer != null) {
            this.readBuffer = prefilledBuffer;
        } else {
            this.readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            readBuffer.flip();
        }
        this.writeBuffer = writeBuffer != null ? writeBuffer : ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.parser = new HttpRequestParser();
        this.writer = new HttpResponseWriter();
        this.request = new HttpRequestImpl();
        this.context = new RequestContext(request);

        // Populate connection-level metadata once
        if (closeable instanceof SocketChannel sc) {
            request.initConnectionInfo(sc, false);
        }
        request.disconnectArmer(this::armDisconnectProbe);
    }

    /**
     * Entry point — called by chappe-core on a virtual thread.
     * Loops until the connection is closed or an error occurs.
     */
    public void run() {
        try {
            while (open) {
                parser.reset();
                request.reset();

                // 1. Parse request (request-line + headers)
                ParseResult result;
                try {
                    result = parser.parse(readBuffer, readChannel, request, config);
                } catch (ParseException e) {
                    sendError(e.statusCode(), e.getMessage());
                    break;
                }

                if (result == ParseResult.CONNECTION_CLOSED) {
                    break;
                }

                // 2. Validation Host header (RFC 9112 §3.2)
                if (request.version() == HttpVersion.HTTP_1_1) {
                    var hostValues = request.headers().all("Host");
                    if (hostValues.isEmpty()) {
                        sendError(StatusCode.BAD_REQUEST, "Missing Host header");
                        break;
                    }
                    if (hostValues.size() > 1) {
                        sendError(StatusCode.BAD_REQUEST, "Multiple Host headers");
                        break;
                    }
                }

                // 3. Expect: 100-continue (RFC 9110 §10.1.1)
                if ("100-continue".equalsIgnoreCase(request.headers().firstOrNull("Expect"))) {
                    // Send 100 Continue before reading the body
                    var continueBytes = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                    var buf = ByteBuffer.wrap(continueBytes);
                    while (buf.hasRemaining()) {
                        writeChannel.write(buf);
                    }
                }

                // 4. Setup body
                InputStream bodyStream;
                try {
                    bodyStream = setupBody();
                } catch (BadBodyException e) {
                    sendError(StatusCode.BAD_REQUEST, e.getMessage());
                    break;
                }
                if (bodyStream != null) {
                    request.body = Body.of(bodyStream, contentLength());
                }
                bodyPending = bodyStream != null;

                // 3. Dispatch to handler with ScopedValue binding
                boolean keepAlive = isKeepAlive();
                Response response;
                try {
                    response =
                            ScopedValue.where(RequestContext.CURRENT, context).call(() -> handler.handle(request));
                } catch (Exception e) {
                    response = Response.builder()
                            .status(StatusCode.INTERNAL_SERVER_ERROR)
                            .body("Internal Server Error")
                            .build();
                } finally {
                    // The probe shares the parse buffer: it must be fully stopped
                    // before the loop resumes channel reads (next request parse,
                    // WebSocket takeover) or response writing.
                    disarmDisconnectProbe();
                }

                // 4a-bis. gRPC requires HTTP/2 — defensive rejection on HTTP/1.1 layer.
                // In practice router already returns 505 (see DefaultRouterBuilder.grpc()),
                // this check only acts as guardrail if an extension builds GrpcDispatch elsewhere.
                if (response instanceof io.vidocq.chappe.api.GrpcDispatch) {
                    response = Response.of(StatusCode.HTTP_VERSION_NOT_SUPPORTED);
                }

                // 4a. WebSocket upgrade: switch to frame mode and end HTTP loop.
                if (response instanceof WebSocketUpgrade upgrade) {
                    if (bodyStream != null) HttpBodyReader.drain(bodyStream);
                    var key = request.headers().firstOrNull("Sec-WebSocket-Key");
                    WebSocketHandshake.writeResponse(writeChannel, key, upgrade.subprotocol());
                    // Prefer the matched handshake request (carries route pathParams) over the raw one.
                    var handshakeReq = upgrade.handshakeRequest() != null ? upgrade.handshakeRequest() : request;
                    var wsConn = new WebSocketConnection(
                            readChannel,
                            writeChannel,
                            closeable,
                            readBuffer,
                            upgrade.handler(),
                            handshakeReq,
                            upgrade.subprotocol());
                    wsConn.run();
                    return; // closeable already closed by WebSocketConnection
                }

                // 4. Write response
                writer.write(response, writeBuffer, writeChannel, keepAlive, request.method());

                // 5. Drain unread body (for keep-alive)
                if (bodyStream != null) {
                    HttpBodyReader.drain(bodyStream);
                }

                // 6. Check keep-alive
                if (!keepAlive) {
                    break;
                }
            }
        } catch (IOException _) {
            // Lost connection — silent
        } finally {
            close();
        }
    }

    /** Closes the connection. */
    public void close() {
        open = false;
        disarmDisconnectProbe();
        try {
            closeable.close();
        } catch (IOException _) {
            // Ignore
        }
    }

    // --- Client-disconnect probe (Request.onDisconnect) ---

    /**
     * Arms a best-effort client-disconnect probe for the request currently
     * being handled. Plaintext HTTP/1.1 only ({@code readChannel} must be the
     * raw {@link SocketChannel} so the probe can switch to non-blocking reads),
     * and only when the request body is absent — the probe reads into the
     * shared parse buffer, which would race a concurrent body consumer.
     */
    private boolean armDisconnectProbe(Runnable callback) {
        if (!(readChannel instanceof SocketChannel)) return false; // TLS / wrapped channel
        if (bodyPending || !open || probeArmed) return false;
        disconnectCallback = callback;
        probeArmed = true;
        probeThread = Thread.ofVirtual().name("chappe-disconnect-probe").start(this::probeLoop);
        return true;
    }

    /**
     * Polls the socket every 100 ms with a non-blocking, non-destructive read
     * into the parse buffer: EOF (FIN) or an IOException (RST) means the
     * client is gone → fire the callback. Pipelined bytes that arrive early
     * are simply kept in the buffer for the next parse iteration.
     */
    private void probeLoop() {
        var sc = (SocketChannel) readChannel;
        try {
            while (probeArmed && open) {
                int n;
                sc.configureBlocking(false);
                try {
                    readBuffer.compact();
                    n = sc.read(readBuffer);
                    readBuffer.flip();
                } finally {
                    sc.configureBlocking(true);
                }
                if (n == -1) {
                    fireDisconnect();
                    return;
                }
                Thread.sleep(100);
            }
        } catch (IOException _) {
            fireDisconnect(); // hard close / connection reset
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void fireDisconnect() {
        if (!probeArmed) return;
        probeArmed = false;
        Runnable cb = disconnectCallback;
        disconnectCallback = null;
        if (cb != null) {
            try {
                cb.run();
            } catch (RuntimeException _) {
                // listener failure must not take the connection down
            }
        }
    }

    /** Stops the probe and waits for it to release the parse buffer/channel. */
    private void disarmDisconnectProbe() {
        probeArmed = false;
        disconnectCallback = null;
        Thread t = probeThread;
        probeThread = null;
        if (t != null) {
            try {
                t.join(500); // probe wakes within its 100 ms poll interval
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Internal helpers ---

    /**
     * Sets up the request body.
     * @return the body InputStream, or null if there is no body
     * @throws BadBodyException if Content-Length is invalid or exceeds the limit
     */
    private InputStream setupBody() throws BadBodyException {
        var headers = request.headers();
        var transferEncoding = headers.firstOrNull("Transfer-Encoding");
        var contentLengthStr = headers.firstOrNull("Content-Length");

        // Transfer-Encoding: chunked wins over Content-Length (RFC 9112 §6.3)
        if ("chunked".equalsIgnoreCase(transferEncoding)) {
            return HttpBodyReader.chunked(readBuffer, readChannel);
        }

        if (contentLengthStr != null) {
            long cl;
            try {
                cl = Long.parseLong(contentLengthStr);
            } catch (NumberFormatException _) {
                throw new BadBodyException("Invalid Content-Length: " + contentLengthStr);
            }
            if (cl < 0) {
                throw new BadBodyException("Negative Content-Length: " + cl);
            }
            if (cl == 0) return null;
            if (cl > config.maxRequestSize()) {
                throw new BadBodyException("Content-Length " + cl + " exceeds max " + config.maxRequestSize());
            }
            return HttpBodyReader.fixedLength(readBuffer, readChannel, cl);
        }

        return null;
    }

    private static final class BadBodyException extends Exception {
        BadBodyException(String message) {
            super(message);
        }
    }

    private long contentLength() {
        var headers = request.headers();
        var transferEncoding = headers.firstOrNull("Transfer-Encoding");
        if ("chunked".equalsIgnoreCase(transferEncoding)) return -1;

        return headers.first("Content-Length")
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException _) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    private boolean isKeepAlive() {
        var connection = request.headers().firstOrNull("Connection");
        if (request.version() == HttpVersion.HTTP_1_1) {
            // HTTP/1.1: keep-alive by default, unless Connection: close
            return !"close".equalsIgnoreCase(connection);
        }
        // HTTP/1.0: close by default, unless Connection: keep-alive
        return "keep-alive".equalsIgnoreCase(connection);
    }

    private void sendError(StatusCode status, String message) {
        try {
            writer.writeError(status, message, writeBuffer, writeChannel);
        } catch (IOException _) {
            // Connection already lost
        }
    }
}
