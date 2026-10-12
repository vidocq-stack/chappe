# Connection Timeouts and Port-0 Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three places where a chappe connection or start-up can block or race:
1. **The TLS handshake has no deadline.** It becomes bounded by `idleTimeout` (CHAPPE-028).
2. **HTTP/2 writes ignore `writeTimeout`.** They become bounded (CHAPPE-029).
3. **`port(0)` probes a free port, closes the probe, then rebinds.** It now binds directly on a specific address (CHAPPE-030).

**Architecture:**
- **TLS handshake.** `SslHandler.doHandshake(Duration)` arms one total deadline for the whole handshake. A watchdog virtual thread closes the socket at the deadline, and a CAS decides who wins, as in `TimedReadChannel`. The deadline is disarmed before the existing rejected-handshake path (alert plus lingering close), which keeps its own budgets.
- **HTTP/2 writes.** `Http2FrameWriter.flushBuffer` writes through `BoundedWrites.writeWithTimeout`, which gives HTTP/2 the same bound HTTP/1.1 already has.
- **Port 0.** `ChappeServer` binds port 0 directly when the host is not a wildcard address. The wildcard keeps the loopback probe, for CHAPPE-004 #3.

**Tech Stack:** Java 25, zero dependencies, JUnit 5 in `chappe-tests`.

**Spec:** the maintainer decisions below. The research is in the controller scratchpad (`chappe-timeouts-research.md`), with file:line references. Read its A, B and C sections for your task.

## Global Constraints

- **Language and dependencies.** English everywhere. Say "Java Modules", never the abbreviation. Zero runtime dependencies. Use virtual threads and `ReentrantLock`, never `synchronized` around I/O.
- **TDD.** Write the failing test first. Record RED and GREEN with the number of tests run (a 0-test run counts as neither).
- **Decision A.** The handshake deadline is `ServerConfig.idleTimeout()`: a **total** deadline for the handshake, not a per-read one. There is no new `ServerConfig` component and no API change. A separate `handshakeTimeout` is a documented follow-up.
- **Decision B.** Bound the flush with `BoundedWrites` at `Http2FrameWriter.flushBuffer`. A per-connection write watchdog (`TimedWriteChannel`) is a follow-up. `waitForSendWindow` (flow-control stall) is NOT bounded here; it is recorded as an open follow-up.
- **Decision C.** Bind port 0 directly only when `!InetAddress.getByName(host).isAnyLocalAddress()`. Keep the probe and the 5-attempt retry for wildcard hosts. `ServerConfig.port` stays 0, and `Server.port()` reports the bound port.
- **Scope.** The HTTP/2 header-list limit and 431 (item D of the research) are out of scope: they get a separate PR.
- **Bug ids.** CHAPPE-028 (A), CHAPPE-029 (B), CHAPPE-030 (C), each as a new entry marked FIXED at the top of `BUG.md`. Rewrite the old follow-ups to "(FIXED in CHAPPE-0NN)", following the CHAPPE-022 precedent.
- **Commits.**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - The last paragraph is `Co-Authored-By: Claude <model> <noreply@anthropic.com>` followed by `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **Build.**
  - Run `./mvnw -ntp …` from the chappe root with `-Dmaven.repo.local=<scratchpad>/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository`.
  - Pass tests as `-Dtest='A,B'`, with `-Dsurefire.failIfNoSpecifiedTests=false` when using `-am`.
  - Spotless must pass.
- **Test timing.** No fixed sleeps as assertions. Every wait is bounded, and timing assertions use generous slack, so they hold on CI runners (see CHAPPE-006).

## Review Focus

1. **A handshake that completes just as the deadline fires.** The CAS lets exactly one side win. A completed handshake is never handed back on a closed socket, and a fired deadline always surfaces as `SocketTimeoutException` (Task 1, `aHandshakeThatCompletesIsNotCut`, plus code review of the CAS).
2. **A rejected handshake (CHAPPE-027).** The client must still get its alert, followed by the 2 s / 1 MiB lingering drain. The handshake deadline must not cut them short (Task 1: the existing `TlsHandshakeHardeningTest` stays green, plus `plaintextOnTheTlsPortStillEndsWithinTheLingerBudget`).
3. **A drip-feeding client.** One byte every 100 ms must still be cut at the total deadline (Task 1, `aDripFeedingClientIsStillCutAtTheDeadline`).
4. **An HTTP/2 peer that reads slowly but steadily**, or a quiet connection with nothing to write. Neither must be cut (Task 2, `aSlowButProgressingReaderIsNotCut`, `aQuietConnectionIsNotCut`).
5. **`port(0)` on the default wildcard host.** Behaviour must be unchanged, with the probe kept. `config().port()` stays 0 after `start()` (Task 3).

---

### Task 1: Bound the TLS handshake (CHAPPE-028)

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java`
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java` (`handleTlsConnection`)
- Create: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsHandshakeTimeoutTest.java`
- Modify: `BUG.md`; `docs/en/modules/ROOT/pages/{usage,reference,whats-new,migration}.adoc`

