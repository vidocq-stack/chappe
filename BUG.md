# Chappe — Bug Registry

Format: one bug per dated section, with a short id, symptom, minimal repro,
cause hypothesis, and status.

---

## CHAPPE-031 — a very large or negative idleTimeout killed the accept loop

- **Date**: 2026-10-12
- **Status**: FIXED (commit `fix(core): saturate the idle SO_TIMEOUT so no idleTimeout kills the accept
  loop (CHAPPE-031)`, branch `pr/ybl/chappe-timeouts`)
- **Module**: `chappe-core` (`ChappeServer.acceptLoop`)
- **Symptom**: with `idleTimeout` above `Integer.MAX_VALUE` ms (about 24.8 days), with
  `ChronoUnit.FOREVER.getDuration()`, or with a negative value, the server started but never
  answered: connections were accepted by the kernel and then left unhandled, silently.
- **Minimal reproduction**: `chappe-tests/.../ServerLifecycleTest`
  (`aVeryLargeIdleTimeoutStillAccepts` with 30 days and `FOREVER`,
  `aNegativeIdleTimeoutDoesNotKillTheAcceptLoop` with -1 s): before the fix the client read hit its
  own 5 s `SO_TIMEOUT`.
- **Cause**: `acceptLoop` computed `(int) config.idleTimeout().toMillis()` once. Past 24.8 days the
  cast overflowed to a negative value, `FOREVER` made `toMillis()` throw `ArithmeticException`, and
  a negative `idleTimeout` stayed negative. `setSoTimeout` then threw `IllegalArgumentException`,
  and `acceptLoop` catches only `IOException`: the accept thread died. `ServerConfig` validates
  neither bound. Found by the final review of the connection-timeouts branch (pre-existing).
- **Fix**: `ChappeServer.soTimeoutMillis` saturates the value at `Integer.MAX_VALUE` ms and maps
  `null`, zero and negative to 0 (no bound). The `SO_TIMEOUT` is informative only on blocking
  `SocketChannel` reads; the real bounds (`BoundedReads`, `TimedReadChannel`, the handshake deadline)
  already saturate.
- **Follow-up (OPEN)**: a zero or negative `idleTimeout` still does not mean "no bound" for the idle
  waits: `BoundedReads.readWithTimeout` arms a watchdog that fires at once, so a connection waiting
  for a request may be closed immediately. Either treat it as no bound, as `readTimeout` does, or
  reject it in `ServerConfig`; a separate decision.

---

## CHAPPE-030 — an ephemeral port could be lost between the probe and the bind

- **Date**: 2026-10-12
- **Status**: FIXED for non-wildcard hosts (commit `fix(core): bind port 0 directly on a specific
  address (CHAPPE-030)`, branch `pr/ybl/chappe-timeouts`). For a wildcard host the probe-then-bind
  window stays open and is repaired by the retry.
- **Module**: `chappe-core` (`ChappeServer.bindWithRetry`, `resolveBindPort`)
- **Symptom**: with `port(0)`, `start()` could fail or sleep 100-400 ms and retry although the
  machine had plenty of free ports, when many servers started at once in the same JVM or when other
  processes were binding ephemeral ports.
- **Minimal reproduction**: none deterministic; the race cannot be forced.
  `ServerLifecycleTest.manyPortZeroServersStartConcurrentlyWithoutError` (8 threads x 25 servers on
  `127.0.0.1:0`) is a stress test that passed before the fix too, so it was not RED. The property
  test `portZeroReportsTheBoundPortAndKeepsTheConfig` pins the contract.
- **Cause**: `resolveBindPort()` bound a throwaway socket to `127.0.0.1:0`, read its port and closed
  it, then the real listener bound that port. Anyone could take the port between the close and the
  bind; only the 5-attempt retry repaired it.
- **Fix**: when `config.port() == 0` and the host is not a wildcard address
  (`InetAddress.isAnyLocalAddress()`, resolved once outside the retry loop), the listener binds
  `host:0` directly, so the kernel chooses and binds atomically. `port()` and `localAddress()` already
  read the bound port back; `ServerConfig.port` stays 0.
- **Wildcard hosts are deliberately unchanged**: binding port 0 directly on `0.0.0.0` would
  re-introduce CHAPPE-004 #3 (a port free on the wildcard but held by a foreign process on
  `127.0.0.1`/`::1`). The loopback probe stays there, with its window repaired by the retry. Closing
  that window would need an OS-dependent approach that has not been spiked on macOS and Linux.
- **See also**: CHAPPE-004.

---

## CHAPPE-029 — a peer that stopped reading blocked HTTP/2 writes forever

- **Date**: 2026-10-12
- **Status**: FIXED (commit `fix(http): apply writeTimeout to HTTP/2 writes (CHAPPE-029)`,
  branch `pr/ybl/chappe-timeouts`)
- **Module**: `chappe-http` (`Http2FrameWriter`, `Http2Connection`)
- **Symptom**: `writeTimeout` bounded HTTP/1.1 writes only. On HTTP/2, a peer that opened its
  flow-control windows, sent a request and then stopped reading its socket made the server's
  `channel.write` block forever. That write runs under the frame writer's single lock, so every
  other writer of the connection queued behind it: the connection thread (SETTINGS and PING acks),
  every other stream's response, the WINDOW_UPDATEs a request body read sends (CHAPPE-024 (a)),
  RST_STREAM and GOAWAY. The connection and its threads stayed open for as long as the peer liked.
- **Minimal reproduction**: `chappe-tests/.../Http2WriteTimeoutTest`
  (`aPeerThatStopsReadingIsCut`, `aPeerThatStopsReadingIsCutOverTls`): a client with a 2 KiB
  receive buffer opens both windows to 2^31 − 1, asks for a 32 MiB body and reads nothing for 3 s.
  Before the fix it then received the whole 32 MiB and no EOF within 10 s.
- **Cause**: `Http2FrameWriter.flushBuffer()` looped on `channel.write(writeBuffer)` with no bound,
  and `Http2Connection` never passed `config.writeTimeout()` to the writer.
- **Fix**: `Http2FrameWriter` takes a `writeTimeout` and a `Closeable`, and each write of a flush
  goes through `BoundedWrites.writeWithTimeout`, as HTTP/1.1 does. A flush is at most the 16 KiB
  frame buffer, and a partial write is progress, so only a socket that accepts nothing for
  `writeTimeout` is cut. `Http2Connection` passes `config.writeTimeout()` and its write channel,
  which is the transport: the `SocketChannel` for h2c, the `SslHandler` for TLS. At the deadline
  the transport is closed: the stalled write fails, every other writer fails fast, the frame loop's
  read fails, and the existing `close()` cancels all the streams. On HTTP/2 a stalled flush
  therefore ends the whole multiplexed connection, every stream with it. The constructors without
  a `writeTimeout` keep no bound.
- **Found while testing**: on macOS loopback, a reader that drains 64 KiB every 100 ms still left
  single writes of a few hundred bytes blocked for about 420 ms (measured), the kernel waiting to
  reopen a closed receive window. A `writeTimeout` near half a second can therefore cut a healthy
  but pausing reader; the default (30 s) is far from it. `aSlowButProgressingReaderIsNotCut` runs
  with a 2 s bound for that reason.
- **Zero or negative bound (fix round 1)**: `BoundedWrites.writeWithTimeout` armed its watchdog
  with whatever it was given, so a zero or negative `writeTimeout` raced a close against the
  first write (a `null` one threw an NPE in the watchdog thread and left the write unbounded). On HTTP/1.1 that cut a response
  after its first bytes (pre-existing); with this fix it would have closed whole HTTP/2
  connections. `writeWithTimeout` now treats `null`, zero and negative as no bound and writes
  plain, on HTTP/1.1 and HTTP/2, the convention `readTimeout` and the TLS handshake bound already
  follow. Reproduction: `WriteTimeoutTest#zeroWriteTimeoutIsNoBound` and
  `Http2WriteTimeoutTest#aZeroWriteTimeoutIsNoBound` (the body was cut after 16 to 24 KiB).
- **The TLS close stays bounded (final review I1)**: that change also made the TLS close_notify
  (`SslHandler.close`, `closeNotifyAndDrain`) and the alert of a rejected handshake
  (`sendPendingAlert`) unbounded under a zero or negative `writeTimeout`: a peer that stopped
  reading, with the send buffer full, pinned the closing thread until `stop()`. The `SslHandler`
  constructor now turns a `null`, zero or negative value into `CLOSE_WRITE_FALLBACK`, the 2 s
  lingering budget (`LingeringClose.TIME`), and every close write uses that one field
  (`HttpConnection` no longer passes `config.writeTimeout()` to `closeNotifyAndDrain`). A `null`
  value used to fall back to the 30 s default instead. Reproduction:
  `TlsCloseBoundTest#aPeerThatStopsReadingCannotPinTheClose` (zero, negative and `null`; a raw
  32 MiB write holds the stalled socket): before, `close()` was still blocked after 5 s; after, it
  ends at the 2 s budget.
- **Follow-ups (OPEN)**:
  - `Http2Connection.waitForSendWindow` waits on its condition with no bound: a peer that never
    sends WINDOW_UPDATE parks the response thread forever. That is a flow-control stall, not a
    socket-write stall, so `writeTimeout` does not cover it. Fix: bound the wait and reset the
    stream, as a separate decision.
  - Each bounded write starts one watchdog virtual thread (CHAPPE-022 (a)). Every frame write ends
    in a flush, and a full 16 KiB DATA frame plus its 9-byte header spans two flushes of the 16 KiB
    buffer, so a 32 MiB body costs about 4,096 watchdogs (and as many write calls); a buffer of
    `maxFrameSize + 9` bytes would halve both. A `TimedWriteChannel`,
    a per-connection write deadline followed by one watchdog like `TimedReadChannel`, would remove
    that cost for HTTP/1.1 and HTTP/2 alike.

---

## CHAPPE-028 — a TLS client that never completed its handshake held its virtual thread forever

- **Date**: 2026-10-12
- **Status**: FIXED (commit `fix(tls): bound the TLS handshake by the idle timeout (CHAPPE-028)`,
  branch `pr/ybl/chappe-timeouts`)
- **Module**: `chappe-http` (`SslHandler`), `chappe-core` (`ChappeServer`)
- **Symptom**: on a TLS listener, a client that connected and sent nothing, sent only part of its
  ClientHello, or sent it one byte at a time, was never disconnected: its connection and virtual
  thread stayed open for as long as the client liked (a slowloris at the TLS layer). `readTimeout`
  and `idleTimeout` did not apply to the handshake.
