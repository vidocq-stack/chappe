# Chappe — Bug Registry

Format: one bug per dated section, with a short id, symptom, minimal repro,
cause hypothesis, and status.

---

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
