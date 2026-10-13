# Connection and Stream Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose, on `io.vidocq.chappe.api.Request`:
- a connection identity that is unique for the JVM lifetime;
- the HTTP/2 stream id.

Foy needs both to implement Servlet 6.1 `ServletConnection.getConnectionId()` and `ServletRequest.getProtocolRequestId()` (foy BUG-20261009-09).

**Architecture:**
- `ConnectionInfo`, the record built once per accepted connection in `ConnectionInfo.of(...)`, gets a `long id` taken from a JVM-wide `AtomicLong`.
- `HttpRequestImpl.initConnectionInfo` copies the id.
- `Http2Connection` sets the stream id on the stream's request.
- `Request` gains two default methods. `ForwardingRequest` forwards them.

**Tech Stack:** Java 25, Java Modules, JUnit 5, `java.net.http.HttpClient` for the HTTP/2 tests.

**Spec:**
- foy BUG-20261009-09, quoted in the Rulings.
- The Servlet 6.1 Javadoc. `ServletConnection.getConnectionId()` says: "unique (within the lifetime of the JVM) identifier … no defined format". `ServletRequest.getProtocolRequestId()` says: HTTP/1.x `""`; HTTP/2 "the stream identifier".

## Global Constraints

- **Language:** English for code, Javadoc, comments, commits and Markdown. Write "Java Modules", never "JPMS".
- **Dependencies:** zero runtime dependencies. Use `ReentrantLock` rather than `synchronized` if a lock is ever needed; an `AtomicLong` needs none.
- **TDD:** write the failing test first, run it, and see it fail.
- **Commits:**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - Trailers: `Co-Authored-By: Claude <model> 5.5 <noreply@anthropic.com>`, then `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **BUG.md:** chappe uses `CHAPPE-NNN` ids. The next free id is `CHAPPE-035`; check the file to confirm it.
- **Worktree:** work in `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/wt-chappe-connid`, on branch `pr/ybl/chappe-connection-ids`. Never use `/Users/yblazart/projects/perso/vidocq/chappe`.
- **Maven:** run it offline with the private repository: `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`.

## Rulings (made while planning; the maintainer approves them with the plan)

- **API shape**, following the existing `Optional<TlsInfo> tls()`:
  - `default OptionalLong connectionId() { return OptionalLong.empty(); }` returns the transport connection's JVM-unique id: a positive counter, assigned at accept, and shared by every request of that connection (HTTP/1.1 keep-alive, HTTP/2 streams).
  - `default OptionalInt streamId() { return OptionalInt.empty(); }` returns the HTTP/2 stream id. It is empty for HTTP/1.x.
  - The names are transport-level, not Servlet-level. Foy maps them to the Servlet strings.
- **No `protocolConnectionId()`.** Chappe has no HTTP/3 or QUIC, and Servlet defines the protocol connection id as `""` for HTTP/1.x and HTTP/2. YAGNI: it gets added along with QUIC.
- **Where the counter lives.** `ConnectionInfo` gains a leading `long id` component. `ConnectionInfo.of(...)` assigns `NEXT_ID.incrementAndGet()`. Every accept path already goes through `of(...)`: TLS (`ChappeServer` ~:434), h2c prior knowledge (`Http2Connection` ~:143), and HTTP/1.1 (`ChappeServer` ~:502-510 and the `HttpConnection` convenience constructors at ~:72/76). The canonical constructor stays public, because the record is public. Callers that build one directly, as tests do, pass an id of their choosing.

## Review Focus

1. **Keep-alive.** Two HTTP/1.1 requests on one keep-alive connection report the same `connectionId()`. Two connections report different ids, even when the client reuses the same local port.
2. **HTTP/2.** Every stream of one connection reports the same `connectionId()` and its own odd `streamId()` (1, 3, 5, …). The stream id is set before the handler runs.
3. **Forwarding.** `ForwardingRequest` forwards both methods. A mounted handler (`Router.mount`) sees them.
4. **The reused `HttpRequestImpl`.** HTTP/1.1 reuses one `HttpRequestImpl` across keep-alive requests. `reset()` keeps the connection id, and `streamId` stays empty.

---

### Task 1: The connection id and the stream id (CHAPPE-035)

**Files:**
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/Request.java`. Add the two default methods with Javadoc, after `tls()` (~:150-162).
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ForwardingRequest.java`, next to `tls()` (~:153).
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/ConnectionInfo.java`. Add the `id` component and the counter.
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpRequestImpl.java`. Change `initConnectionInfo` (~:116-125), and add `initStreamId(int)` plus the two overrides.
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java`, at the stream creation (~:342-349): call `stream.request().initStreamId(streamId)` next to `initConnectionInfo`.
- Modify: every direct `new ConnectionInfo(...)` call, main and test (grep).
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionInfoTest.java`
- Modify: `BUG.md` (CHAPPE-035), `docs/en/modules/ROOT/pages/reference.adoc` or whichever page documents `Request.tls()` (grep `tls()` in `docs/en`), and `docs/en/modules/ROOT/pages/migration.adoc` (the new `ConnectionInfo` component).

- [ ] **Step 1: Write the failing tests** in `ConnectionInfoTest`, using its existing helpers (`startTls`, `tlsClient`, the raw-socket h2c test).
  - `keepAliveRequestsShareTheConnectionId`: one `HttpClient` (HTTP/1.1, keep-alive) sends 3 requests. The handler echoes `req.connectionId()`. All 3 bodies are equal and positive.
  - `separateConnectionsGetDistinctIds`: two requests, each sent with `Connection: close`, or with two clients. The two ids differ.
  - `http2StreamsShareTheConnectionIdAndCarryTheirStreamId`: TLS with an HTTP/2 client, and 3 sequential requests on one client. The connection ids are equal, and the stream ids are 3 distinct odd positive numbers. Over HTTP/1.1, `streamId()` is empty.
  - `aMountedHandlerSeesTheIds`: a `Router.mount` handler sees the same values, which exercises `ForwardingRequest`.
- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl chappe-tests -am test -Dtest=ConnectionInfoTest -Dsurefire.failIfNoSpecifiedTests=false`. The tests fail to compile, or the methods return empty.
- [ ] **Step 3: Implement** the changes listed under Files. The `ConnectionInfo.of` counter:

```java
private static final AtomicLong NEXT_ID = new AtomicLong();
public static ConnectionInfo of(SocketChannel channel, TlsInfo tls) {
    return new ConnectionInfo(NEXT_ID.incrementAndGet(), remote(channel), local(channel), tls);
}
```

  In `HttpRequestImpl`, store `connectionId` from `info.id()`; `connectionId()` returns `OptionalLong.of(connectionId)` once the info is set, else empty. Store `streamId` as an int, with 0 meaning unset: `streamId()` returns `OptionalInt.of(streamId)` when it is greater than 0. `reset()` must keep the connection id and the stream id, since a stream's request is never reused.
- [ ] **Step 4: Run the tests to verify they pass.** Run `ConnectionInfoTest`, then the full build `./mvnw ... install` from the worktree root.
- [ ] **Step 5: Update the docs.** Document `connectionId()` and `streamId()` next to `tls()`. Add a migration note: `ConnectionInfo`, a public record in `chappe-http`, gains a leading `id` component. Add BUG.md CHAPPE-035, "Request exposes no connection identity nor the HTTP/2 stream id (needed by foy BUG-20261009-09)", as FIXED with the hash. Record it in a follow-up commit.
- [ ] **Step 6: Commit** `feat(api): expose the connection id and the HTTP/2 stream id on Request (CHAPPE-035)`, then the docs and BUG.md commit.