**Interfaces:**
- Produces `public void doHandshake(Duration timeout) throws IOException` on `SslHandler`. A null, zero or negative timeout means no bound. The existing `doHandshake()` delegates with `null`, so no bound, unchanged.
- At the deadline, the method throws `SocketTimeoutException("TLS handshake timed out after <ms> ms")`.

- [ ] **Step 1: Write the failing tests** in `TlsHandshakeTimeoutTest`.
  - Use `TlsFixtures` (`selfSigned`, `context`) and `Server.builder().host("127.0.0.1").port(0).tls(ctx).idleTimeout(Duration.ofMillis(500)).writeTimeout(...)`, in the same style as `KeepAliveIdleTimeoutTest`.
  - The assertion pattern is the existing one: `socket.setSoTimeout(5000)`, `in.read() == -1`, and elapsed time within bounds. The close must be the server's, not the client's `SO_TIMEOUT`.
  - Tests:
    1. `aClientThatNeverSendsAClientHelloIsClosed`: connect and send nothing. Expect EOF, with elapsed in [400 ms, 4500 ms].
    2. `aPartialClientHelloIsClosed`: build a real ClientHello with a client-mode `SSLEngine` (`beginHandshake()`, `wrap(EMPTY, buf)`) and write only the first 15 bytes. Expect EOF within the bound.
    3. `aDripFeedingClientIsStillCutAtTheDeadline`: write that ClientHello one byte every 100 ms from a virtual thread, and stop when a write fails. Expect EOF before the full ClientHello is sent, and within `idleTimeout` + 4 s. A per-read timer of 500 ms would never fire here.
    4. `aHandshakeThatCompletesIsNotCut`: run a normal `SSLSocket` handshake and send `GET /` (200). Then send a second keep-alive request at about 0.6 × timeout and a third at about 1.2 × timeout after connect. Both answer 200, which proves the handshake watchdog was disarmed. Use the server's existing keep-alive behaviour.
    5. `plaintextOnTheTlsPortStillEndsWithinTheLingerBudget`: write `GET / HTTP/1.1\r\n\r\n` in clear. Expect EOF within 2 s + slack. This covers the CHAPPE-027 path.
  - Run the five tests, plus `TlsHandshakeHardeningTest,TlsLingeringCloseTest,TlsTest`.

- [ ] **Step 2: Run and check RED.** Run `./mvnw -ntp -pl chappe-tests -am test -Dtest='TlsHandshakeTimeoutTest' -Dsurefire.failIfNoSpecifiedTests=false <repo flags>`. Expected: tests 1–3 fail, because the read returns no EOF within 5 s and throws `SocketTimeoutException` on the client; tests 4–5 pass.

- [ ] **Step 3: Implement** in `SslHandler`. Place the code by its content: the handshake loop is `runHandshake()`, called by `doHandshake()`.