- **Minimal reproduction**: `chappe-tests/.../TlsHandshakeTimeoutTest`
  (`aClientThatNeverSendsAClientHelloIsClosed`, `aPartialClientHelloIsClosed`,
  `aDripFeedingClientIsStillCutAtTheDeadline`): before the fix the client read hit its own 5 s
  `SO_TIMEOUT` instead of the server's close.
- **Cause**: `SslHandler.runHandshake()` blocks in `channel.read(netInBuffer)` on `NEED_UNWRAP`
  (and in `channel.write` on `NEED_WRAP`) with no bound. A partial ClientHello leaves the engine in
  `BUFFER_UNDERFLOW` and the loop reads again. Same class as CHAPPE-005 (the idle timeout that was
  a silent no-op).
- **Fix**: `SslHandler.doHandshake(Duration)` arms one total deadline for the whole handshake, and
  `ChappeServer.handleTlsConnection` passes `idleTimeout`. A watchdog virtual thread (same pattern
  as `TimedReadChannel`) closes the socket at the deadline, which wakes the blocked read or write;
  the handshake then fails with `SocketTimeoutException("TLS handshake timed out after N ms")`. The
  end of the handshake and the watchdog race on one compare-and-set, so exactly one wins. A total
  deadline, not a per-read one, so a drip-feeding client is cut too. The deadline is disarmed
  before the rejected-handshake path of CHAPPE-027 runs, so the alert and the lingering close keep
  their own budgets. `doHandshake()` without argument keeps no bound. That ordering is guarded by
  `TlsHandshakeTimeoutTest#aRejectedHandshakeKeepsItsAlertAndItsLingerPastTheDeadline` (a `NEED`
  listener with a 300 ms deadline: the client gets the alert record, and the server's full close
  comes after the 2 s linger); moving `disarm()` after the catch blocks makes it fail (full close at
  about 375 ms).
- **Follow-ups (OPEN)**:
  - the HTTP/1.1-over-TLS pre-read right after the handshake (`ChappeServer`, around
    `sslHandler.read(readBuffer)`) has no bound: a client that completes the handshake and then
    sends nothing holds its thread;
  - the HTTP/2 connection preface read over TLS (`Http2Connection.validateClientPreface`) has no
    bound either;
  - a dedicated `handshakeTimeout` setting (shorter default than `idleTimeout`, separate from the
    keep-alive idle bound) is not provided.

---

## CHAPPE-027 — a TLS handshake the server rejected ended with a bare close, without its alert

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(tls): refuse TLS 1.2 renegotiation and send the handshake alert (CHAPPE-027)`,
  branch `pr/ybl/chappe-tls-client-auth`)
- **Module**: `chappe-http` (`SslHandler`)
- **Symptom**: when JSSE rejected a handshake (a client without a trusted certificate under
  `ClientAuth.NEED`, no common protocol or cipher suite), the client saw the connection close
  ("Broken pipe", "Connection reset", "Remote host terminated the handshake", or EOF on its first
  read under TLS 1.3) instead of the alert that says why (`bad_certificate`, `certificate_required`,
  `handshake_failure`).
- **Minimal reproduction**: `chappe-tests/.../TlsHandshakeHardeningTest#tls12NeedRejectionSendsTheAlert`
  and `#tls13NeedRejectionSendsTheAlert`.
- **Cause**: `SslHandler.doHandshake()` rethrew the engine's `SSLException` without calling
  `wrap()`, which the `SSLEngine` contract requires to emit the alert the engine queued.
- **Fix**: on an `SSLException`, `doHandshake()` wraps and writes the pending alert (each write
  bounded by the write timeout), then half-closes and drains the socket within the lingering-close
  budget (`LingeringClose`, 2 s / 1 MiB), so a TCP reset caused by unread client bytes cannot destroy
  the alert, and rethrows; the server closes the socket as before. In the same commit, `SslHandler`
  refuses TLS 1.2 client-initiated renegotiation, so the `TlsInfo` read at the handshake cannot go
  stale.
- **Follow-up (FIXED in CHAPPE-028)**: the TLS handshake itself had no timeout.
  `SslHandler.doHandshake()` read without a bound, so a client that connected and never completed
  the handshake held its virtual thread (same class as CHAPPE-005). The handshake is now bounded
  by `idleTimeout` as a total deadline.

---

## CHAPPE-026 — over TLS and HTTP/2, a request reported cleartext and no client address

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): report TLS and HTTP/2 connection info to handlers (CHAPPE-026)`,
  branch `pr/ybl/chappe-tls-client-auth`)
- **Module**: `chappe-http` (`HttpConnection`, `h2/Http2Connection`), `chappe-core` (`ChappeServer`)
- **Symptom**: over TLS (HTTP/1.1 and HTTP/2 negotiated by ALPN), `Request.isSecure()` was `false`,
  `scheme()` was `"http"` and `remoteAddress()`/`localAddress()` were `null`. Over h2c the
  addresses were `null` too. Foy refused every TLS request on a CONFIDENTIAL constraint (it reads
  `isSecure()`), and Cassini's `SecurityContext.isSecure()` and `UriInfo` scheme were wrong.
- **Minimal reproduction**: `chappe-tests/.../ConnectionInfoTest` (h1 and h2 over TLS, keep-alive,
  `Router.mount`, h2c prior knowledge). Before the fix every case but cleartext HTTP/1.1 failed.
- **Cause**: `HttpConnection` filled the connection fields only when its `Closeable` was a
  `SocketChannel`; over TLS it is the `SslHandler`. `Http2Stream` creates one request per stream and
  nothing in `h2/` set connection fields.
- **Fix**: `ChappeServer` builds a `ConnectionInfo` (addresses and the `TlsInfo` that `SslHandler`
  reads at the end of the handshake) once per connection and passes it to `HttpConnection` and
  `Http2Connection`, which copies it into every stream's request. `Request.tls()` exposes the
  session.

---

## CHAPPE-025 — HTTP/2 request bodies are not capped by maxRequestSize

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): cap HTTP/2 request bodies at maxRequestSize (CHAPPE-025)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`h2/Http2Connection`, `h2/Http2Stream`, `h2/Http2BodyInputStream`),
  `chappe-api` (`RequestBodyTooLargeException` Javadoc)
- **Symptom**: nothing in `h2/` read `maxRequestSize`: an HTTP/2 request body of any size was handed
  to the handler.
- **Minimal reproduction**: `chappe-tests/.../Http2RequestBodyLimitTest#aBodyOverTheLimitIsAnswered413WhenTheResponseHasNotStarted`
  (`maxRequestSize(1000)`, 1,200 bytes: the handler kept waiting for more).
- **Cause**: the limit was only wired in the HTTP/1.1 `setupBody()`.
- **Fix**: each stream counts the payload of its DATA frames against `maxRequestSize` (padding
  excluded: it is not body, and flow control already bounds it). A per-stream phase, changed by
  compare-and-set, decides what passing the limit does:
  - before the response starts, the body stays buffered (bounded by the stream window) until the
    reader, the response or the route decides: the handler's read fails with
    `RequestBodyTooLargeException` (the frame that passed the limit is never handed out), the
    buffered DATA is dropped and no longer credited, and the dispatch answers `413` instead of the
    handler's response, then, if the client is still sending, resets the rest of the upload with
    `RST_STREAM(NO_ERROR)` (CHAPPE-023);
  - once the response has started, the stream is reset with `RST_STREAM(CANCEL)` through
    `resetStream`, so its id joins the remembered resets and the DATA in flight is ignored;
  - a call the router sends to a gRPC handler (`GrpcDispatch` in `dispatchStream`, the hook of the
    readTimeout exemption), or to a gRPC-Web handler in binary mode (`GrpcWebDispatch` with
    `Mode.BINARY`), is exempt, even when its messages passed the limit before the route was known
    (`#aCallRoutedToAGrpcHandlerIsNotCappedByMaxRequestSize`,
    `#aCallRoutedToAGrpcWebBinaryHandlerIsNotCappedByMaxRequestSize`). Nothing bounds the total such a
    call receives: each message is bounded only when the handler reads it, at the 4 MiB receive
    message size of the gRPC layer. A client `content-type` exempts nothing
    (`#aGrpcContentTypeDoesNotExemptAPlainHandlerFromTheLimit`);
  - a gRPC-Web call in text mode (`application/grpc-web-text`) is not exempt from the cap nor from
    `readTimeout`: `GrpcWebCallImpl` reads its whole base64 body with `readAllBytes` before decoding
    it, so an exemption made it an unbounded heap buffer with no read timeout. A body over the cap
    ends the call with `RESOURCE_EXHAUSTED` before the handler runs
    (`#aGrpcWebTextBodyOverTheLimitEndsTheCallWithResourceExhausted`), and a stalled one resets the
    stream (`Http2RequestFlowControlTest#aStalledGrpcWebTextBodyIsResetAfterTheReadTimeout`). Cost:
    large text-mode uploads need a larger `maxRequestSize`;
  - a stream gets a single RST_STREAM: `writeRstStream` checks and sets `resetSent` under the
    stream's reset lock, so when an early response's `RST_STREAM(NO_ERROR)` and the cap's
    `RST_STREAM(CANCEL)` race, the second is a no-op (RFC 9113 §5.1). This covers every reset path
    (`NO_ERROR`, `CANCEL`, `STREAM_CLOSED`, `FLOW_CONTROL_ERROR`; `Http2StreamResetOnceTest`);
  - the application's `Body.release()` is no longer run under the stream's reset lock: a reset
    releases the response body after letting the lock go. It still runs on the thread that resets,
    the connection thread included.
- **Related**: Foy BUG-20261011-11 (`foy/BUG.md`), which CHAPPE-020, CHAPPE-021 and this entry fix on
  the chappe side. That entry is closed from Foy, once Foy builds against a chappe carrying them.

---

## CHAPPE-024 — the HTTP/2 stream window reopened on receipt, so a stream's buffer was unbounded

