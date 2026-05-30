# Chappe — Development Plan

## Phase 1: Foundations ✅
- [x] Multi-module Maven structure with JPMS
- [x] Public API (chappe-api): Server, Handler, Request, Response, Router
- [x] HTTP/1.1 parsing (RFC 9112)
- [x] Basic TCP server with virtual threads
- [x] HTTP/1.1 keep-alive and pipelining
- [x] Integration tests with java.net.http.HttpClient

## Phase 2: HTTP/2 ✅
- [x] HTTP/2 framing (RFC 9113)
- [x] HPACK (full decode + Huffman encode + dynamic table)
- [x] Stream multiplexing (one virtual thread per stream)
- [x] Flow control (connection + per-stream, WINDOW_UPDATE, AtomicInteger)
- [x] h2c cleartext + ALPN h2 via TLS
- [ ] Server push (PUSH_PROMISE) — deferred

## Phase 3: Validation ✅
- [x] HTTP/1.1 conformance (RFC 9110/9112): 32 tests
- [x] HTTP/2 conformance (RFC 9113): 8 tests
- [x] JMH benchmarks + Jetty/Helidon/JDK comparison
- [x] Robustness tests: malformed requests, slowloris, abandonment, oversized

## Phase 4: Production-ready ✅
- [x] TLS/SSL (SSLContext, SSLEngine, ALPN)
- [x] Buffer pooling (ThreadLocal, zero contention)
- [x] Configurable graceful shutdown
- [x] Timeouts (SO_TIMEOUT)

## RFC Audit ✅
### Critical bugs fixed (8):
- [x] Chunked TE responses, H2 flow control, timeouts, Content-Length validation
- [x] Chunk size overflow, SslHandler thread safety, max concurrent streams, FrameWriter IOException

### RFC conformance (9):
- [x] Date, HEAD/204/304 body suppression, 405+Allow, Host validation
- [x] 100-continue, H2 INITIAL_WINDOW_SIZE, pseudo-headers, forbidden headers

### Performance optimizations (63K → 96K req/s, +53%):
- [x] Write coalescing (1 syscall), zero-alloc headers (putAsciiString)
- [x] Thread-local buffer pool, fast path 200 OK, header value interning
- [x] HPACK Huffman encoding + dynamic table

## Phase 5: Preparing Vidocq extensions ✅
### Extension SPI:
- [x] `Router.mount(prefix, handler)` — registration by path prefix with stripping
- [x] Enriched `Request` — contextPath, pathInfo, attributes, remoteAddress, isSecure, scheme
- [x] `RequestContext` ScopedValue — context propagated without parameter passing
- [x] `Body.ofOutputStream()` — streaming body (Servlet OutputStream compatibility)
- [x] `Body.ofFile()` + `FileBody` — zero-copy via FileChannel.transferTo()

### Static files:
- [x] `StaticFileHandler` builder with fallback chain
- [x] Filesystem (zero-copy) and classpath (jar, META-INF/resources) sources
- [x] In-memory cache with ETag for classpath resources
- [x] Configurable Cache-Control
- [x] `MimeTypes` — detection by extension (26+ types)
- [x] Last-Modified / If-Modified-Since → 304
- [x] If-None-Match / ETag → 304 (cache)
- [x] Path traversal protection
- [x] Directory index (index.html)

## Phase 6: Vidocq integration (coming soon)
- [ ] `vidocq-servlet` — CDI extension mounting Servlet 6.1 on Chappe
- [ ] `vidocq-jaxrs` — CDI extension mounting JAX-RS 4.0 on Chappe (native, not via Servlet)
- [ ] Servlet + JAX-RS coexistence on different prefixes
- [ ] Maven plugin for embedded launch
- [ ] Documentation and examples

## Phase 6.5: WebSocket (RFC 6455) ✅
- [x] Public API: `WebSocket`, `WebSocketHandler`, `CloseCodes`, `WebSocketUpgrade`, `Router.Builder.webSocket(pattern, handler)`
- [x] Server handshake: validation of `Upgrade`/`Connection`/`Sec-WebSocket-Version: 13`/`Sec-WebSocket-Key`, computation of `Sec-WebSocket-Accept` (SHA-1 + base64 + RFC GUID), 101 Switching Protocols, subprotocol support
- [x] RFC 6455 §5 framing: opcodes (TEXT/BINARY/PING/PONG/CLOSE/CONTINUATION), FIN, RSV, client masking (XOR unmask), 7/16/64-bit payload, UTF-8 validation on TEXT, control frames ≤ 125o, rejection of non-zero RSV / unknown opcode / missing mask / orphan continuation / payload > 64 MiB
- [x] Lifecycle: auto-PONG on PING (before `onPing` observation), bidirectional close handshake (echo of received code), `onError` on exception, TCP close in `finally`
- [x] Thread safety: `ReentrantLock` serializes writes (RFC §5.4), sequential callbacks on the connection virtual thread
- [x] Tests: 5 integration tests (`WebSocketEchoTest` via `java.net.http.WebSocket`) + 8 raw conformance tests (`WebSocketRfc6455Test` via socket: canonical RFC §1.3 `Sec-WebSocket-Accept`, missing version → 426, unmasked → close 1002, invalid UTF-8 → close 1007, client Close echo with same code)
- [ ] permessage-deflate (RFC 7692) — deferred, to be wired as a negotiated extension
- [ ] WebSocket over HTTP/2 (RFC 8441) — deferred