```java
    /**
     * Runs the TLS handshake within {@code timeout} in total ({@code null}, zero or negative: no
     * bound). The deadline covers the whole handshake, so a client that sends nothing, half a
     * ClientHello, or one byte at a time is cut when it expires: a watchdog closes the socket and
     * this method throws {@link SocketTimeoutException}. A handshake the engine rejects still ends
     * with its alert and the bounded lingering close (CHAPPE-027), outside this deadline.
     */
    public void doHandshake(Duration timeout) throws IOException {
        HandshakeDeadline deadline = HandshakeDeadline.arm(timeout, channel);
        try {
            try {
                runHandshake();
            } finally {
                deadline.disarm();          // before the rejected-handshake path below
            }
        } catch (SSLException e) {
            if (deadline.fired()) throw deadline.timedOut(e);
            // existing rejected-handshake handling, unchanged: sendPendingAlert, then the lingering close, then rethrow
        } catch (IOException e) {
            if (deadline.fired()) throw deadline.timedOut(e);
            throw e;
        }
        if (deadline.fired()) throw deadline.timedOut(null);   // handshake ended but the socket was closed
        // existing post-handshake code (tlsInfo, ALPN) unchanged
    }
```

  - Adapt this skeleton to the real shape of `doHandshake()`: keep its existing catch body verbatim.
  - `HandshakeDeadline` is a private static nested class:
    - `AtomicInteger state` holds `ARMED`, `DISARMED` or `FIRED`.
    - `arm(Duration, Closeable)` returns a no-op instance for a null, zero or negative timeout. Otherwise it starts one virtual thread, `Thread.ofVirtual().name("chappe-handshake-watchdog")`, which parks with `LockSupport.parkNanos` until the deadline, then does `if (state.compareAndSet(ARMED, FIRED)) closeQuietly(closeable)`.
    - `disarm()` does `state.compareAndSet(ARMED, DISARMED)` and `LockSupport.unpark(watchdog)`.
    - `fired()` returns `state.get() == FIRED`.
    - `timedOut(cause)` builds the `SocketTimeoutException` with that cause.
    - The watchdog loop re-checks `state` after every wake-up and never sleeps past the deadline. Mirror `TimedReadChannel.watch()`.
  - In `ChappeServer.handleTlsConnection`, call `sslHandler.doHandshake(config.idleTimeout())` instead of `doHandshake()`.
  - The existing `catch (IOException)` in `handleConnection` already logs at DEBUG and closes. The new `SocketTimeoutException` message makes that log line explicit.
  - Out of scope, recorded as open follow-ups in CHAPPE-028:
    - the post-handshake TLS pre-read (`ChappeServer` around `sslHandler.read(readBuffer)`);
    - the h2 preface read (`Http2Connection.validateClientPreface`).

- [ ] **Step 4: Run and check GREEN.** Run `TlsHandshakeTimeoutTest,TlsHandshakeHardeningTest,TlsLingeringCloseTest,TlsTest,TlsClientAuthTest,TlsInfoTest,TlsCertificateChainTest,ConnectionUpgradeTlsTest`, three times in a row. Report the counts.

- [ ] **Step 5: Docs and BUG.md.**
  - **`BUG.md`:**
    - Add a new top entry, `CHAPPE-028`: date 2026-10-12, symptom, minimal repro (`TlsHandshakeTimeoutTest`), cause, and status FIXED with the commit subject.
    - List the remaining open follow-ups:
      - the TLS pre-read;
      - the h2 preface read;
      - a dedicated `handshakeTimeout` setting.
    - Cross-reference CHAPPE-005.
    - In CHAPPE-027, rewrite the "Follow-up (OPEN…)" paragraph to "(FIXED in CHAPPE-028)".
  - **`usage.adoc`:** replace the sentence that says the handshake has no timeout with the new rule.
  - **`reference.adoc`:** in the `idleTimeout` bullet, say that it also bounds the whole TLS handshake, as a total deadline.
  - **`whats-new.adoc`:** one bullet.
  - **`migration.adoc`:** a behaviour note: a TLS client that takes longer than `idleTimeout` (60 s by default) to finish its handshake is now disconnected.

- [ ] **Step 6: Commit.** `fix(tls): bound the TLS handshake by the idle timeout (CHAPPE-028)`.

---