- **Date**: 2026-10-11
- **Status**: FIXED (commits `fix(http): open the HTTP/2 stream window as the request body is read (CHAPPE-024)`
  and `fix(http): bound an HTTP/2 header block at the max header list size (CHAPPE-024)`, branch
  `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`h2/Http2Connection`, `h2/Http2Stream`, `h2/Http2BodyInputStream`)
- **Symptom**: `handleData` sent the stream WINDOW_UPDATE as DATA arrived, not as the handler read it.
  A handler that did not read let the client push without limit into the stream's unbounded queue
  (heap). The stream window was not enforced either: DATA past it was accepted. DATA padding was never
  credited back, and `readTimeout` did not bound an HTTP/2 body read. Found on the way:
  `Http2BodyInputStream.read(b, off, 0)` blocked until the next DATA frame instead of returning 0, so
  `InputStream.readNBytes(n)`, which asks for zero bytes once its buffer is full, hung on a body that
  had not ended.
- **Minimal reproduction**: `chappe-tests/.../Http2RequestFlowControlTest#theStreamWindowReopensOnlyAsTheHandlerReadsTheBody`
  (a WINDOW_UPDATE for the stream came before any read).
- **Cause**: flow control was written for throughput (credit on receipt), with no back-pressure.
- **Fix**: `Http2BodyInputStream` reports what it consumes; `Http2Stream.bodyBytesConsumed` sends the
  stream WINDOW_UPDATE once half the window is pending. Padding is credited at receipt. DATA past the
  stream window is a stream error `FLOW_CONTROL_ERROR`; DATA past the connection window, on any stream
  (served, reset or closed), is a connection error `FLOW_CONTROL_ERROR`. The connection window is still
  credited on receipt, so an unread stream never blocks the others; a connection buffers at most
  `MAX_CONCURRENT_STREAMS` (100) × 64 KiB. A body read waits at most `readTimeout` for the next DATA
  frame, then throws `SocketTimeoutException` (the HTTP/2 side of CHAPPE-022); the wait is a timed
  poll of the stream's queue, with no timer thread, and returns at once when a chunk is queued. A call
  the router sends to a gRPC handler (`Router.grpc` / `grpcWeb`, i.e. a `GrpcDispatch` /
  `GrpcWebDispatch` answer) is exempt (ruling F-04): a client or bidi streaming call may pause for as
  long as it likes. The exemption is decided at dispatch, never from the client's `content-type`,
  which would let any client switch the timeout off on any handler. No WINDOW_UPDATE follows a
  RST_STREAM this endpoint sent on the stream (§5.1): both are written under a per-stream lock. A
  read of zero bytes returns 0 at once.
- **Same class, header blocks (CONTINUATION flood, CVE-2024-27316 class)**: a header block (HEADERS
  plus its CONTINUATION frames) was accumulated without bound until END_HEADERS; `HpackDecoder`
  checks `maxHeaderListSize` only once the block is complete. A client that never set END_HEADERS
  grew the heap at will, on a served stream and, since CHAPPE-023 decodes their blocks, on a refused
  or reset stream too. Kept under this id rather than a new one: CHAPPE-026 and later are reserved for
  the next PR. Reproduction: `chappe-tests/.../Http2HeaderBlockLimitTest` (no GOAWAY, the server kept
  reading). Fix: before a fragment is buffered, a block that would pass the advertised
  `SETTINGS_MAX_HEADER_LIST_SIZE` (8,192 bytes) is a connection error `ENHANCE_YOUR_CALM`. RFC 9113
  §10.5.1 requires a block to be processed unless the connection is closed, and it cannot be decoded
  piecemeal here, so the connection is closed; §10.5 names `ENHANCE_YOUR_CALM` for such abuse, and
  the HPACK decoder already uses it for a decoded list past the same size. It is neither a
  `COMPRESSION_ERROR` (the HPACK is not malformed) nor a `PROTOCOL_ERROR` (the framing is valid).
- **Follow-ups (open)**:
  - (a) (FIXED in CHAPPE-029) HTTP/2 writes had no `writeTimeout` (it bounded HTTP/1.1 only). A
    peer that stopped reading its socket could block any HTTP/2 write indefinitely, and with it the
    stream WINDOW_UPDATE a body read sends: a request body read could block behind it, beyond
    `readTimeout`. Each flush is now bounded by `writeTimeout`.
  - (b) The HTTP/2 header-list limit is a fixed 8,192 bytes (`Http2Settings.DEFAULT`), not derived
    from `ServerConfig.maxHeaderSize`, so raising `maxHeaderSize` does not help HTTP/2, and a header
    section that fits HTTP/1.1 can fail over HTTP/2. Past it, the connection ends with
    `GOAWAY(ENHANCE_YOUR_CALM)`, which cuts every other stream, where HTTP/1.1 answers `431` on the
    request. Fix: derive `SETTINGS_MAX_HEADER_LIST_SIZE` from `maxHeaderSize` (with room for the
    32-byte per-field overhead), and between it and a hard accumulation cap, decode with a discarding
    sink and answer `431` on the stream.
  - (c) Once a stream id has been evicted from the ring of the last 256 reset or refused ids
    (`ResetStreams`, CHAPPE-023), each DATA frame the client still sends on it is answered with its
    own `RST_STREAM(STREAM_CLOSED)`: one frame out for each frame in. The client pays for it in
    connection window, but nothing stops it. Fix: answer such a stream once, or count these resets
    and end the connection with `GOAWAY(ENHANCE_YOUR_CALM)` past a threshold.
  - (d) HEADERS that arrive on a live stream after its END_STREAM (a second header block that is not
    trailers) are not answered with `STREAM_CLOSED` as RFC 9113 §5.1 requires for a half-closed
    (remote) stream. Pre-existing, not changed by this PR. Fix: check `remoteEnded` when a header
    block completes on a known stream.

---

## CHAPPE-023 — HTTP/2 frames that follow an early response tear the whole connection down

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): reset an unread HTTP/2 request body and ignore late frames (CHAPPE-023)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`h2/Http2Connection`, new `h2/ResetStreams`)
- **Symptom**: when a handler answered before the end of its request body, the stream was removed
  after the response and no RST_STREAM was sent. The client kept uploading, and its next DATA frame
  (or its trailers) hit `DATA for unknown stream` / `Invalid stream ID`: `GOAWAY(PROTOCOL_ERROR)`,
  every other stream of the connection cut. The same happened to DATA the client had sent on a stream
  the server refused (`REFUSED_STREAM`) or reset for flow control. The header block of a refused stream
  was never decoded, so a block inserting into the HPACK dynamic table desynchronised the connection.
  DATA padding was not counted in flow control, so a long padded upload stalled.
- **Minimal reproduction**: `chappe-tests/.../Http2EarlyResponseTest#lateDataOnTheResetStreamIsIgnoredButCountsAgainstTheConnectionWindow`
  and `#aRefusedStreamKeepsTheConnectionAndTheHpackContext` (GOAWAY before the fix);
  `Http2PaddedDataFlowControlTest#aPaddedUploadLargerThanTheWindowCompletes` (stall before the fix).
- **Cause**: `dispatchStream` removed the stream without telling the client; every frame on a stream
  missing from `streams` was a connection error.
- **Fix**: after a complete response, a stream whose request END_STREAM has not arrived is reset with
  `RST_STREAM(NO_ERROR)` (RFC 9113 §8.1). The ids of the last 256 streams the server reset or refused
  are remembered: DATA on them is ignored but counted against the connection window, and their HEADERS
  and CONTINUATION blocks are decoded and dropped (§5.1). DATA on another closed stream, or on a stream
  whose request already ended (half-closed remote), is a stream error `STREAM_CLOSED` (§5.1, §6.1);
  DATA on stream 0 or a stream never opened stays a connection error. The flow-controlled length of a
  DATA frame now includes its padding.

---

## CHAPPE-022 — ServerConfig.readTimeout is never applied

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): apply readTimeout to HTTP/1.1 request body reads (CHAPPE-022)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`HttpConnection`, new `TimedReadChannel`, `SslHandler`), `chappe-api` (Javadoc)
- **Symptom**: `Server.Builder.readTimeout(Duration)` was stored and never read. A request body read
  blocked for as long as the client sent nothing: a stalled upload pinned the handler, and the drain
  of an unread body held the connection until the client closed it.
- **Minimal reproduction**: `chappe-tests/.../RequestBodyReadTimeoutTest#aStalledContentLengthBodyFailsTheReadAfterTheReadTimeout`
  (`Content-Length: 10`, 3 bytes sent: the handler waited forever).
- **Cause**: body streams read the raw channel. `SO_TIMEOUT` does not apply to channel reads, and only
  the idle wait was bounded (CHAPPE-005).
- **Fix**: body streams read their channel through `TimedReadChannel`, below the
  `RequestBodyInputStream`, so the wire-byte drain budget of CHAPPE-021 still applies. Bytes chappe
  already holds (the parse buffer, or decrypted bytes held by the `SslHandler`) take no timer. Any
  other read arms a deadline (a compare-and-set) that one watchdog virtual thread per connection
  follows; no thread is started per read, and the socket's blocking mode is never switched (that
  would take the write lock: a body read would wait behind a blocked response write, full duplex).
  A timeout too large for nanoseconds is saturated instead of failing the connection. At the
  deadline the connection is closed and the read throws `SocketTimeoutException`; zero or negative
  disables the bound. The HTTP/2
  counterpart is CHAPPE-024.
- **Found while fixing**: over TLS, once the connection was closed under the handler,
  `SslHandler.write` returned `0` (the engine's outbound side is done) instead of failing, so the
  response writer looped until `writeTimeout` (30 s) and the connection thread outlived the close.
  It now throws `ClosedChannelException` when the closed engine refuses bytes
  (`TlsLingeringCloseTest#aStalledBodyOverTlsFailsTheReadAfterTheReadTimeout` asserts a prompt stop).
- **Also in this commit (CHAPPE-021 follow-ups)**: `ServerConfig` refuses a negative `maxDrainSize`
  in its canonical constructor too; `HttpBodyReader.drain` takes a `RequestBodyInputStream`, so no
  body stream reaches the drain without the wire-byte hook.
- **Follow-ups (open)**:
  - (a) `BoundedWrites`, which applies `writeTimeout` to HTTP/1.1 response writes (pre-existing, not
    changed by this PR), starts one virtual thread per bounded write, where `TimedReadChannel` arms a
    deadline that one watchdog per connection follows (ruling F-05 for reads). A response written in
    many small writes starts as many threads. Fix: give writes the same per-connection deadline and
    watchdog.

---

## CHAPPE-021 — the drain of an unread request body has no bound

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): bound the drain of an unread request body (CHAPPE-021)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`HttpBodyReader`, `HttpConnection`, `RequestBodyInputStream`,
  `ChunkedInputStream`), `chappe-api` (`ServerConfig`, `Server.Builder`), `chappe-core`
  (`ChappeServerBuilder`)