## Phase 6.6: HTTP/2 Trailers (RFC 9113 §8.1) ✅
- [x] Public API: `Response.trailers()` + `Builder.trailer(name, value)` / `trailers(headers)`; `Request.trailers()`
- [x] Server sending: additional HEADERS frame after DATA frames with END_STREAM=1, HPACK `encodeTrailers` encoding (no pseudo-headers)
- [x] Server receiving: detection of a second HEADERS frame on an existing stream via `Http2Stream.markTrailers()`, rejection of pseudo-headers in trailers, exposure through `request.trailers()`
- [x] Refactor `HpackDecoder.decode(ByteBuffer, HeaderSink)` to decouple the write target
- [x] `Http2TrailersTest` tests: 2 round-trip scenarios via raw H2 client (send + receive)

## Phase 6.7: gRPC transport (RFC HTTP/2 + core framing) ✅
> gRPC on the wire = HTTP/2 + trailers + length-prefixed 5-byte framing. **No dependency** on grpc-java / Netty / perfmark / protobuf-java. Serialization (protobuf, JSON, …) remains the responsibility of the application or of a dedicated extension (`champollion` for protobuf, in parallel).
- [x] Public `chappe-api` API: `GrpcHandler`, `GrpcCall` (blocking synchronous byte-level SPI), `GrpcStatus` (17 RFC codes), `GrpcDispatch` (marker Response), `Router.Builder.grpc(pattern, handler)`
- [x] Core framing `chappe-http/grpc/`: `GrpcFrameReader` (5-byte prefix, rejects `compressed=1`, guard `maxMessageSize=4 MiB`), `GrpcFrameWriter` (encodes prefix + payload), `GrpcFrameException`
- [x] `GrpcCallImpl`: automatic headers emission (`:status 200`, `content-type: application/grpc`, `grpc-accept-encoding: identity`), trailers with RFC 3986 percent-encoding on `grpc-message`, trailers-only merged into a single HEADERS frame, handler error handling → `INTERNAL`
- [x] HTTP/2 dispatch: `Http2Connection.dispatchStream` detects `instanceof GrpcDispatch` and switches without `sendResponse`, public helpers `frameWriter()`/`hpackEncoder()`/`sendDataChunked()` exposed
- [x] Defensive HTTP/1.1 refusal: `DefaultRouterBuilder.grpc()` returns `505` if version ≠ HTTP/2, `415` if content-type ≠ `application/grpc*`; `HttpConnection` hardens a residual case
- [x] Cancellation: `Http2Stream.cancel()` wired in `handleRstStream`, exposed via `GrpcCall.isCancelled()`
- [x] `Http2GrpcTransportTest` tests: 7 scenarios (unary, server-stream, client-stream, bidi 3/3, handler-error, trailers-only, reject HTTP/1.1)
- [x] `grpc-encoding: gzip` compression: automatic decompression on receive (negotiated via `grpc-encoding` request header), opt-in outbound compression via `GrpcCall.useResponseEncoding("gzip")`, advertises `grpc-accept-encoding: identity, gzip` in all responses, trailers-only UNIMPLEMENTED (12) if request codec is unknown; tests: 4 e2e (decode gzip in, encode gzip out, unknown codec = UNIMPLEMENTED, accept-encoding advertised by default)
- [x] `grpc-timeout` deadline propagation: RFC parser (H/M/S/m/u/n, overflow saturation), watchdog virtual thread, cancel stream + DEADLINE_EXCEEDED (4) trailers if handler overruns, exposed via `GrpcCall.deadline()` (Optional<Duration>); tests: `GrpcTimeoutParserTest` (13) + `deadlineExceededViaGrpcTimeout` + `deadlineRespectedReturnsOk`
- [x] gRPC-Web V1 (HTTP/2 only): `GrpcWebDispatch` marker + `Router.grpcWeb(pattern, handler)`, BINARY (`application/grpc-web`) and TEXT (`application/grpc-web-text` with Base64 chunk-by-chunk) modes, trailers serialized inline as DATA frame 0x80, reuses timeout parser + grpc-encoding negotiation, H2 dispatch in `Http2Connection.dispatchStream`; tests: 6 scenarios (binary unary, binary server-streaming, text unary, handler-error, unknown content-type = 415, HTTP/1.1 = 505). HTTP/1.1 mode deferred (modern browsers speak H2 natively)
- [x] gRPC-Web v1 client (zero-dep via JDK `HttpClient`): `GrpcWebClient` + `GrpcWebResponse` in `chappe-api.client`, BINARY/TEXT modes, unary + server-streaming (collected), parser for 5-byte body frames + 0x80 trailer, exposes `grpc-status`/`grpc-message`/trailers; self-loop client↔server tests (4) + H1 server test (1 new). Native gRPC client (on H2 trailers) deferred (requires custom HTTP/2 client)
- [x] gRPC-Web HTTP/1.1 mode (was deferred): `Router.grpcWeb` accepts H1 via `Body.ofOutputStream` callback + package-private `GrpcWebBufferedCall` (buffered read, chunked-transfer streaming write). In H2 the `GrpcWebDispatch` marker remains for streaming via Http2Connection. Tests: H1 unary echo via JDK HttpClient
- [x] Protocol conformance: `grpcurl` cross-impl — `GrpcurlConformanceTest` (2 scenarios: unary echo + handler error → INTERNAL; clean skip if binary absent)