### Task 2: `writeTimeout` for HTTP/2 writes (CHAPPE-029)

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2FrameWriter.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` (where the frame writer is built)
- Create: `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2WriteTimeoutTest.java`
- Modify: `BUG.md`; `docs/en/modules/ROOT/pages/{reference,whats-new,migration}.adoc`; the `writeTimeout` Javadoc in `chappe-api/.../ServerConfig.java`

**Interfaces:**
- Produces `public Http2FrameWriter(ByteBuffer writeBuffer, WritableByteChannel channel, int maxFrameSize, Duration writeTimeout, Closeable closeable)`.
  - Check the existing constructors for the exact parameter list, and append `writeTimeout` and `closeable` to the longest one.
  - The existing constructors stay and delegate with `null`, meaning no bound.
- Consumes `BoundedWrites.writeWithTimeout(WritableByteChannel, Closeable, ByteBuffer, Duration)` (existing). Check its exact signature and its behaviour for a `null` timeout.

- [ ] **Step 1: Write the failing tests** in `Http2WriteTimeoutTest`.
  - Model it on `WriteTimeoutTest`, its HTTP/1.1 twin, and use `H2TestConnection` over h2c prior knowledge.
  - The server answers `/big` with a 32 MiB body. Set `writeTimeout(Duration.ofMillis(400))` and `idleTimeout(Duration.ofSeconds(30))`.
  - **Open the flow-control windows first.** Without this the server stalls in `waitForSendWindow` at 64 KiB, not in the socket write. So the client sends:
    - the preface;
    - a SETTINGS with `INITIAL_WINDOW_SIZE = 2^31 − 1`;
    - a connection `WINDOW_UPDATE` of `2^31 − 1 − 65535` on stream 0;
    - then the GET HEADERS for `/big`.
  - Tests:
    1. `aPeerThatStopsReadingIsCut`:
       - Set the client socket's `setReceiveBufferSize(2048)` before connect. Read nothing for 3 s, then read to EOF with `setSoTimeout(10_000)`.
       - Assert that EOF arrives rather than a `SocketTimeoutException`, and that fewer DATA bytes arrive than 32 MiB.
       - Afterwards, a new connection to the same server gets a normal small response from `/small`.
    2. `aPeerThatStopsReadingIsCutOverTls`: the same over TLS with ALPN h2, using `TlsFixtures`. Use the repo's existing h2-over-TLS test helper if there is one; otherwise drive h2 frames through an `SSLSocket`, the way `H2TestConnection` drives a plain socket. If no such helper exists and writing one exceeds about 80 lines, record that in the report and keep the h2c test only.
    3. `aSlowButProgressingReaderIsNotCut`: read 64 KiB every 100 ms until the 32 MiB arrive. The response completes, with END_STREAM seen. Use a 4 MiB body here to keep the test short.
    4. `aQuietConnectionIsNotCut`: complete one small request, then wait 3 × `writeTimeout` with nothing to write. The connection is still open, and a second request on it succeeds.

- [ ] **Step 2: Run and check RED.** Run `-Dtest='Http2WriteTimeoutTest'`. Expected: test 1 (and 2) fails, because no EOF comes within 10 s, or the server thread stays blocked. Tests 3 and 4 pass.

- [ ] **Step 3: Implement.**
  - Add `writeTimeout` and `closeable` fields to `Http2FrameWriter`.
  - In `flushBuffer()`, replace `while (writeBuffer.hasRemaining()) channel.write(writeBuffer);` with:

```java
        while (writeBuffer.hasRemaining()) {
            BoundedWrites.writeWithTimeout(channel, closeable, writeBuffer, writeTimeout);
        }