- **Symptom**: when a handler returned without reading its request body, `HttpBodyReader.drain` read
  the rest before the next request, with no byte bound. A client that kept sending a chunked body held
  the connection and its virtual thread for as long as it sent; a `Content-Length` body up to
  `maxRequestSize` (10 MiB) was pulled in full only to reuse the connection. The same unbounded drain
  ran before a WebSocket or generic upgrade. Seen from Foy as BUG-20261011-11.
- **Minimal reproduction**: `chappe-tests/.../UnreadBodyDrainTest#anEndlessChunkedUploadIgnoredByTheHandlerIsCutOff`
  (the client never reached the end of the connection while it uploaded).
- **Cause**: `drain(InputStream)` read to `-1`, without a budget.
- **Fix**: new `ServerConfig.maxDrainSize` (`Server.Builder.maxDrainSize(long)`, default 1 MiB, the
  same budget as the lingering close). `drain(InputStream, long)` returns `false` past it, and the
  connection closes gracefully instead (lingering close), after the response; an upgrade is refused
  the same way, without its `101`. The budget counts wire bytes: a chunked body counts its size lines,
  extensions, CRLFs and trailers (`RequestBodyInputStream.limitRemainingWireBytes`), and the unread
  rest of a chunk the handler started counts too, so framing alone cannot outlast it. A failed read
  during the drain ends it and closes the connection.
- **Related**: Foy BUG-20261011-11 (`foy/BUG.md`), closed from Foy once it builds against this fix.

---

## CHAPPE-020 — a chunked request body is not capped by maxRequestSize

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): cap chunked request bodies at maxRequestSize (CHAPPE-020)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`ChunkedInputStream`, `HttpConnection`), `chappe-api`
  (`RequestBodyTooLargeException`)
- **Symptom**: `maxRequestSize` refused a `Content-Length` above it with a `400` (RFC 9110 §15.5.14
  calls for `413 Content Too Large`), and a chunked body was decoded to its end whatever its size. A
  handler reading it (`readAllBytes`) buffered all of it, and chappe's own drain of an unread body
  read all of it before the next request.
- **Minimal reproduction**: `chappe-tests/.../ChunkedBodyLimitTest#aChunkedBodyOverTheLimitFailsTheReadAndClosesTheConnection`
  (`maxRequestSize(1024)`, three 400-byte chunks: the handler read 1,200 bytes and the pipelined
  request behind the body was answered).
- **Cause**: `setupBody()` passed no limit to `HttpBodyReader.chunked`, and `ChunkedInputStream` had
  no counter.
- **Fix**: `ChunkedInputStream` counts the body as it is on the wire against `maxRequestSize`: chunk
  data (counted when its size line is read), size lines with their leading zeros and extensions,
  CRLFs and the trailer section. A chunk that would take the body past the cap fails the read with
  the new `RequestBodyTooLargeException` (an `IOException` carrying `limit()`), before any of its
  data is read; so does a framing byte past the cap, which bounds an endless extension or trailer
  (`ChunkedBodyLimitTest#chunkFramingOverheadAloneCanExceedTheLimit`,
  `#anEndlessChunkExtensionIsCountedAgainstTheLimit`, `#anEndlessTrailerLineIsCountedAgainstTheLimit`).
  Whatever the cap, one chunk-size line (extensions included) or one trailer line is bounded by
  `ChunkedInputStream.MAX_CHUNK_LINE_LENGTH` = 8192 bytes, the default `maxHeaderSize` (chappe has no
  per-line header limit to reuse), so the trailer parser never buffers a line without bound
  (`#aChunkExtensionLongerThanTheLineLimitFailsTheRead`, `#aTrailerLineLongerThanTheLineLimitFailsTheRead`).
  A failed read is final: every later read rethrows the same exception instead of parsing the refused
  chunk's data as size lines (`#aReadAfterTheCapFailureRethrowsIt`). The body stream is then
  `failed()`, so the connection closes with a lingering close and reads no other request
  (CHAPPE-013, CHAPPE-014). A
  `Content-Length` above `maxRequestSize` is now answered `413` instead of `400`
  (`ChunkedBodyLimitTest#aContentLengthOverTheLimitIsAnswered413BeforeTheHandlerRuns`,
  `Http11StatusCodesTest#payloadTooLarge413`). The body is now set up before `Expect: 100-continue`
  is answered, so a refused `Content-Length` gets its `413` (or `400`) instead of `100 Continue`
  first (RFC 9110 §10.1.1;
  `ChunkedBodyLimitTest#anExpectContinueWithAContentLengthOverTheLimitGets413Without100Continue`).
  Cost: a body near the cap with heavy chunk overhead trips slightly earlier than its data alone
  would. A `RequestBodyTooLargeException` that escapes the handler, as is or as a cause, or after a
  read of this body threw one, is answered `413` instead of `500`, as over HTTP/2 (CHAPPE-025): the
  response is not committed yet at that point. The connection still closes after it
  (`#aCapFailureEscapingTheHandlerIsAnswered413AndClosesTheConnection`,
  `#aWrappedCapFailureEscapingTheHandlerIsAnswered413`). A handler that catches the exception keeps
  its own response over HTTP/1.1, where HTTP/2 replaces it with the `413`. A cap failure during the
  response write (a live response body reading the request) closes the connection, as before.
- **Related**: Foy BUG-20261011-11 (`foy/BUG.md`), closed from Foy once it builds against this fix.

---

## CHAPPE-019 — Filter.addHeaderIf and Filter.gzip drop the response trailers

- **Date**: 2026-10-11
- **Status**: OPEN (pre-existing, found in the CHAPPE-008 review)
- **Module**: `chappe-api` (`Filter`)
- **Symptom**: `Filter.addHeaderIf` (so `addHeader` and `addHeaderIfEnv`) and `Filter.gzip` rebuild
  the response with `Response.builder().status().headers().body()`. The rebuilt response has no
  trailers, so a handler's `Response.trailers()` (gRPC-style status trailers, Servlet trailer
  fields) is silently lost behind these filters. A trailer supplier computed after the body is lost
  too, since the builder only takes static trailers.
- **Minimal reproduction**: not written yet. Shape: `Filter.addHeader("X", "1")` around a handler
  returning a chunked body with `.trailer("t", "v")`; the HTTP/1.1 trailer section is empty.
- **Cause hypothesis**: the filters copy status, headers and body only.
- **Fix**: none yet. Option: wrap the inner response (a delegating `Response` overriding
  `headers()`, and `body()` for gzip) instead of rebuilding it, so `trailers()` is still called
  lazily on the original.

---

## CHAPPE-018 — HTTP/2 responses keep connection-specific headers and padded values

- **Date**: 2026-10-11
- **Status**: OPEN (found in the CHAPPE-008 review)
- **Module**: `chappe-http` (`h2/Http2Connection`, `h2/HpackEncoder`)
- **Symptom**: on the response side, HTTP/2 sends whatever header fields the application set:
  - connection-specific fields (`Connection`, `Keep-Alive`, `Proxy-Connection`,
    `Transfer-Encoding`, `Upgrade`) are encoded as is. RFC 9113 §8.2.2 makes a message carrying
    them malformed, and strict clients reset the stream;
  - a value with leading or trailing SP or HTAB is sent as is; RFC 9113 §8.2.1 makes it
    malformed too.
  `FORBIDDEN_HEADERS` in `Http2Connection` is applied to requests only. CHAPPE-008 made the
  values one byte per char without CR, LF or NUL, but not these two rules.
- **Minimal reproduction**: not written yet. Shape: a handler returning
  `Response.builder().header("Connection", "close").header("X", " padded ").build()` over h2c;
  the HEADERS frame carries `connection: close` and `x:  padded `.
- **Cause hypothesis**: the encoder was written for well-formed HTTP/1.1-style headers.
- **Fix**: none yet. Options: drop the connection-specific fields when encoding a response (and
  `te` unless it is `trailers`), and trim (or refuse with a 500) values with surrounding
  whitespace.

---

## CHAPPE-017 — an HTTP/2 response body failing mid-stream left the client stream hanging

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): reset an aborted HTTP/2 stream and fail a cut request body`,
  branch `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-http` (`h2/Http2Connection`)
- **Symptom**: when the response body threw while being sent (a live body the application
  aborted: Foy on a late exception, an async timeout on a committed response, a short
  Content-Length), `dispatchStream` only closed the stream locally. No RST_STREAM and no
  END_STREAM reached the client, which waited for the end of the stream until its own timeout.
- **Minimal reproduction**: `chappe-tests/.../Http2StreamAbortTest#aResponseBodyFailingMidStreamResetsTheStream`
  (a body yielding one byte, then throwing; before the fix the client read timed out).
- **Cause**: the `IOException` catch of `dispatchStream` sent nothing.
- **Fix**: unless the stream was already cancelled (peer reset, connection gone: RFC 9113 §5.4.2),
  the server sends `RST_STREAM(INTERNAL_ERROR)`. The HTTP/1.x counterpart aborts the connection.

---

## CHAPPE-016 — an HTTP/2 request body could be lost when its DATA frame beat the dispatch

- **Date**: 2026-10-10
- **Status**: FIXED (same commit as CHAPPE-017)
- **Module**: `chappe-http` (`h2/Http2Connection`)
- **Symptom**: a `POST` whose HEADERS frame had no END_STREAM, followed at once by a DATA frame with
  END_STREAM, sometimes reached the handler with an empty body.
- **Minimal reproduction**: `chappe-tests/.../Http2StreamAbortTest#aRequestBodyEndedByEndStreamReadsToTheEnd`
  (intermittent: it failed with `body:` instead of `body:abc` during the CHAPPE-015 work).
- **Cause**: the dispatch thread decided whether a body exists from `stream.state() == OPEN`. The
  connection thread could already have processed the DATA frame with END_STREAM, which
  half-closes the stream, so the body was never attached.
- **Fix**: the body presence is decided on the connection thread from the HEADERS frame
  (`!headersEndStream()`), and passed to the dispatch.

---

## CHAPPE-015 — an HTTP/2 stream reset or a lost connection read as a clean end of the request body

