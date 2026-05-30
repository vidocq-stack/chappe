# Chappe — Bug Registry

Format: one bug per dated section, with a short id, symptom, minimal repro,
cause hypothesis, and status.

---

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