```

  - Keep the loop only if `writeWithTimeout` can return with bytes remaining; check it.
  - `writeWithTimeout` lives in `io.vidocq.chappe.http`, while the writer is in `io.vidocq.chappe.http.h2`. If it is package-private, make it public. The package is exported only to chappe modules; check `module-info`.
  - In `Http2Connection`, build the writer with `config.writeTimeout()`, and with `writeChannel instanceof Closeable c ? c : readChannel`, or whatever closes the transport: the `SocketChannel` for h2c, the `SslHandler` for TLS.
  - A timed-out flush closes the transport, and every other writer then fails fast. The connection's existing close path cancels the streams; add no new error path.

- [ ] **Step 4: Run and check GREEN.**
  - Run `Http2WriteTimeoutTest` 3 times.
  - Run `Http2Test,Http2RequestFlowControlTest,WriteTimeoutTest`, and any `Http2Grpc*Test` and `GrpcWeb*Test` classes. Use `ls chappe-tests/src/test/java/io/vidocq/chappe/tests/ | grep -i -e http2 -e grpc` to list them.
  - Report the counts.

- [ ] **Step 5: Docs and BUG.md.**
  - **New `CHAPPE-029` entry:** status FIXED. Add an open follow-up: `waitForSendWindow` has no bound, so a peer that never sends WINDOW_UPDATE parks the response thread. Also note that `TimedWriteChannel`, a per-connection write watchdog, would remove the one-thread-per-flush cost (see CHAPPE-022(a)).
  - **CHAPPE-024:** rewrite follow-up (a) to "(FIXED in CHAPPE-029)".
  - **`reference.adoc`:** the `writeTimeout` bullet now covers HTTP/2. It bounds each flushed frame buffer of up to 16 KiB, and on expiry the whole multiplexed connection is closed.
  - **`ServerConfig` Javadoc:** the same rule.
  - **`migration.adoc`:** a behaviour note for HTTP/2: a stalled peer is now cut after `writeTimeout`, and all its streams with it.
  - **`whats-new.adoc`:** one bullet.

- [ ] **Step 6: Commit.** `fix(http): apply writeTimeout to HTTP/2 writes (CHAPPE-029)`.

---

### Task 3: Bind port 0 directly on a specific address (CHAPPE-030)

**Files:**
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java` (`bindWithRetry`, `resolveBindPort`, their Javadoc)
- Modify: `chappe-tests/src/test/java/io/vidocq/chappe/tests/ServerLifecycleTest.java`
- Modify: `BUG.md`; the `port` Javadoc in `chappe-api/.../ServerConfig.java`

- [ ] **Step 1: Write the tests** in `ServerLifecycleTest`.
  - `portZeroReportsTheBoundPortAndKeepsTheConfig`: with `host("127.0.0.1").port(0)`, `port()` is 0 before `start()` and `> 0` after. `config().port()` stays 0, and `localAddress().getPort() == port()`. After `stop()` then a fresh server, a new port is bound.
  - `manyPortZeroServersStartConcurrentlyWithoutError`:
    - 8 threads each start and stop 25 servers with `host("127.0.0.1").port(0)`.
    - Assert that no exception is thrown, that the ports of servers alive at the same time are distinct, and that each server answers one GET.
    - Keep it under about 10 s.
  - Show RED honestly: the race cannot be forced, so the stress test may pass before the fix. Write that down in the report; the property tests pin the contract.
  - To pin the direct bind, also add `aSpecificHostBindsPortZeroWithoutAProbe`. It is a seam test, and it is only possible if the decision method is reachable. If it is not, skip it: chappe-core has no test directory, and the plan does not create one.

- [ ] **Step 2: Implement.**
  - When `config.port() == 0` and the host is not a wildcard address, bind `new InetSocketAddress(config.host(), 0)` directly, inside the existing retry loop. Wildcard hosts are those for which `InetAddress.getByName(host).isAnyLocalAddress()` is true; resolve the host once, outside the loop.
  - The wildcard keeps `resolveBindPort()`'s loopback probe. Update the Javadoc: it must explain both branches and cite CHAPPE-004 #3, so that nobody "simplifies" to a wildcard bind on port 0.

- [ ] **Step 3: Run and check GREEN.** Run `ServerLifecycleTest` 3 times, then the whole build, `./mvnw -ntp install`. Report the totals.

- [ ] **Step 4: Update BUG.md and the Javadoc.**
  - New `CHAPPE-030` entry, status FIXED for non-wildcard hosts. For the wildcard host, say that the probe-then-bind window stays open, repaired by the retry. Cross-reference CHAPPE-004.
  - The `ServerConfig.port` Javadoc describes port 0.

- [ ] **Step 5: Commit.** `fix(core): bind port 0 directly on a specific address (CHAPPE-030)`.

---

## Self-review notes (plan author)

- **Coverage.**
  - Decision A: Task 1, using the total `idleTimeout` deadline.
  - Decision B: Task 2, using `BoundedWrites` at the flush.
  - Decision C: Task 3, non-wildcard only.
  - D is out of scope.
- **Interference.** Tasks 1 and 2 both touch the TLS write path:
  - Task 1 only touches the handshake.
  - Task 2's TLS case bounds `SslHandler.write` from outside, through `BoundedWrites`. The closeable is the `SslHandler`, whose `close()` waits at most 100 ms for its write lock.
- **Risk.** On h2, the blast radius of a write stall is the whole connection. This is documented in migration.