- **Date**: 2026-10-10
- **Status**: FIXED (same commit as CHAPPE-017)
- **Module**: `chappe-http` (`h2/Http2Stream`, `h2/Http2BodyInputStream`)
- **Symptom**: `Http2Stream.cancel()` (RST_STREAM from the peer, end of the connection) signalled
  END_STREAM, so `Http2BodyInputStream` returned `-1`: a cut body read as a whole, shorter body.
  The HTTP/2 counterpart of CHAPPE-011/012. In Foy, a ReadListener got `onAllDataRead` instead of
  `onError`.
- **Minimal reproduction**: `chappe-tests/.../Http2StreamAbortTest#aRequestBodyCutByAStreamResetFailsTheRead`
  and `#aRequestBodyCutByTheEndOfTheConnectionFailsTheRead` (`body:abc` before the fix).
- **Cause**: cancellation reused the END_STREAM signal.
- **Fix**: `cancel()` wakes the reader with a sentinel without marking END_STREAM; a read on a
  cancelled stream with no END_STREAM received throws `IOException`. A normal END_STREAM still
  reads `-1`, also when the stream is cancelled after the whole body arrived.

---

## CHAPPE-014 — a connection closed after a failed body read skips the lingering close

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): close gracefully after a failed request body read`, branch
  `pr/ybl/chappe-008-014`)
- **Module**: `chappe-http` (`HttpConnection`)
- **Symptom**: after a request body read fails, the connection closes with `lingerOnClose = false`
  (CHAPPE-013). The failure can be a truncated body, a reset, or an interrupt that closed the
  channel, for example Foy handing the body back from a ReadListener pump once the response is
  written. A client still uploading can then get a TCP RST, and the RST may discard the response
  it has not read yet (RFC 9112 §9.6). Over TLS, close_notify is not sent either. When an
  interrupt closed the channel, a half-close and a linger are no longer possible on it.
- **Minimal reproduction**: `chappe-tests/.../LingeringCloseTest` and `TlsLingeringCloseTest`:
  a `POST` with a chunked body that fails on its first byte (`Z0\r\n\r\n`) followed by 64 KiB
  the server never reads.
  - `#responseSurvivesAFailedBodyReadWithUnreadUploadBytes`: the handler reads the body, catches
    the failure and answers `read failed`.
  - `#responseSurvivesAFailedBodyDrainWithUnreadUploadBytes`: the handler ignores the body, and the
    connection's own drain of it fails. That drain failure went to the "lost connection" catch,
    with no linger either.
  - `#anUploaderStillSendingAfterAFailedBodyReadGetsTheResponseAndAnOrderlyClose` (plaintext): the
    client keeps sending for 500 ms (each chunk holds a whole `GET /smuggled` request), then reads.
  Before the fix the plaintext client read failed with "Connection reset", and the TLS client
  write failed with "Broken pipe".
- **Cause**: the failed-body-read paths (after the response, before an upgrade, and a failing
  drain) closed the socket outright with request bytes still unread.
- **Fix**: those paths set `lingerOnClose = readChannel.isOpen()`. The existing lingering close
  then runs: the response is written first, then a half-close (plaintext) or close_notify and a
  half-close (TLS), then input is read and discarded for at most 2 s or 1 MiB, then the socket is
  closed. The lingering drain only discards bytes, so CHAPPE-013 still holds: no next request is
  ever parsed, no upgrade head is written (`InterruptedBodyReadTest` and
  `UpgradeAfterFailedBodyReadTest` stay green). A drain that fails now takes the same path instead
  of the "lost connection" one. Each close_notify write is bounded by `writeTimeout`
  (review follow-up): a client that keeps uploading but never reads fills the send buffer, and the
  unbounded write could have held the virtual thread and the socket forever. `SslHandler.close()`,
  used by the non-lingering close paths, bounds its close_notify writes the same way.
- **Residual**: when an interrupt already closed the channel (`InterruptibleChannel` semantics),
  nothing can be done on that socket: it closes at once, as before. The fix for that case
  belongs to the caller: hand the body back without interrupting a thread blocked in a read
  (Foy's ReadListener pump).

---

## CHAPPE-013 — an interrupted body read left stale bytes readable, parsed as a next request

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): never parse a request from a failed body read`, branch
  `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-http` (`ChunkedInputStream`, `FixedLengthInputStream`, `HttpConnection`,
  `HttpRequestParser`, `h2/Http2FrameReader`, `h2/Http2Connection`, `ws/WebSocketFrameReader`)
- **Symptom** (request smuggling): every buffer refill was `compact(); channel.read(); flip();`
  without a `finally`. A read that threw skipped the `flip()`, which left the buffer at position 0
  with limit = capacity, so the stale bytes of earlier reads read as unread data. Such reads
  include a reset, a watchdog close, or an interrupt that closed the channel; the interrupt is how
  Foy hands a request body back from its ReadListener pump. The sequence was:
  1. A body read on another thread was interrupted after the response was written.
  2. The connection drained the stale bytes as the rest of the body.
  3. `open` was still true and `awaitNextRequest()` saw `readBuffer.hasRemaining()`.
  4. The parser read stale body bytes as a next request, and the handler ran a request embedded
     in the previous body (over TLS too).
- **Minimal reproduction**: `chappe-tests/.../InterruptedBodyReadTest`:
  1. `POST` with `Content-Length: n + 4`, whose body is `AAAA` followed by a whole
     `GET /smuggled` request.
  2. The handler reads those bytes on a second thread, which then blocks for more.
  3. The response body's `release()` interrupts that thread.
  Before the fix, `/smuggled` was dispatched.
- **Cause hypothesis**: the buffer state was not restored on a failed read, and the connection
  kept parsing after a failed body read.
- **Fix** (defence in depth):
  - every compact/read/flip goes through `ReadBuffers.compactAndRead` or flips in a `finally`:
    body readers, request parser, idle probe and idle wait, HTTP/2 frame reader and preface,
    WebSocket frame reader;
  - the body streams extend `RequestBodyInputStream`, which records any failed read;
  - after the handler, a failed body read or a closed read channel closes the connection: no
    drain, no next request.
  - the upgrade paths (`WebSocketUpgrade`, `ConnectionUpgrade`) apply the same guard before
    their drain: the connection closes, with no upgrade head and no hand-over
    (`chappe-tests/.../UpgradeAfterFailedBodyReadTest`: a chunked body `Z0\r\n\r\n` failed on
    `Z`, then a drain ended it cleanly and the upgrade ran on a misaligned stream).

---

## CHAPPE-012 — a Content-Length request body cut short reads as a whole, shorter body

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): a fixed-length body cut short fails the read`, branch
  `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-http` (`FixedLengthInputStream`)
- **Symptom**: when the connection ended before `Content-Length` bytes arrived, both read methods
  returned `-1`, so a handler took a truncated body for a complete one (Foy fired
  `ReadListener.onAllDataRead` instead of `onError`). Same defect as CHAPPE-011 for chunked bodies.
- **Minimal reproduction**: `chappe-tests/.../FixedLengthRequestBodyTest`: `Content-Length: 10`,
  `Hello` (or `Hel`) then a half-close; `readAllBytes()` returned `Hello`, the byte loop `Hel`.
- **Cause hypothesis**: a failed `refill()` was mapped to `-1` with bytes still expected.
- **Fix**: both read methods throw `EOFException` while Content-Length bytes are still missing.

---

## CHAPPE-011 — a chunked request body cut inside a chunk reads as a whole, shorter body

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): a chunked body cut inside a chunk fails the read`, branch
  `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-http` (`ChunkedInputStream`)
- **Symptom**: when the connection ended inside a chunk's data, the body stream reported a normal
  end of stream (`-1`). The handler took the truncated body for a complete one (Foy fired
  `ReadListener.onAllDataRead` instead of `onError`). A cut inside a chunk-size line already threw.
- **Minimal reproduction**: `chappe-tests/.../ChunkedTransferTest.aBodyTruncatedInsideAChunkFailsTheRead`:
  the client sends `5\r\nHello\r\n3\r\nab` and half-closes; `readAllBytes()` returned `Helloab`.
- **Cause hypothesis**: `read()` and `read(byte[], int, int)` mapped a failed `ensureData()` to `-1`.
- **Fix**: both throw `EOFException` when the connection ends with chunk data still expected.

---

## CHAPPE-010 — a chunked request body read waits for the next chunk instead of returning the one that arrived

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): return each request chunk as it arrives`, branch
  `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-http` (`ChunkedInputStream`)
- **Symptom**: `read(byte[], int, int)` on a chunked request body kept decoding chunks until `len`
  bytes were read or the body ended. Once a chunk was consumed it blocked on the socket for the next
  chunk size, although it already had bytes to return. A streaming reader (Foy's `ReadListener`
  pump) saw `Hello` and `World`, sent one second apart, as a single `HelloWorld` read one second
  late (TCK `ReadListenerTests.nioInputTest`).
- **Minimal reproduction**: `chappe-tests/.../ChunkedTransferTest.aReadReturnsTheArrivedChunkWithoutWaitingForTheNext`:
  the client sends `5\r\nHello\r\n` and sends the rest only after the handler's first
  `read(buf, 0, 1024)` returned; it timed out after 5 s.
- **Cause hypothesis**: the read loop ran until `totalRead == len`, through chunk boundaries and
  socket reads.
- **Fix**: a read returns at the end of each chunk, and never blocks on the socket once it has
  bytes to return. The fixed-length and HTTP/2 body streams already returned what was available.

---

## CHAPPE-009 — a response body is never closed when it is not read to the end (live producers hang)

- **Date**: 2026-10-10
- **Status**: FIXED (commit `fix(http): close the response body on every exit`, branch
  `pr/ybl/servlet-streaming-upgrade`)
- **Module**: `chappe-api` (`Body`), `chappe-http` (`HttpConnection`, `h2/Http2Connection`, `h2/Http2Stream`)
- **Symptom**: the response body stream was closed only by the try-with-resources around the body
  read. It was never closed when chappe did not reach that read or stopped short of EOF:
  - HTTP/1.1, writing the head fails (a client gone, the head larger than the write buffer);
  - HTTP/1.1, the body is suppressed (HEAD, 204, 304);
  - HTTP/2, the HEADERS write fails, or the peer resets the stream (RST_STREAM) before or while
    the body is sent (chappe kept sending, then waited for a window that never comes);
  - HTTP/2, the connection ends while a stream sends its body.
  A live body (an `InputStream` fed by a producer thread, such as Foy's servlet output pipe or a
  `PipedInputStream`) then left its producer blocked forever on a full buffer.
