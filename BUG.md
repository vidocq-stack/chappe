# Chappe — Bug Registry

Format: one bug per dated section, with a short id, symptom, minimal repro,
cause hypothesis, and status.

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
  unbounded write could have held the virtual thread and the socket forever.
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