- **Minimal reproduction**: `chappe-tests/.../ResponseBodyReleaseTest.java`. A handler returns
  `Body.of(stream, -1)` over a stream that yields nothing until closed. For the four cases (HEAD;
  RST-closed client and a 512 KiB head; HTTP/2 RST_STREAM; HTTP/2 client gone), the stream was
  never closed within 5 s.
- **Cause hypothesis**: the body's lifetime was tied to the read loop, not to the exchange.
- **Fix**:
  - New `Body.release()` default method (no-op by default; `InputStreamBody` closes its stream,
    `OutputStreamBody` closes the pipe it handed out).
  - HTTP/1.1 releases the handler's response body in a `finally` around everything that follows the
    handler call.
  - HTTP/2 releases it in `dispatchStream`'s `finally` and attaches it to the stream:
    - `Http2Stream.cancel()` (RST_STREAM, connection close) releases the body and wakes a sender
      parked on the stream window;
    - the connection-window waiters are woken too;
    - `waitForSendWindow` ends with an `IOException` for a cancelled stream;
    - `sendResponse` sends nothing for a stream reset before its HEADERS (checked before HPACK
      encoding, so the dynamic table never records an unsent block).

---

## CHAPPE-008 — HTTP/1.1 writer truncates header chars to one byte (header injection)

- **Date**: 2026-10-10
- **Status**: FIXED (trailers and the upgrade head in `pr/ybl/servlet-streaming-upgrade`; ordinary
  headers, HTTP/2 and the API in commit `fix(http): validate response header fields everywhere`,
  branch `pr/ybl/chappe-008-014`)
- **Module**: `chappe-api` (`Response.Builder`, `Filter`, `WebSocketUpgrade`, `ConnectionUpgrade`,
  `GrpcWebBufferedCall`, new `internal.HeaderFields`), `chappe-http` (`HttpResponseWriter`,
  `HttpConnection`, `h2/HpackEncoder`, `h2/Http2Connection`, `grpc/GrpcCallImpl`,
  `grpc/GrpcWebCallImpl`)
- **Symptom**: `putAsciiString` casts each `char` of a header name or value to a `byte`, so a char
  above U+00FF loses its high byte. U+010D and U+010A become CR and LF on the wire, so a value such as
  `"ačĊSet-Cookie: a=b"` injects a header line (response splitting). Names and values are
  not validated at all, so plain CR, LF and NUL go through as well. HTTP/2 encoded values with
  `getBytes(ISO_8859_1)`: a char above U+00FF became `?`, and CR, LF and NUL went through (malformed
  per RFC 9113 §8.2.1, dangerous behind an HTTP/2-to-HTTP/1.1 proxy).
- **Minimal reproduction**: a handler returning
  `Response.builder().header("X", "ačĊSet-Cookie: a=b").build()`. The raw response then
  carries a separate `Set-Cookie: a=b` line. Regression tests:
  `chappe-tests/.../ResponseHeaderValidationTest` (16 tests; before the fix 14 failed: the builder
  accepted every invalid field, HTTP/1.1 answered `200` with the injected line, HTTP/2 sent
  `x-evil: a??b`, a `:status` pseudo-header from the application, and invalid trailers).
- **Cause hypothesis**: the writer assumes the application hands it ASCII header text.
- **Cross-reference**: foy `BUG-20261009-11`. Foy already rejects CR, LF, NUL and chars above U+00FF
  before they reach chappe (foy 2f7fcf0), so Servlet applications are covered. Other chappe users
  are not.
- **Investigations**:
  - 2026-10-10 (Foy Phase 5, task 5.2): response trailers are validated before writing. Names must be
    RFC 9110 §5.6.2 tokens. Values may contain only field-vchar, obs-text, SP and HTAB, so nothing
    above U+00FF gets through. See `ResponseTrailersHttp11Test.trailerValueAboveLatin1IsDropped`. The same
    check, or a 500 for an invalid header, still has to be applied to ordinary headers in `write()`.
    That was deliberately left out of this task.
  - 2026-10-11: ordinary headers fixed.
- **Fix**: one rule, in `io.vidocq.chappe.api.internal.HeaderFields` (exported to
  `io.vidocq.chappe.http` only): a name is a token; a value holds only field-vchar, obs-text,
  SP and HTAB. It is checked in three layers:
  - **API, fail fast**: `Response.Builder.header/headers/trailer/trailers`, `Filter.addHeader*` (at
    filter creation), the `WebSocketUpgrade` subprotocol (a token), `GrpcCall.addHeader/addTrailer`
    (the three implementations) and `ConnectionUpgrade` (already) throw `IllegalArgumentException`.
    The message names the field (escaped) and never echoes the value. `Headers.builder()` stays
    unchecked: it also builds request headers and trailers parsed from the wire.
  - **HTTP/1.1 writer**: `checkHead` checks every header and a custom reason phrase before a byte
    is buffered. On failure `HttpConnection` logs a warning, answers `500` and closes. Trailers
    are still dropped. `putAsciiString` refuses a char above U+00FF with an `IOException` instead of
    truncating it. Each field is read once, checked and written from that same read (review
    follow-up): a custom `Headers` returning other text on a second iteration cannot slip CR LF
    through (`ResponseHeaderValidationTest#http11WritesTheFieldsItChecked`). A field found invalid
    after part of a very large head was flushed drops the connection instead of answering 500.
  - **HTTP/2**: `Http2Connection` answers `500` for an invalid header. `HpackEncoder.encode` checks
    every field of a one-read snapshot before encoding the first one, so a refusal leaves the
    dynamic table untouched and the encoded fields are the checked ones
    (`#http2EncodesTheFieldsItChecked`).
    `encodeTrailers` drops invalid trailers, as HTTP/1.1 does. A `RuntimeException` while sending
    now resets the stream instead of leaving it open.
  - There is no HTTP/3 (QPACK) code in chappe yet: nothing to fix there.

---

## CHAPPE-007 — `RobustnessTest.oversizedHeader` fails with "Connection reset by peer" (main red since 2026-09-21)

- **Date**: 2026-09-25
- **Status**: FIXED (`fix/lingering-close`, issue #19): lingering close in `HttpConnection`
- **Module**: `chappe-conformance` — `RobustnessTest.oversizedHeader` (test code)
- **Symptom**: the `build-and-deploy` run on `main` of 2026-09-21 (task 60868) fails with
  `RobustnessTest.oversizedHeader:197->write:237 » Socket Connection reset by peer`. Main has been red since then.
- **Minimal reproduction**: CI only so far, and not every run. The test sends a 16 KiB
  header value, which is larger than `maxHeaderSize`, then writes two more lines.
- **Cause hypothesis**: a race in the test. When the server has read enough to reject
  the oversized header, it may answer and close before the client has written
  `Connection: close` and the blank line. Those `write` calls sit **outside** the
  `try { … } catch (SocketException _)`, which covers `readResponse` only. The reset
  then escapes. The server's behaviour, a 431/400 or a closed connection, is what the test
  itself declares acceptable. Likely fix: move the writes inside the `try`.
- **Investigations**:
  - 2026-09-25: read the failing log (task 60868, runner `87c30ab4`) and the test source; line 197 is
    the `Connection: close` write. Not reproduced locally.
  - 2026-09-25: the root cause is the server's, not only the test's. `HttpConnection.run()` ends with
    `close()` while request bytes are still unread, so the TCP stack sends a RST, and the RST also destroys
    the response the client has not read. `LingeringCloseTest` shows it deterministically: the 431, a
    400 and a `Connection: close` 200 are all lost to `Connection reset`, on macOS and on Linux (Temurin
    25 container). Fixed with a lingering close (RFC 9112 §9.6): `shutdownOutput()`, then a bounded drain
    (2 s / 1 MiB) through `BoundedReads`, then `close()`. `RobustnessTest.oversizedHeader` passes unchanged.

## CHAPPE-006 — `TlsTest.requestCoalescedWithHandshakeFlightDoesNotHang` times out on the CI runners

- **Date**: 2026-09-25
- **Status**: FIXED (`fix/chappe-006-tls-timeout`): the bug was in the test's hand-driven TLS client, not in the server
- **Module**: `chappe-tests` — `TlsTest.requestCoalescedWithHandshakeFlightDoesNotHang` (CHAPPE-004 bug C regression test)
- **Symptom**: `execution timed out after 10000 ms` (`assertTimeoutPreemptively`) in
  `pr-validate` on PR #16, twice: 2026-09-25 07:08 (runner `6c935f1a`, task 63927) and 08:17
  (runner `ababeffb`, task 64135), both runners Forgejo runner v13.0.0. The PR changes docs only.
  The same suite passed on PR #15 on 2026-09-21 (task 60792, runner `6c935f1a`).
- **Minimal reproduction**:
  ```
  ./mvnw -o -pl chappe-tests -am install -Dtest=TlsTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
  Passes 3/3 locally on `47ff813` (macOS arm64, Temurin 25). Fails on the Codefloe runners.
- **Cause hypothesis**: to investigate. There are two candidates:
  1. The test is too slow on a loaded runner: the handshake is driven by hand through `SSLEngine`, and the whole exchange is bounded by 10 s.
  2. A real return of the CHAPPE-004 bug C deadlock under the runners' TCP timing: a
     coalesced Finished + application record left in `netInBuffer` while the server blocks
     on `channel.read()`. A local pass does not rule this out, since loopback on macOS may
     not coalesce the same way. `fix: wire writeTimeout to bound a single blocking write (#13)`
     landed just before, but #15 passed after it.
  A thread dump on timeout would settle it.
- **Investigations**:
  - 2026-09-25: two CI failures on two runners, three local passes; logs read from the job logs API.
  - 2026-09-25: **reproduced locally in a Linux container** (`eclipse-temurin:25-jdk`, arm64, OrbStack):
    `TlsTest` alone, 8 runs from the reactor, timed out in 1 of the 8 runs on `main` (`8f1cbc4`) and in 1 of the 8 runs with the
    TLS lingering close of #21. The TLS lingering close does not change it. This is a separate
    issue, and macOS has not reproduced it so far. Next step: a thread dump on timeout
    (`HotSpotDiagnosticMXBean.dumpThreads`, which includes virtual threads) to see whether the
    server is stuck in `SslHandler` or the hand-driven client waits for a close that never comes.
  - 2026-09-25: **root cause found, and it is in the test, not in the server.** The test now dumps every thread,
    virtual ones included (`HotSpotDiagnosticMXBean.dumpThreads`), when it times out. The dump showed the
    server in `SslHandler.doHandshake` waiting for the client's second flight, and the test client still in
    **its own** handshake read. Tracing both sides showed the path. The client sometimes gets the whole
    server flight (ServerHello + ChangeCipherSpec + encrypted flight, 1284 bytes) in one read. It unwraps
    the ServerHello, and TLS 1.3 then asks for NEED_WRAP (its compatibility ChangeCipherSpec) with 1157
    server bytes still buffered. Back in NEED_UNWRAP, it called `channel.read()` before unwrapping those
    bytes, so it waited for bytes the server had already sent. This is the pattern of CHAPPE-004 bug C,
    on the client side. When the flight arrived in two reads (133 + 1151 bytes), the test passed. Fix: the
    client unwraps what is buffered first and reads only when the buffer is empty or the last unwrap
    underflowed. Linux container: 16/16 green, against 1 timeout in 8 before.

## CHAPPE-005 — Idle timeout was a silent no-op: idle keep-alive connections never closed

- **Date**: 2026-06-12
- **Status**: FIXED (`pr/ybl/forwarding-request` — `BoundedReads` watchdog)
- **Symptom**: `ServerConfig.idleTimeout` (default 60 s) had no effect — an
  idle keep-alive connection, or a client that connects and never sends a
  request (slowloris), held its connection (and virtual thread) forever.
  Discovered through the Servlet TCK 6.1.0 `TrailerTest`, whose client reads
  the response to EOF on a keep-alive connection and relies on the
  container's keep-alive timeout to close it (fixed upstream by switching to
  `Connection: close`, jakartaee/servlet commit `079ceb29cb`) — the foy TCK
  suite froze on it (foy BUG-20260611-01).
- **Minimal repro**: `nc <host> <port>` and send nothing, or one full request
  then nothing — the connection stayed open indefinitely.
- **Root cause**: the accept loop configured
  `clientChannel.socket().setSoTimeout(timeoutMs)`, but `SO_TIMEOUT` is a
  silent no-op for blocking `SocketChannel` reads (it only applies to
  `Socket.getInputStream()` reads) — a classic NIO trap. The intent existed,
  the mechanism never fired.
- **Fix**: `BoundedReads.readWithTimeout` — a virtual-thread watchdog closes
  the channel at the deadline, waking the blocked read
  (`AsynchronousCloseException`); `Selector.select` was rejected because it
  pins the carrier thread. Applied to the keep-alive wait in
  `HttpConnection.run()` (fast non-blocking probe first, so back-to-back
  traffic pays nothing) and to the first protocol-sniffing read in
  `ChappeServer.handleCleartextConnection`. TLS handshake reads are not
  bounded yet (follow-up if needed). Tests: `KeepAliveIdleTimeoutTest`.

---

## CHAPPE-004 — Intermittent HTTP `400 Bad Request` on `GET /` under concurrent reactor load
- **Date**: 2026-06-03 — **Status**: FIXED
- **Severity**: high (real server-side race — not a CLI-specific bug as first thought)
- **Surfaced by**: workspace-wide verification run (`./mvnw clean install` on `chappe`)

### RESOLUTION (2026-06-03)
The flake was NOT cross-server byte contamination (the early hypothesis below) and
NOT `SO_REUSEPORT`. It was **two independent partial-read bugs**, both timing/load
dependent, which is why the failing test mutated every run:

1. **HTTP/2 cleartext preface detected on a single `read()`**
   (`ChappeServer.handleCleartextConnection`). The 24-byte preface
   (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`) was matched only if the *first* `channel.read()`
   returned ≥6 bytes. TCP may split the preface across segments under load, so a short
   first read mis-routed an h2c connection to the HTTP/1.1 parser, which then choked on
   the binary SETTINGS frame and closed mid-handshake → client saw `EOFException` /
   `400` in ~5 ms. **Fix**: loop reading until enough bytes to confirm/reject the preface
   prefix, bailing to HTTP/1.1 the moment a byte diverges (`matchesPrefacePrefix`).

2. **`SslHandler.readInternal` deadlock on handshake-coalesced application data**
   (the real cause of the *hangs*, proved by a `jcmd Thread.dump_to_file` virtual-thread
   dump showing the server VT parked in `SslHandler.read` → `channel.read`). When the
   client's first application bytes (the HTTP request) arrive in the same TCP segment as
   the final TLS handshake flight, `doHandshake()` leaves them compacted in `netInBuffer`.
   `readInternal()` called `channel.read()` *before* unwrapping that buffer, so a client
   that had sent its full request and was waiting for the response blocked the server
   forever. **Fix**: unwrap `netInBuffer` first; only `channel.read()` on `BUFFER_UNDERFLOW`.

Also removed `SO_REUSEPORT` (kept `SO_REUSEADDR`): harmless for `port=0` tests but a real
hazard for fixed-port deployments — a single-accept-loop server gains nothing from kernel
load-balancing and it would let two live `Server` instances share one port.

3. **Cross-PROCESS ephemeral-port collision on a wildcard `bind(0.0.0.0, 0)`**
   (found while validating — the "cross-server contamination" of the early hypothesis was
   in part this). A test server binding the IPv4 wildcard on an ephemeral port can be
   handed a port a *foreign process* already holds on `127.0.0.1`/`::1` (a different
   address family, so the two binds silently coexist — verified: `bind(0.0.0.0, P)`
   succeeds even when `P` is taken on loopback, with or without `SO_REUSEADDR`, whereas
   `bind(127.0.0.1, P)` correctly fails). A loopback client is then sometimes routed to
   the foreign socket — observed as a test receiving a `301` from an IDE's built-in web
   server (`server: IntelliJ IDEA`) and a `Invalid status line` from a local DB driver.
   **Fix**: for `port == 0`, probe `127.0.0.1:0` first to obtain a loopback-free port,
   then bind the real listener (on `config.host()`) to it (`ChappeServer.resolveBindPort`).
   `SO_REUSEADDR` is irrelevant to this and was left unconditional. Production fixed-port
   binds are unaffected.

**Validation**: 10/10 clean `./mvnw clean install` runs for the protocol fixes (0 RED,
0 HUNG; prior ~40% RED + ~25% HUNG), then 12/12 clean `chappe-tests` runs after the
port-collision fix (the foreign-process collision reproduced ~1 run in 5 before).
jstack/jcmd evidence captured during a live stall; bind semantics confirmed empirically.
**Note**: the fix v1 (close active client sockets in `stop()`, commit `7591765`) remains
valid defensive hardening but was not what closed this bug.

### Symptom
Running `./mvnw clean install` on the `chappe` reactor produces an intermittent failure that
*moves between tests* across runs:
- Run 1: `ServeCommandTest.servesIndexFromYamlConfig` → `expected: <200> but was: <400>`
- Run 2: `Http2GrpcTransportTest.unaryEcho` → `EOFException` at handshake
- Run 3: `ExtensionSpiTest.requestUriHasAuthorityFromHostHeader` → `expected: <200> but was: <400>`

Common pattern: **a perfectly valid HTTP/1.1 (or HTTP/2 handshake) request gets rejected with
`400 Bad Request` (or the connection is closed mid-handshake) when the reactor is under
multi-module test load**. In isolation (`mvn -pl chappe-cli test` or running the single test
5×) every test passes — failure rate ≈ 33% across the full reactor.

### Diagnostic so far
- Branch clean, HEAD `d8e0b51`, no local diff
- 5/5 isolated runs of `ServeCommandTest#servesIndexFromYamlConfig` → green
- 3/3 isolated runs of full `chappe-cli` test suite → green
- 5 full `./mvnw clean install` runs → 2 green, 3 red — **the failing test mutates every run**:
  - `ServeCommandTest.servesIndexFromYamlConfig` (400 vs 200)
  - `Http2GrpcTransportTest.unaryEcho` (EOFException at handshake)
  - `ExtensionSpiTest.requestUriHasAuthorityFromHostHeader` (400 vs 200)
  - `HttpMethodsTest.patchWithBody` (`HTTP/1.1 header parser received no bytes`)
  - `ExtensionSpiTest.staticFilePathTraversal` (**401 vs 403** — see below)
  - `Http2GrpcTransportTest.gzipRequestDecodedAutomatically` (`Broken pipe` mid-write)
- All failing tests use real socket I/O against a `port=0` server
- **Confirmed not related to a `:8080` port collision** — even with 8080 free, 3/3
  full reactor runs still flake on different tests

### Critical signal — `401` instead of `403` on a static-file test
`ExtensionSpiTest.staticFilePathTraversal` tests a path traversal attempt against a
**static-file server with no auth in scope**. The server simply cannot produce a `401
Unauthorized` from its own routes — there is no challenge, no realm. Yet the client
receives one. The only plausible source is a **cross-connection contamination**: the
client socket reads bytes that belong to a *different* `Server` instance running
elsewhere in the JVM (or a different test in the same fork) that legitimately answered
`401` for an auth-protected endpoint.

This pinpoints the cause to **JVM-wide shared mutable state**:
- A `static` `ByteBufferPool` whose recycled buffers leak bytes across `Server` instances;
- A `static` socket / `ServerSocketChannel` map keyed on something not unique per `Server`;
- A `static` `RequestContext` / `ScopedValue` carrier that bleeds across handlers when
  multiple servers run in parallel.

The 400-vs-200, EOF, Broken-pipe, "no bytes" symptoms all fit the same root cause:
the parser ingests bytes that belonged to a previous request (whether on the same
connection or on a sibling Server's pool), parses junk, and either rejects (400) or
closes mid-frame (EOF / Broken pipe).

### Cause hypothesis
Server-side race condition — likely candidates:
1. **`ByteBufferPool` corruption** under concurrent connection burst (a buffer is recycled
   while still being read by another thread).
2. **Parser state leak** — `Http11Parser` or HPACK decoder reuses state from a previous
   connection (no per-connection isolation).
3. **`RequestContext.CURRENT` ScopedValue leak** between virtual threads.

The fact that the symptom mutates between `400 Bad Request` and `EOFException` strongly
suggests the server reads garbage bytes (recycled buffer) and either fails the request-line
parse (→ 400) or closes mid-frame (→ EOF).

### Minimal repro
```bash
cd chappe
for i in 1 2 3 4 5; do ./mvnw -ntp clean install >/tmp/r$i.log 2>&1; \
   grep "Tests run.*Failures: [1-9]" /tmp/r$i.log | head -1; done
```
≈ 33-50% red rate on a quiet machine.

### Investigation results (this session)
- `ByteBufferPool` is a per-instance field of `ChappeServer` (not a static singleton),
  and its `ThreadLocal<ArrayDeque<ByteBuffer>>` is per-instance too → pool isolation OK.
- The 401 in `ExtensionSpiTest.staticFilePathTraversal` matches exactly the 401 that
  `RouterTest.filterBlocksUnauthenticated` produces on `/admin/dashboard`. Both tests
  live in `chappe-tests`, same surefire fork, no `<parallel>` configured → tests run
  sequentially. So the 401 must travel **across the `Server.stop()` / `Server.start()`
  boundary** within the same JVM fork.
- `ChappeServer.stop()` calls `executor.shutdownNow()` then `awaitTermination(graceMs)`.
  `shutdownNow()` only **interrupts** the running virtual threads — a VT blocked in
  `channel.write()` (response body) is *not* cancelled by interrupt; it finishes its
  write loop. If the grace period expires before the VT lands, the next test starts
  a new Server and the OS may recycle the freed port immediately (especially under
  `SO_REUSEADDR`). The leftover VT then writes its `401` bytes onto a socket that
  the *new* Server now serves to a *different* test's client.

### Fix v1 applied (partial — knocks down rate from ~60-70% to ~20-40%)
`ChappeServer` now:
1. Tracks every accepted `SocketChannel` in a `ConcurrentHashMap`-backed set
   (`activeConnections`).
2. `acceptLoop()` does the registration *immediately* after `accept()` and re-checks
   `running.get()` (plus catches `RejectedExecutionException`) to close any client
   that races against `stop()`.
3. `handleConnection()` de-registers in `finally` (regardless of how it exits).
4. `stop()` now force-closes every still-live `SocketChannel` *after* closing the
   `ServerSocketChannel` and joining the accept thread, but *before*
   `executor.shutdownNow()`. Closing the channel turns any in-flight `channel.write()`
   into `ClosedChannelException` (the only thing that actually unblocks a write —
   `Thread.interrupt()` does not).

### Remaining residual flake (~20-30% rate, different shape)
After the fix two new failure patterns appear:
1. `WebSocketRfc6455Test.serverDoesNotAcceptUnmaskedClientFrame` → handshake gets
   `HTTP/1.1 404 Not Found` (still cross-Server, but harder to trigger).
2. `ExtensionSpiTest.requestAttributes` → expected `user=admin`, got `` (empty body).
   This one is *intra*-Server: a single Server using keep-alive seems to leak
   `Request.attribute()` / `RequestContext` state between successive requests on
   the same connection (`Request#attribute` map not cleared between requests on a
   keep-alive socket, *or* `RequestContext.CURRENT` ScopedValue not re-bound).

### Next step (dedicated session)
1. Investigate `HttpConnection` (HTTP/1.1) keep-alive loop: confirm that
   `Request#attribute()` map and any `ScopedValue` carrier are reset on every
   new request, not reused across pipelined / keep-alive iterations.
2. Consider disabling `SO_REUSEPORT` on test profiles (`bindWithRetry()` calls
   `setReusePort`) — that option allows two `Server` instances to receive
   connections on the same port concurrently and is unsafe inside a single JVM
   where multiple servers come and go.
3. Add a `Connection: close` short-circuit when `stop()` is called: walk the
   live HTTP/1.1 connections and refuse further requests so keep-alive can't
   sneak in a late one.

### Next step
1. Reproduce in a microbenchmark: start/stop 100 servers in a row with concurrent
   requests in flight; observe whether late bytes appear on the next server's port.
2. In `ChappeServer.stop()`, wait for **all** in-flight `channel.write()` to drain
   before closing the serverChannel (block on a per-connection `latch`), or
   explicitly close all client `SocketChannel` instances first to fail their writes.
3. Verify `SO_REUSEADDR` setting on the `ServerSocketChannel` — if set, TIME_WAIT
   sockets can be reassigned immediately to a different `Server` instance in the
   same JVM.
4. Alternative quick win: add a brief sleep (50 ms) in `Server.stop()` between
   serverChannel close and executor termination to let pending writes flush.
5. Capture server-side bytes on the 400 path (`io.vidocq.chappe.http.Http11Parser`)
   to confirm the parser sees recycled bytes rather than a malformed real request.

---

## CHAPPE-003 — WebSocket handler exceptions were swallowed silently (no log)
- **Date**: 2026-06-01 — **Status**: FIXED
- **Severity**: medium (observability — turned any handler bug into a silent, hard-to-diagnose failure)
- **Surfaced by**: Arago LAB seat locking (cf. Arago `ARAGO-007`) — a repository call threw inside
  `onText`; the socket closed but nothing was logged, so the cause was invisible.

### Symptom
When a `WebSocketHandler` callback (`onOpen`/`onText`/`onBinary`) threw, `WebSocketConnection` closed
the connection (1011) but the throwable vanished: `safeError` only delegated to `handler.onError`,
whose default implementation is an empty no-op. A handler that does not override `onError` (the common
case) lost the exception entirely — no stack trace, no log.

### Cause
`WebSocketConnection.safeError(Throwable)` called `handler.onError(this, t)` and nothing else. The
`WebSocketHandler.onError` default is `{}`, so the framework relied on the application to surface its
own errors.

### Fix
`safeError` now logs through a `System.Logger` (JDK, zero-dep) before delegating: routine I/O drops and
client protocol violations (`IOException`, `WebSocketProtocolException`) at DEBUG, any other throwable
(a handler-side bug) at WARNING. The close behaviour is unchanged. Regression:
`WebSocketEchoTest.handlerErrorIsLoggedNotSwallowed` (captures `System.Logger` output deterministically
via a test `System.LoggerFinder`). Full reactor green incl. 53 RFC 6455 conformance tests.

## CHAPPE-002 — WebSocket routes did not expose route `{pathParams}` to the handshake
- **Date**: 2026-06-01 — **Status**: FIXED
- **Severity**: medium (any WS endpoint with a `{var}` in its pattern, e.g. `/ws/rooms/{pin}`)
- **Surfaced by**: Arago Phase 1 (room chat WebSocket) — `handshake.pathParams().get("pin")` was null.

### Symptom
`Router.webSocket("/ws/rooms/{pin}", handler)` matched the upgrade (the connection opened), but
`onOpen(ws, handshake)` received a `Request` whose `pathParams()` was empty — the `{pin}` capture
was lost. HTTP routes populate `pathParams()`; WS routes did not.

### Cause
`DefaultRouterBuilder.webSocket(...)` returns a `WebSocketUpgrade` from the route lambda. The lambda
received the param-wrapped `Request` (route matching wraps it via `withPathParams`), but
`WebSocketUpgrade` carried only the handler. `HttpConnection` then handed the **raw** request (no
captures) to `WebSocketConnection` → `onOpen`.

### Fix
`WebSocketUpgrade` now carries the matched handshake `Request`; the `webSocket` lambda passes the
param-wrapped `request` into it; `HttpConnection` prefers `upgrade.handshakeRequest()` over the raw
request when invoking the WS connection. Regression: `WebSocketEchoTest.pathParamsReachHandshake`.

## CHAPPE-001 — Truncated Responses on Large Files (SO_SNDBUF Saturated)

- **Date**: 2026-05-09
- **Status**: FIXED (commit in progress)
- **Severity**: critical (silent response corruption)

### Symptom

On `https://staging-doc.vidocq.dev/chappe-fr/index.html` (Antora behind openresty
which `proxy_pass`es to Chappe over cleartext HTTP/1.1), images load only
partially and the browser hangs waiting for the rest — the server has stopped
sending but advertised a full `Content-Length`, so the client waits
indefinitely.

### Minimal repro

```java
// Tests/LargeStaticFileTest.java
// 1. Serves an 8 MiB file through StaticFileHandler
// 2. Slow client-side drain (4 KiB chunks, 2 ms sleep)
// 3. Client SO_RCVBUF = 16 KiB to quickly saturate the server SO_SNDBUF
// → receives 319,020 / 8,388,608 bytes, then the server stops sending
```

On macOS Sonoma + Java 25, reproduction is systematic in ~250 ms.

### Cause

`HttpResponseWriter.writeBody()` line 261 (before patch):

```java
while (remaining > 0) {
    long transferred = fc.transferTo(position, remaining, channel);
    if (transferred <= 0) break;        // ← BUG
    position += transferred;
    remaining -= transferred;
}
```

`FileChannel.transferTo(SocketChannel)` can return **0** on a blocking
`SocketChannel` when the kernel `SO_SNDBUF` is saturated: the underlying
`sendfile(2)` handles this case with EAGAIN-like behavior on some platforms
(documented on macOS, Linux on some versions — see JDK-8264762, JDK-8230846).

The `break` on `transferred <= 0` confuses this transient return value with a
real EOF (`< 0`) and silently truncates the response.

### Fix

Distinguish EOF (`< 0`) from retry (`== 0`):

```java
if (transferred < 0) break;        // real EOF
if (transferred == 0) {
    Thread.yield();                // SO_SNDBUF saturated, retry
    continue;
}
position += transferred;
remaining -= transferred;
```

### Regression coverage

`chappe-tests/.../LargeStaticFileTest.java` — slow-drain 8 MiB test, verifies
that `Content-Length` is fully consumed and SHA-256 matches byte-for-byte.

### Related notes (to investigate separately)

- `Http2Connection.waitForSendWindow` — possible race: multiple concurrent
  streams read `connectionSendWindow.get()` in parallel and may exceed the
  advertised window. Not reproduced here (H2 path inactive on staging behind
  openresty), but should be fixed on the HTTP/2 side.

### Production validation (post-fix)

After deploying `8d670fb` (fix + X-Chappe-Build observability) on
staging-doc.vidocq.dev behind oauth2-proxy + NPM:

- 624 cumulative requests across the 7 PNG logos (877 KB → 2.4 MB)
- Profiles: 84-way parallel burst × 5 cycles, 200-random burst, slow drain
  `--limit-rate 200K`
- **0 failures, 0 timeouts, 0 truncations** (vs 9/21 = 43% failures pre-fix)

A pre-fix variant that was observed (timeout at ~96% of the body, suspected as
"2nd bug") was actually the same `transferTo==0` bug manifesting under a
different profile (late SO_SNDBUF saturation) — resolved by the same commit
`ef864e8`.
