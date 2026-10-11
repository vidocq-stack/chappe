# Chappe PR 2 — TLS Connection Info and Client Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Plan date: 2026-10-11.

**Goal:** Handlers see correct connection metadata over TLS and HTTP/2 (`isSecure`, `scheme`, remote/local addresses), can read the TLS session through `Request.tls()`, and a listener can request (`WANT`) or require (`NEED`) a client certificate through `Server.Builder.tls(SSLContext, ClientAuth)`.

**Architecture:** A new public record `TlsInfo` (chappe-api) describes the TLS session. `SslHandler` builds it once, at the end of the handshake. `ChappeServer` packs the socket addresses and the `TlsInfo` into an internal `ConnectionInfo` record (chappe-http) and passes it to `HttpConnection` and `Http2Connection`. They copy it into each `HttpRequestImpl`; on HTTP/2 that means every stream. This replaces the `closeable instanceof SocketChannel` shortcut, which is the cause of the bug. `ClientAuth` is a new `ServerConfig` component that `ChappeServer.handleTlsConnection` applies to the `SSLParameters` of each engine, so one mode per listener. `SslHandler` refuses TLS 1.2 client-initiated renegotiation (so `TlsInfo` cannot go stale), and on a failed handshake it sends the pending TLS alert before the connection closes.

**Tech Stack:** Java 25 (Temurin), Maven 3.9.16 (`./mvnw`), JUnit Jupiter (JUnit 6), JDK JSSE (`SSLEngine`, `SSLSession`), JDK `java.net.http.HttpClient` for test clients, the JDK `keytool` for test certificates. No new dependency.

**Spec:** `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/chappe-tls-decisions.md` (binding decisions, "PR 2 TLS" bullet and controller ruling Q2), with the facts in `.../scratchpad/chappe-clientcert-research.md` part A. This plan covers PR 2 only. PR 1 ("request body bounds") and the Foy work come before and after it, and are not part of this plan.

## Global Constraints

- Repository: `/Users/yblazart/projects/perso/vidocq/chappe` (its own Maven reactor; run everything from that directory, after `sdk env`: `java=25-tem`, `maven=3.9.16`).
- English only: code, Javadoc, comments, test names, commit messages, BUG.md, docs.
- Zero dependencies outside the JDK. JUnit stays the only test dependency. `keytool` is a JDK tool, already used by `TlsTest`, `TlsLingeringCloseTest` and `ConnectionUpgradeTlsTest`.
- Java Modules: no `module-info.java` change. `java.security.cert.X509Certificate` and `javax.net.ssl.*` are in `java.base`. `chappe-api` keeps its current `requires java.net.http;` and gets no new `requires`. Never write the four-letter abbreviation of the Java Platform Module System in code, comments, docs or commit messages; write "Java Modules" (chappe CLAUDE.md, "Terminology").
- TDD: every task writes its test first, runs it red, then writes the code.
- Virtual threads only. This plan adds no thread or executor.
- Prefer records for immutable values (chappe CLAUDE.md).
- Formatting: `./mvnw -ntp spotless:apply` before each commit. Spotless `check` runs in `verify` (Palantir style, import order `java,javax,jakarta,io.vidocq,`). Every new file starts with the license header copied verbatim from `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java` lines 1-19.
- Commits: `git commit -S -s -F -` with the message in a heredoc, Conventional Commits with the CHAPPE id when there is one. The message ends with this paragraph, two lines and no blank line between them (`-s` appends `Signed-off-by` to it):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
  ```
- BUG.md: `## CHAPPE-NNN — <title>` sections, newest first, with Date, Status, Module, Symptom, Minimal reproduction, Cause, Fix. This plan uses **CHAPPE-022** for the connection info bug; see "Before Task 1" for the re-check.
- Docs: Antora component `docs/en`, English only, new sections carry the `[.tag-new]#NEW#` badge, and `whats-new.adoc` lists them.
- Public API added: `enum ClientAuth {NONE, WANT, NEED}`, `record TlsInfo(String protocol, String cipherSuite, byte[] sessionId, List<X509Certificate> peerCertificates)`, `Request.tls()` returning `Optional<TlsInfo>`, `ForwardingRequest.tls()`, `Server.Builder.tls(SSLContext, ClientAuth)`, `ServerConfig.clientAuth()`. `Server.Builder.tls(SSLContext)` stays and means `ClientAuth.NONE`.
- Client authentication is per listener (one `Server` = one mode), decided at the handshake. No per-URL certificate request. TLS 1.2 client-initiated renegotiation is refused by `SslHandler` itself (controller ruling R-1), without relying on `jdk.tls.rejectClientInitiatedRenegotiation`.
- `WANT` with a certificate the trust managers reject fails the handshake (JSSE behaviour, pinned by a test and documented: ruling R-2).
- A handshake rejected by the server sends its TLS alert before the close (ruling R-3). No truststore option: the caller's `SSLContext` carries the trust material.
- `TlsInfo` has no `keySize`. Deriving the key size from the cipher suite stays in Foy (ruling Q2).

## Review Focus

1. **A keep-alive request on a TLS connection still reports TLS.** `HttpRequestImpl.reset()` runs before every request and must not clear the connection fields. Pinned by `ConnectionInfoTest#tlsKeepAliveRequestsAllReportTheConnection` (Task 2).
2. **A handler behind `Router.mount` sees the same TLS data.** The mount wrapper is a `ForwardingRequest`, and a missing forward silently returns the interface default. Pinned by `ForwardingRequestParityTest` (Task 1) and `ConnectionInfoTest#mountedHandlerSeesTheTlsSession` (Task 2).
3. **`tls(null, NEED)` or `tls(null, WANT)` must fail at `build()`.** Otherwise the server serves cleartext and asks for no certificate, while the caller believes it is enforced. Pinned by `TlsClientAuthTest#clientAuthWithoutSslContextFailsAtBuild` (Task 3).
4. **`NEED` rejects a client whose certificate the server does not trust**, and the handler never runs. Pinned by `TlsClientAuthTest#needRejectsAnUntrustedClientCertificate` (Task 3).
5. **A `NEED` rejection closes the connection promptly.** It must not leave the client waiting until its own timeout, which a bare `IOException` assertion would accept because `HttpTimeoutException` is an `IOException`. Pinned by `assertRejected` in `TlsClientAuthTest` (Task 3), which fails on a timeout.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `chappe-api/src/main/java/io/vidocq/chappe/api/TlsInfo.java` | Create (Task 1) | Immutable, transport-neutral description of a TLS session. |
| `chappe-api/src/main/java/io/vidocq/chappe/api/Request.java` | Modify (Task 1) | `default Optional<TlsInfo> tls()`. |
| `chappe-api/src/main/java/io/vidocq/chappe/api/ForwardingRequest.java` | Modify (Task 1) | Forward `tls()`. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsInfoTest.java` | Create (Task 1) | Record contract: copies, equality, nulls. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/ConnectionInfo.java` | Create (Task 2) | Per-connection addresses + `TlsInfo`, built once. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java` | Modify (Task 2) | Build `TlsInfo` after `doHandshake()`; `tlsInfo()` accessor. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/HttpRequestImpl.java` | Modify (Task 2) | `initConnectionInfo(ConnectionInfo)`, `tls()`. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java` | Modify (Task 2) | Take a `ConnectionInfo` instead of guessing from the `Closeable`. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` | Modify (Task 2) | Take a `ConnectionInfo`; copy it into every stream request. |
| `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java` | Modify (Tasks 2, 3) | Build `ConnectionInfo`; apply `ClientAuth` to the engine. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java` | Create (Task 2) | Test certificates (keytool), trust stores, `SSLContext`s. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionInfoTest.java` | Create (Task 2) | h1+TLS, h2+TLS, h2c, cleartext h1, keep-alive, mount. |
| `BUG.md` | Modify (Task 2) | CHAPPE-022 entry. |
| `chappe-api/src/main/java/io/vidocq/chappe/api/ClientAuth.java` | Create (Task 3) | `NONE`, `WANT`, `NEED`. |
| `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java` | Modify (Task 3) | `clientAuth` component + validation. |
| `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java` | Modify (Task 3) | `tls(SSLContext, ClientAuth)`; `tls(SSLContext)` becomes a default. |
| `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java` | Modify (Task 3) | Store and pass `clientAuth`. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsClientAuthTest.java` | Create (Task 3) | NONE / WANT / NEED over h1 and h2, untrusted certificates. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java` | Modify (Task 3) | `contextAlwaysOffering`: a client that sends its certificate whatever the server's issuer list. |
| `chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java` | Modify (Task 4) | Refuse TLS 1.2 renegotiation; send the pending alert on a failed handshake. |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsHandshakeHardeningTest.java` | Create (Task 4) | Renegotiation refused, alert received by the client. |
| `BUG.md` | Modify (Task 4) | CHAPPE-023 entry (alert not sent). |
| `docs/en/modules/ROOT/pages/{usage,reference,whats-new,migration}.adoc`, `README.md` | Modify (Task 5) | User documentation. |

All paths are relative to `/Users/yblazart/projects/perso/vidocq/chappe`.

---

## Before Task 1: rebase onto PR 1 and re-check what PR 1 changed

PR 1 ("request body bounds") lands first. It adds a drain-budget component to `ServerConfig`, wires `readTimeout`, and adds BUG.md entries. This plan was written against `origin/main` @ `7cef196`, before PR 1.

- [ ] **Step 1: Start from the updated main**

```bash
cd /Users/yblazart/projects/perso/vidocq/chappe
git fetch origin
git switch -c pr/ybl/chappe-tls-client-auth origin/main
git log --oneline -5   # PR 1's commits must be there; stop and ask the controller if they are not
```

- [ ] **Step 2: Re-read the files PR 1 may have touched**

Read these files in full before Task 1. Where a code block in this plan shows the "current" text of one of them, use the text on main as the base and apply only the edit the step describes:
`chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java`, `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java`, `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java`, `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java` (constructors, lines ~70-110 before PR 1), `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` (constructors ~98-129, stream creation ~275), `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java` (`handleTlsConnection` ~372-406, `handleCleartextConnection` ~411-445).

- [ ] **Step 3: Fix the BUG id**

```bash
grep -m3 -n "^## CHAPPE-" BUG.md
```

Before PR 1 the top entry is `CHAPPE-019`, and PR 1 is expected to add `CHAPPE-020` and `CHAPPE-021`. This plan writes its two new ids as **CHAPPE-022** (connection info, Task 2) and **CHAPPE-023** (handshake alert, Task 4). If the top entry is not `CHAPPE-021`, use the next two free numbers instead, and replace `CHAPPE-022` / `CHAPPE-023` everywhere they appear in this plan's code, BUG.md entries, docs and commit messages.

- [ ] **Step 4: Baseline build**

Run: `./mvnw -ntp install -DskipTests && ./mvnw -ntp -pl chappe-tests test -Dtest='TlsTest,ExtensionSpiTest,ForwardingRequestParityTest'`
Expected: BUILD SUCCESS.

---

### Task 1: `TlsInfo` record and `Request.tls()`

**Files:**
- Create: `chappe-api/src/main/java/io/vidocq/chappe/api/TlsInfo.java`
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/Request.java` (after `scheme()`, end of the interface)
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ForwardingRequest.java` (after `scheme()`, end of the interface)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsInfoTest.java` (create); `chappe-tests/src/test/java/io/vidocq/chappe/tests/ForwardingRequestParityTest.java` (existing, unchanged; it goes red when `Request.tls()` exists without a forward)

**Interfaces:**
- Consumes: nothing.
- Produces: `public record TlsInfo(String protocol, String cipherSuite, byte[] sessionId, List<X509Certificate> peerCertificates)` in `io.vidocq.chappe.api`: `protocol` and `cipherSuite` are non-null, `sessionId()` returns a copy (never null, possibly empty), `peerCertificates()` is an immutable list (never null, possibly empty, client certificate first). `default Optional<TlsInfo> Request.tls()` returns `Optional.empty()`. `ForwardingRequest.tls()` returns `delegate().tls()`.

Style choice, `Optional<TlsInfo>` over a nullable return: `Request` already returns `Optional` when a value is legitimately absent and callers branch on it (`header(String)`, `queryParam(String)`), and the nullable accessors (`remoteAddress()`, `query()`, `attribute()`) are older raw-socket or Servlet-shaped values. Here cleartext is a normal case, not an error, so `Optional` makes callers handle it. `isSecure()` stays the cheap boolean, and the two always agree.

Equality choice for `byte[] sessionId`: a record compares array components by reference, so two equal sessions would be unequal. `TlsInfo` overrides `equals`, `hashCode` and `toString` to use the array content (`Arrays.equals`, `Arrays.hashCode`, hex in `toString`). Certificates compare with `X509Certificate.equals`, which compares the encoded form.

- [ ] **Step 1: Write the failing record test**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsInfoTest.java` (license header, then):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.TlsInfo;

import org.junit.jupiter.api.Test;

/** Contract of {@link TlsInfo}: defensive copies, content equality, absent values. */
class TlsInfoTest {

    private static final String SUITE = "TLS_AES_128_GCM_SHA256";

    @Test
    void sessionIdIsCopiedOnTheWayInAndOut() {
        byte[] id = {1, 2, 3};
        var info = new TlsInfo("TLSv1.3", SUITE, id, List.of());

        id[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, info.sessionId(), "the caller's array must not leak in");

        info.sessionId()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, info.sessionId(), "the returned array must be a copy");
    }

    @Test
    void peerCertificatesAreAnImmutableCopy() {
        var source = new ArrayList<X509Certificate>();
        var info = new TlsInfo("TLSv1.3", SUITE, new byte[0], source);

        source.add(null);
        assertTrue(info.peerCertificates().isEmpty(), "the caller's list must not leak in");
        assertThrows(UnsupportedOperationException.class, () -> info.peerCertificates().add(null));
    }

    @Test
    void equalityComparesTheSessionIdContent() {
        var a = new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 2}, List.of());
        var b = new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 2}, List.of());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new TlsInfo("TLSv1.3", SUITE, new byte[] {1, 3}, List.of()));
        assertNotEquals(a, new TlsInfo("TLSv1.2", SUITE, new byte[] {1, 2}, List.of()));
    }

    @Test
    void absentSessionIdAndChainBecomeEmpty() {
        var info = new TlsInfo("TLSv1.2", SUITE, null, null);

        assertEquals(0, info.sessionId().length);
        assertEquals(List.of(), info.peerCertificates());
    }

    @Test
    void protocolAndCipherSuiteAreRequired() {
        assertThrows(NullPointerException.class, () -> new TlsInfo(null, SUITE, new byte[0], List.of()));
        assertThrows(NullPointerException.class, () -> new TlsInfo("TLSv1.3", null, new byte[0], List.of()));
    }

    @Test
    void toStringShowsTheSessionIdInHex() {
        var info = new TlsInfo("TLSv1.3", SUITE, new byte[] {0x0a, (byte) 0xff}, List.of());

        assertTrue(info.toString().contains("sessionId=0aff"), info.toString());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=TlsInfoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: class TlsInfo`.

- [ ] **Step 3: Create `TlsInfo`**

Create `chappe-api/src/main/java/io/vidocq/chappe/api/TlsInfo.java` (license header, then):

```java
package io.vidocq.chappe.api;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The TLS session of a connection, as negotiated by its handshake. Transport-neutral: it
 * describes the session, not the engine that produced it.
 *
 * <p>Chappe reads it once, when the handshake completes, and shares it with every request
 * of the connection (HTTP/1.1 keep-alive requests, HTTP/2 streams). It is immutable: the
 * session id and the chain are copied on the way in, and {@link #sessionId()} returns a copy.
 *
 * <p>Equality compares the content of {@code sessionId} (a plain record would compare the
 * array reference) and the certificates with {@link X509Certificate#equals}.
 *
 * @param protocol the negotiated protocol, for example {@code "TLSv1.3"}
 * @param cipherSuite the negotiated cipher suite, for example {@code "TLS_AES_128_GCM_SHA256"}
 * @param sessionId the session id; empty when the session has none ({@code null} is read as empty)
 * @param peerCertificates the client's certificate chain, the client's own certificate first;
 *     empty when the listener did not ask for one or the client sent none ({@code null} is read
 *     as empty)
 */
public record TlsInfo(
        String protocol, String cipherSuite, byte[] sessionId, List<X509Certificate> peerCertificates) {

    public TlsInfo {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(cipherSuite, "cipherSuite");
        sessionId = sessionId == null ? new byte[0] : sessionId.clone();
        peerCertificates = peerCertificates == null ? List.of() : List.copyOf(peerCertificates);
    }

    /** A copy of the session id, never {@code null}. */
    @Override
    public byte[] sessionId() {
        return sessionId.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TlsInfo other
                && protocol.equals(other.protocol)
                && cipherSuite.equals(other.cipherSuite)
                && Arrays.equals(sessionId, other.sessionId)
                && peerCertificates.equals(other.peerCertificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, cipherSuite, Arrays.hashCode(sessionId), peerCertificates);
    }

    /** Session id in hex and certificate subjects only: a certificate's own {@code toString} spans pages. */
    @Override
    public String toString() {
        return "TlsInfo[protocol=" + protocol
                + ", cipherSuite=" + cipherSuite
                + ", sessionId=" + HexFormat.of().formatHex(sessionId)
                + ", peerCertificates="
                + peerCertificates.stream()
                        .map(c -> c.getSubjectX500Principal().getName())
                        .toList()
                + "]";
    }
}
```

Note: `List.copyOf` throws `NullPointerException` on a `null` element. That is intended: a chain with a hole is a caller bug.

- [ ] **Step 4: Run the record test**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=TlsInfoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (6 tests).

- [ ] **Step 5: Add `Request.tls()` and watch the parity test fail**

In `Request.java`, after the `scheme()` method (the last member), add:

```java
    /**
     * The TLS session of the connection that carried this request, or empty on a cleartext
     * connection. Present exactly when {@link #isSecure()} is {@code true}.
     *
     * <p>Read once per connection, when the TLS handshake completes, and shared by every
     * request of that connection (HTTP/1.1 keep-alive, HTTP/2 streams).
     * {@link TlsInfo#peerCertificates()} holds the client's chain when the listener asked for
     * one (see {@code Server.Builder.tls(SSLContext, ClientAuth)}) and the client sent it.
     */
    default Optional<TlsInfo> tls() {
        return Optional.empty();
    }
```

(`java.util.Optional` is already imported in `Request.java`.)

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=ForwardingRequestParityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `ForwardingRequest must forward every Request method; missing: [tls[]]`.

- [ ] **Step 6: Forward it**

In `ForwardingRequest.java`, after the `scheme()` override (the last member), add:

```java
    @Override
    default Optional<TlsInfo> tls() {
        return delegate().tls();
    }
```

(`java.util.Optional` is already imported.)

- [ ] **Step 7: Run both tests**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest='TlsInfoTest,ForwardingRequestParityTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
./mvnw -ntp spotless:apply
git add chappe-api/src/main/java/io/vidocq/chappe/api/TlsInfo.java \
        chappe-api/src/main/java/io/vidocq/chappe/api/Request.java \
        chappe-api/src/main/java/io/vidocq/chappe/api/ForwardingRequest.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsInfoTest.java
git commit -S -s -F - <<'EOF'
feat(api): add TlsInfo and Request.tls()

TlsInfo describes the TLS session of a connection: protocol, cipher
suite, session id and the client's certificate chain. It copies the
session id and the chain, and compares the session id by content.

Request.tls() returns it, or empty on cleartext; ForwardingRequest
forwards it. Nothing fills it yet: the transport wiring follows.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 2: Connection info over TLS and HTTP/2 (CHAPPE-022)

**Files:**
- Create: `chappe-http/src/main/java/io/vidocq/chappe/http/ConnectionInfo.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java` (fields ~52-70, end of `doHandshake()` ~157, ALPN section ~304)
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpRequestImpl.java:116-138` (`initConnectionInfo`, connection fields) and the accessors ~389-407
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java:68-109` (constructors)
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java:98-129` (constructors, field), `:275` (stream creation)
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java:372-445`
- Modify: `BUG.md` (new top entry)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java` (create), `chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionInfoTest.java` (create)

**Interfaces:**
- Consumes: `TlsInfo`, `Request.tls()` (Task 1).
- Produces:
  - `public record ConnectionInfo(InetSocketAddress remoteAddress, InetSocketAddress localAddress, TlsInfo tls)` in `io.vidocq.chappe.http`, with `boolean secure()` and `static ConnectionInfo of(SocketChannel channel, TlsInfo tls)`.
  - `public TlsInfo SslHandler.tlsInfo()`: `null` before `doHandshake()` returns.
  - `public void HttpRequestImpl.initConnectionInfo(ConnectionInfo info)`, which replaces `initConnectionInfo(SocketChannel, boolean)`.
  - `HttpConnection(ReadableByteChannel, WritableByteChannel, Closeable, ConnectionInfo, Handler, ServerConfig, ByteBuffer prefilledBuffer, ByteBuffer writeBuffer)`, which replaces the 7-argument constructor.
  - `Http2Connection(ReadableByteChannel, WritableByteChannel, ConnectionInfo, Handler, ServerConfig, ByteBuffer)`, which replaces the 5-argument channel constructor.
  - Test helper `TlsFixtures` (package `io.vidocq.chappe.tests`): `KeyStore selfSigned(String alias, String dname, String san)`, `X509Certificate certificate(KeyStore, String alias)`, `KeyStore trustStore(X509Certificate...)`, `SSLContext context(KeyStore keys, KeyStore trust)`, `char[] PASSWORD`. Task 3 uses all of them.

Root cause, verified at `7cef196`: `HttpConnection.java:105-108` only fills the connection fields when `closeable instanceof SocketChannel`. Over TLS the closeable is the `SslHandler`, so `isSecure()` is `false`, `scheme()` is `"http"` and both addresses are `null`. `Http2Stream.java:77` creates a fresh `HttpRequestImpl` per stream, and nothing in `h2/` sets connection info, so h2 and h2c requests have no addresses and h2 over TLS reports `isSecure() == false`. No chappe test asserts `isSecure()` over TLS.

- [ ] **Step 1: Write the test fixtures**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java` (license header, then):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Test certificates generated at test time with the JDK {@code keytool} (as {@code TlsTest}
 * does; the JDK has no public API to issue a certificate), plus in-memory trust stores and
 * {@link SSLContext}s built from them. Nothing is committed to the repository.
 */
final class TlsFixtures {

    static final char[] PASSWORD = "changeit".toCharArray();

    private TlsFixtures() {}

    /**
     * A PKCS12 key store holding one self-signed RSA key pair under {@code alias}.
     *
     * @param san the subjectAltName extension value (for example {@code "ip:127.0.0.1"}), or
     *     {@code null} for none
     */
    static KeyStore selfSigned(String alias, String dname, String san) throws Exception {
        Path file = Files.createTempFile("chappe-" + alias + "-", ".p12");
        Files.delete(file); // keytool refuses to overwrite an existing file
        try {
            var command = new ArrayList<>(List.of(
                    "keytool", "-genkeypair",
                    "-alias", alias,
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "1",
                    "-dname", dname,
                    "-storetype", "PKCS12",
                    "-keystore", file.toString(),
                    "-storepass", "changeit",
                    "-keypass", "changeit"));
            if (san != null) {
                command.add("-ext");
                command.add("san=" + san);
            }
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), "keytool failed: " + output);

            var keyStore = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(file)) {
                keyStore.load(in, PASSWORD);
            }
            return keyStore;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    static X509Certificate certificate(KeyStore keyStore, String alias) throws Exception {
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    /** An in-memory trust store holding exactly {@code certificates}. */
    static KeyStore trustStore(X509Certificate... certificates) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int i = 0; i < certificates.length; i++) {
            trustStore.setCertificateEntry("trusted-" + i, certificates[i]);
        }
        return trustStore;
    }

    /**
     * A TLS context presenting the key pair of {@code keys} ({@code null}: present none) and
     * trusting exactly the certificates of {@code trust}.
     */
    static SSLContext context(KeyStore keys, KeyStore trust) throws Exception {
        KeyManager[] keyManagers = null;
        if (keys != null) {
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, PASSWORD);
            keyManagers = kmf.getKeyManagers();
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(keyManagers, tmf.getTrustManagers(), null);
        return context;
    }
}
```

Leave the three existing TLS tests as they are. Moving them onto `TlsFixtures` is not part of this PR.

- [ ] **Step 2: Write the failing connection info test**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionInfoTest.java` (license header, then):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-022: connection metadata ({@code isSecure}, {@code scheme}, addresses, {@code tls()})
 * on every transport path — HTTP/1.1 and HTTP/2 over TLS, h2c, cleartext HTTP/1.1.
 * Before the fix, TLS reported {@code isSecure() == false}, scheme {@code http} and
 * {@code null} addresses, and HTTP/2 (TLS or h2c) had no addresses at all.
 */
class ConnectionInfoTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        // The server asks for no client certificate (ClientAuth.NONE); its trust store is never used.
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    /** {@code secure|scheme|remote host|local port|protocol|cipher suite|peer count}. */
    private static String describe(Request req) {
        var remote = req.remoteAddress();
        var local = req.localAddress();
        return String.join(
                "|",
                String.valueOf(req.isSecure()),
                req.scheme(),
                remote == null ? "null" : remote.getAddress().getHostAddress(),
                local == null ? "null" : String.valueOf(local.getPort()),
                req.tls().map(t -> t.protocol()).orElse("no-tls"),
                req.tls().map(t -> t.cipherSuite()).orElse("no-tls"),
                req.tls().map(t -> String.valueOf(t.peerCertificates().size())).orElse("no-tls"));
    }

    private void startTls(Handler handler) {
        server = Server.builder().port(0).tls(serverContext).handler(handler).build();
        server.start();
    }

    private static HttpClient tlsClient(HttpClient.Version version) {
        return HttpClient.newBuilder()
                .version(version)
                .sslContext(clientContext)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> send(HttpClient client, String scheme, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(scheme + "://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertTls(String described) {
        String[] parts = described.split("\\|");
        assertEquals("true", parts[0], "isSecure: " + described);
        assertEquals("https", parts[1], "scheme: " + described);
        assertEquals("127.0.0.1", parts[2], "remote address: " + described);
        assertEquals(String.valueOf(server.port()), parts[3], "local port: " + described);
        assertTrue(parts[4].matches("TLSv1\\.[23]"), "protocol: " + described);
        assertTrue(parts[5].startsWith("TLS_"), "cipher suite: " + described);
        assertEquals("0", parts[6], "no client certificate was asked for: " + described);
    }

    @Test
    void http11OverTlsReportsTheConnection() throws Exception {
        startTls(req -> Response.ok(describe(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            var response = send(client, "https", "/");
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertTls(response.body());
        }
    }

    @Test
    void http2OverTlsReportsTheConnectionOnEveryStream() throws Exception {
        startTls(req -> Response.ok(describe(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_2)) {
            for (int i = 0; i < 2; i++) { // two streams on one connection
                var response = send(client, "https", "/");
                assertEquals(HttpClient.Version.HTTP_2, response.version(), "ALPN must select h2");
                assertTls(response.body());
            }
        }
    }

    @Test
    void tlsKeepAliveRequestsAllReportTheConnection() throws Exception {
        startTls(req -> Response.ok(describe(req)));
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            for (int i = 0; i < 3; i++) { // reset() between requests must keep the connection fields
                assertTls(send(client, "https", "/").body());
            }
        }
    }

    @Test
    void mountedHandlerSeesTheTlsSession() throws Exception {
        startTls(Router.builder().mount("/app", req -> Response.ok(describe(req))).build());
        try (var client = tlsClient(HttpClient.Version.HTTP_1_1)) {
            assertTls(send(client, "https", "/app/x").body());
        }
    }

    @Test
    void cleartextHttp11HasNoTlsSession() throws Exception {
        server = Server.builder().port(0).handler(req -> Response.ok(describe(req))).build();
        server.start();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            assertEquals(
                    "false|http|127.0.0.1|" + server.port() + "|no-tls|no-tls|no-tls",
                    send(client, "http", "/").body());
        }
    }

    @Test
    void h2cPriorKnowledgeReportsTheAddresses() throws Exception {
        var seen = new CompletableFuture<String>();
        server = Server.builder()
                .port(0)
                .handler(req -> {
                    seen.complete(describe(req) + "|" + req.version().name());
                    return Response.ok("ok");
                })
                .build();
        server.start();

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            var out = new DataOutputStream(socket.getOutputStream());
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, 0x4, 0, 0, new byte[0]); // SETTINGS
            writeFrame(out, 0x1, 0x5, 1, getHeaders()); // HEADERS, END_STREAM | END_HEADERS
            out.flush();

            assertEquals(
                    "false|http|127.0.0.1|" + server.port() + "|no-tls|no-tls|no-tls|HTTP_2",
                    seen.get(5, TimeUnit.SECONDS));
        }
    }

    private static void writeFrame(DataOutputStream out, int type, int flags, int streamId, byte[] payload)
            throws Exception {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    /** HPACK literal header fields without indexing, no Huffman (RFC 7541 §6.2.2). */
    private static byte[] getHeaders() {
        var out = new ByteArrayOutputStream();
        literal(out, ":method", "GET");
        literal(out, ":scheme", "http");
        literal(out, ":authority", "127.0.0.1");
        literal(out, ":path", "/");
        return out.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, String name, String value) {
        out.write(0x00);
        for (String s : new String[] {name, value}) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=ConnectionInfoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `cleartextHttp11HasNoTlsSession` passes; the other five FAIL. The TLS tests fail on `isSecure: false|http|null|null|no-tls|...`, and `h2cPriorKnowledgeReportsTheAddresses` fails on `false|http|null|null|...`.

- [ ] **Step 4: Create `ConnectionInfo`**

Create `chappe-http/src/main/java/io/vidocq/chappe/http/ConnectionInfo.java` (license header, then):

```java
package io.vidocq.chappe.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import io.vidocq.chappe.api.TlsInfo;

/**
 * Connection-level metadata every request of one connection reports: HTTP/1.1 keep-alive
 * requests and HTTP/2 streams alike. Built once by the server engine when the connection is
 * ready, after the TLS handshake on a TLS listener, and never changed.
 *
 * @param remoteAddress the client's address, or {@code null} if the socket could not report it
 * @param localAddress the server's address, or {@code null} if the socket could not report it
 * @param tls the TLS session, or {@code null} on a cleartext connection
 */
public record ConnectionInfo(InetSocketAddress remoteAddress, InetSocketAddress localAddress, TlsInfo tls) {

    /** {@code true} on a TLS connection. */
    public boolean secure() {
        return tls != null;
    }

    /** Reads the socket's addresses; one the socket cannot report (already closed) stays {@code null}. */
    public static ConnectionInfo of(SocketChannel channel, TlsInfo tls) {
        return new ConnectionInfo(address(channel, true), address(channel, false), tls);
    }

    private static InetSocketAddress address(SocketChannel channel, boolean remote) {
        try {
            return (InetSocketAddress) (remote ? channel.getRemoteAddress() : channel.getLocalAddress());
        } catch (IOException _) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Let `SslHandler` describe its session**

In `SslHandler.java`:

1. Add the imports (Spotless sorts them): `java.security.cert.Certificate`, `java.security.cert.X509Certificate`, `java.util.ArrayList`, `java.util.List`, `javax.net.ssl.SSLPeerUnverifiedException`, `javax.net.ssl.SSLSession`, `io.vidocq.chappe.api.TlsInfo`.
2. Add a field next to `closeWriteTimeout`:

```java
    /** The session of the completed handshake; {@code null} until {@link #doHandshake()} returns. */
    private volatile TlsInfo tlsInfo;
```

3. At the very end of `doHandshake()`, after the `while` loop closes, add:

```java
        tlsInfo = describe(engine.getSession());
```

4. In the "ALPN & lifecycle" section, after `getAlpnProtocol()`, add:

```java
    /**
     * The TLS session negotiated by {@link #doHandshake()}, or {@code null} before it returned.
     * Read once, at the end of the handshake.
     */
    public TlsInfo tlsInfo() {
        return tlsInfo;
    }

    private static TlsInfo describe(SSLSession session) {
        List<X509Certificate> chain = new ArrayList<>();
        try {
            for (Certificate certificate : session.getPeerCertificates()) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
        } catch (SSLPeerUnverifiedException _) {
            // The client sent no certificate, or the listener did not ask for one.
        }
        return new TlsInfo(session.getProtocol(), session.getCipherSuite(), session.getId(), chain);
    }
```

- [ ] **Step 6: Let `HttpRequestImpl` take a `ConnectionInfo`**

In `HttpRequestImpl.java`, replace the whole `initConnectionInfo(java.nio.channels.SocketChannel channel, boolean secure)` method (lines ~116-130 with its Javadoc) with:

```java
    /**
     * Sets the connection-level metadata. Called once per connection (HTTP/1.1) or once per
     * stream (HTTP/2); {@link #reset()} keeps it.
     */
    public void initConnectionInfo(ConnectionInfo info) {
        this.remoteAddress = info.remoteAddress();
        this.localAddress = info.localAddress();
        this.tls = info.tls();
        setSecure(info.secure());
    }
```

Next to `private boolean secure;` in the enrichment fields, add:

```java
    private TlsInfo tls;
```

Extend the comment in `reset()` to list `tls`: `// Per-request fields (NOT connection-level: remoteAddress, localAddress, secure, scheme, tls)`.

After the `scheme()` override, add:

```java
    @Override
    public Optional<TlsInfo> tls() {
        return Optional.ofNullable(tls);
    }
```

(`io.vidocq.chappe.api.*` and `java.util.Optional` are already imported. Keep the public setters `setRemoteAddress`, `setLocalAddress` and `setSecure`: the package is exported, so removing them is a separate cleanup.)

- [ ] **Step 7: Pass `ConnectionInfo` into `HttpConnection`**

In `HttpConnection.java`, replace the three constructors (lines ~68-109) with the version below. The body of the full constructor stays the same except for the new `connectionInfo` parameter and the last two statements. If PR 1 changed this body, keep PR 1's lines.

```java
    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config) {
        this(channel, channel, channel, ConnectionInfo.of(channel, null), handler, config, null, null);
    }

    public HttpConnection(SocketChannel channel, Handler handler, ServerConfig config, ByteBuffer prefilledBuffer) {
        this(channel, channel, channel, ConnectionInfo.of(channel, null), handler, config, prefilledBuffer, null);
    }

    /**
     * Full constructor: separate channels (an {@link SslHandler} over TLS) and the connection
     * metadata every request of this connection reports.
     */
    public HttpConnection(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            Closeable closeable,
            ConnectionInfo connectionInfo,
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

        // Connection-level metadata, once: reset() keeps it across keep-alive requests.
        request.initConnectionInfo(connectionInfo);
        request.disconnectArmer(this::armDisconnectProbe);
    }
```

If `SocketChannel` is no longer used anywhere else in the file, Spotless `removeUnusedImports` drops the import. It is still used by the two convenience constructors.

- [ ] **Step 8: Pass `ConnectionInfo` into `Http2Connection` and its streams**

In `Http2Connection.java`:

1. Add the import `io.vidocq.chappe.http.ConnectionInfo` next to `io.vidocq.chappe.http.HttpRequestImpl`.
2. Add a field next to `private final ServerConfig config;`:

```java
    /** Connection metadata copied into every stream's request. */
    private final ConnectionInfo connectionInfo;
```

3. Replace both constructors with:

```java
    /**
     * Creates a new cleartext (h2c) HTTP/2 connection.
     *
     * @param channel    the socket channel for the connection
     * @param handler    the application handler
     * @param config     the server configuration
     * @param readBuffer the pre-filled read buffer (from protocol sniffing)
     */
    public Http2Connection(SocketChannel channel, Handler handler, ServerConfig config, ByteBuffer readBuffer) {
        this(channel, channel, ConnectionInfo.of(channel, null), handler, config, readBuffer);
    }

    /**
     * Constructor accepting separate channels (for TLS via SslHandler) and the connection
     * metadata every stream's request reports.
     */
    public Http2Connection(
            ReadableByteChannel readChannel,
            WritableByteChannel writeChannel,
            ConnectionInfo connectionInfo,
            Handler handler,
            ServerConfig config,
            ByteBuffer readBuffer) {
        this.readChannel = readChannel;
        this.writeChannel = writeChannel;
        this.connectionInfo = connectionInfo;
        this.handler = handler;
        this.config = config;
        this.readBuffer = readBuffer;
        this.writeBuffer = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE);
        this.frameReader = new Http2FrameReader(readBuffer, readChannel);
        this.frameWriter = new Http2FrameWriter(writeBuffer, writeChannel);
        this.hpackDecoder = new HpackDecoder(localSettings.headerTableSize(), localSettings.maxHeaderListSize());
        this.hpackEncoder = new HpackEncoder();
    }
```

(If PR 1 added lines to this constructor body, keep them.)

4. At stream creation (~line 275), right after `var stream = new Http2Stream(...);`, add:

```java
        stream.request().initConnectionInfo(connectionInfo);
```

- [ ] **Step 9: Build `ConnectionInfo` in `ChappeServer`**

In `ChappeServer.java`, add the import `io.vidocq.chappe.http.ConnectionInfo`. In `handleTlsConnection`, replace everything from `// Protocol negotiated via ALPN` to the end of the method with:

```java
        // Connection metadata, once per connection, after the handshake (CHAPPE-022)
        var connectionInfo = ConnectionInfo.of(channel, sslHandler.tlsInfo());

        // Protocol negotiated via ALPN
        String protocol = sslHandler.getAlpnProtocol();
        boolean isH2 = "h2".equals(protocol);

        if (isH2) {
            // HTTP/2 via ALPN — no pre-read, Http2Connection handles the preface
            readBuffer.clear();
            readBuffer.flip(); // empty
            new Http2Connection(sslHandler, sslHandler, connectionInfo, handler, config, readBuffer).run();
        } else {
            // HTTP/1.1 — pre-read for the parser
            readBuffer.clear();
            int read = sslHandler.read(readBuffer);
            if (read == -1) {
                sslHandler.close();
                return;
            }
            readBuffer.flip();
            new HttpConnection(
                            sslHandler,
                            sslHandler,
                            sslHandler,
                            connectionInfo,
                            handler,
                            config,
                            readBuffer,
                            writeBuffer)
                    .run();
        }
```

In `handleCleartextConnection`, replace the HTTP/1.1 dispatch line with:

```java
            new HttpConnection(
                            channel,
                            channel,
                            channel,
                            ConnectionInfo.of(channel, null),
                            handler,
                            config,
                            readBuffer,
                            writeBuffer)
                    .run();
```

The h2c line `new Http2Connection(channel, handler, config, readBuffer).run();` stays: that constructor now builds the `ConnectionInfo` itself. Keep any `readTimeout` or drain changes PR 1 made in these methods.

- [ ] **Step 10: Run the tests**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest='ConnectionInfoTest,TlsTest,TlsLingeringCloseTest,ConnectionUpgradeTlsTest,ExtensionSpiTest,Http2*Test,DisconnectProbeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including all 6 `ConnectionInfoTest` tests.

- [ ] **Step 11: File the bug**

Insert at the top of `BUG.md`, right after the `---` that follows the header paragraph and before the current first `## CHAPPE-...` entry:

```markdown
## CHAPPE-022 — over TLS and HTTP/2, a request reported cleartext and no client address

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): report TLS and HTTP/2 connection info to handlers (CHAPPE-022)`,
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
```

- [ ] **Step 12: Commit**

```bash
./mvnw -ntp spotless:apply
git add chappe-http/src/main/java/io/vidocq/chappe/http/ConnectionInfo.java \
        chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java \
        chappe-http/src/main/java/io/vidocq/chappe/http/HttpRequestImpl.java \
        chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java \
        chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java \
        chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionInfoTest.java \
        BUG.md
git commit -S -s -F - <<'EOF'
fix(http): report TLS and HTTP/2 connection info to handlers (CHAPPE-022)

Over TLS, HttpConnection only filled the connection fields when its
Closeable was a SocketChannel, which the SslHandler is not: isSecure()
was false, scheme() "http" and both addresses null. HTTP/2 streams got
no connection fields at all, over TLS or h2c.

ChappeServer now builds a ConnectionInfo once per connection: the
socket addresses and the TlsInfo SslHandler reads when the handshake
completes. HttpConnection takes it, and Http2Connection copies it into
every stream's request. Request.tls() returns the session.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 3: `ClientAuth` per listener

**Files:**
- Create: `chappe-api/src/main/java/io/vidocq/chappe/api/ClientAuth.java`
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java` (Javadoc, components, `DEFAULT`, compact constructor)
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java:105-106` (`Builder.tls`)
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java:45,103-107,127-138`
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java` (`handleTlsConnection`, SSLParameters block ~378-381)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsClientAuthTest.java` (create), `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java` (modify: add `contextAlwaysOffering`)

**Interfaces:**
- Consumes: `TlsFixtures` (Task 2), `Request.tls()` / `TlsInfo.peerCertificates()` (Tasks 1-2).
- Produces (test helper): `static SSLContext TlsFixtures.contextAlwaysOffering(KeyStore keys, String alias, KeyStore trust)`, a client context that always presents `alias`, even when the server's CertificateRequest does not list its issuer.
- Produces: `public enum ClientAuth { NONE, WANT, NEED }` in `io.vidocq.chappe.api`. `ServerConfig.clientAuth()` is non-null and is `ClientAuth.NONE` in `ServerConfig.DEFAULT`. `new ServerConfig(...)` throws `IllegalArgumentException` when `sslContext == null && clientAuth != NONE`. `Server.Builder tls(SSLContext sslContext, ClientAuth clientAuth)` is abstract and throws `NullPointerException` on a `null` `clientAuth`. `default Server.Builder tls(SSLContext sslContext)` returns `tls(sslContext, ClientAuth.NONE)`.

How the JDK behaves, which the tests rely on: with `NEED`, a client that sends no certificate, or one the server's trust managers reject, fails the handshake. `SslHandler.doHandshake()` throws an `SSLHandshakeException` (an `IOException`), `ChappeServer.handleConnection` closes the channel, and the handler never runs. Under TLS 1.3 the client may finish its side of the handshake first, so it sees the failure on its first read, as an `IOException`, not necessarily an `SSLHandshakeException`. The tests therefore assert "an `IOException` that is not an `HttpTimeoutException`, and the handler never ran". With `WANT`, a client without a certificate is accepted and `peerCertificates()` is empty. With `WANT` and a certificate the trust managers reject, JSSE fails the handshake as with `NEED` (ruling R-2; pinned by `wantRejectsAnUntrustedClientCertificate`). If that test shows JSSE accepting the client instead, stop and report to the controller. Do not change the expectation.

The JDK key manager does not offer a certificate whose issuer is missing from the server's CertificateRequest list. A plain context would therefore send no certificate, and the "untrusted" tests would only test "no certificate". `contextAlwaysOffering` forces the certificate onto the wire so the server's trust check is what rejects it.

- [ ] **Step 0: Add the forcing client context to the fixtures**

In `TlsFixtures.java`, add the imports `java.net.Socket`, `java.security.Principal`, `java.security.PrivateKey`, `javax.net.ssl.SSLEngine`, `javax.net.ssl.X509ExtendedKeyManager`, and this method after `context(...)`:

```java
    /**
     * A TLS client context that always presents the key pair {@code alias} of {@code keys}, even
     * when the server's CertificateRequest does not list its issuer (the JDK key manager would
     * then send no certificate). Used to put an untrusted certificate on the wire.
     */
    static SSLContext contextAlwaysOffering(KeyStore keys, String alias, KeyStore trust) throws Exception {
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        var delegate = (X509ExtendedKeyManager) kmf.getKeyManagers()[0];
        var forcing = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[] {alias};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return alias;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return alias;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return delegate.getServerAliases(keyType, issuers);
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return delegate.chooseServerAlias(keyType, issuers, socket);
            }

            @Override
            public X509Certificate[] getCertificateChain(String name) {
                return delegate.getCertificateChain(name);
            }

            @Override
            public PrivateKey getPrivateKey(String name) {
                return delegate.getPrivateKey(name);
            }
        };
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {forcing}, tmf.getTrustManagers(), null);
        return context;
    }
```

- [ ] **Step 1: Write the failing client-auth test**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsClientAuthTest.java` (license header, then):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.ServerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * TLS client authentication per listener ({@link ClientAuth}), over HTTP/1.1 and over HTTP/2
 * negotiated by ALPN. The handler answers with the subject of the client certificate it sees
 * ({@code none} when the chain is empty).
 */
class TlsClientAuthTest {

    private static final String CLIENT_DN = "CN=chappe-client";

    private static SSLContext serverContext;
    private static SSLContext clientWithCertificate;
    private static SSLContext clientWithoutCertificate;
    private static SSLContext untrustedClient;

    private final AtomicInteger handled = new AtomicInteger();
    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore clientKeys = TlsFixtures.selfSigned("client", CLIENT_DN, null);
        KeyStore strangerKeys = TlsFixtures.selfSigned("stranger", "CN=stranger", null);
        X509Certificate serverCertificate = TlsFixtures.certificate(serverKeys, "server");

        // The server trusts the client certificate only, not the stranger's.
        serverContext = TlsFixtures.context(
                serverKeys, TlsFixtures.trustStore(TlsFixtures.certificate(clientKeys, "client")));
        KeyStore trustServer = TlsFixtures.trustStore(serverCertificate);
        clientWithCertificate = TlsFixtures.context(clientKeys, trustServer);
        clientWithoutCertificate = TlsFixtures.context(null, trustServer);
        untrustedClient = TlsFixtures.contextAlwaysOffering(strangerKeys, "stranger", trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private static String peer(Request req) {
        return req.tls()
                .map(t -> t.peerCertificates().isEmpty()
                        ? "none"
                        : t.peerCertificates().getFirst().getSubjectX500Principal().getName())
                .orElse("no-tls");
    }

    private void start(ClientAuth clientAuth) {
        server = Server.builder()
                .port(0)
                .tls(serverContext, clientAuth)
                .handler(req -> {
                    handled.incrementAndGet();
                    return Response.ok(peer(req));
                })
                .build();
        server.start();
    }

    private static HttpClient client(SSLContext context, HttpClient.Version version) {
        return HttpClient.newBuilder()
                .version(version)
                .sslContext(context)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> get(HttpClient client) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String body(SSLContext context, HttpClient.Version version) throws Exception {
        try (var client = client(context, version)) {
            var response = get(client);
            assertEquals(200, response.statusCode());
            assertEquals(version, response.version(), "ALPN must select the requested protocol");
            return response.body();
        }
    }

    /** The connection is refused: an I/O failure that is not the client's own timeout, and no handler call. */
    private void assertRejected(SSLContext context, HttpClient.Version version) {
        try (var client = client(context, version)) {
            var failure = assertThrows(IOException.class, () -> get(client));
            assertFalse(
                    failure instanceof HttpTimeoutException,
                    "the server must close the connection, not leave the client waiting: " + failure);
        }
        assertEquals(0, handled.get(), "the handler must not run for a rejected client");
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needRejectsAClientWithoutCertificate(HttpClient.Version version) {
        start(ClientAuth.NEED);
        assertRejected(clientWithoutCertificate, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needRejectsAnUntrustedClientCertificate(HttpClient.Version version) {
        start(ClientAuth.NEED);
        assertRejected(untrustedClient, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantRejectsAnUntrustedClientCertificate(HttpClient.Version version) {
        // WANT makes the certificate optional, not unchecked: JSSE fails the handshake when the
        // client sends one the trust managers reject (ruling R-2, documented in the reference).
        start(ClientAuth.WANT);
        assertRejected(untrustedClient, version);
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void needAcceptsATrustedClientAndExposesItsChain(HttpClient.Version version) throws Exception {
        start(ClientAuth.NEED);
        assertEquals(CLIENT_DN, body(clientWithCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantAcceptsAClientWithoutCertificate(HttpClient.Version version) throws Exception {
        start(ClientAuth.WANT);
        assertEquals("none", body(clientWithoutCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void wantExposesTheClientChain(HttpClient.Version version) throws Exception {
        start(ClientAuth.WANT);
        assertEquals(CLIENT_DN, body(clientWithCertificate, version));
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpClient.Version.class,
            names = {"HTTP_1_1", "HTTP_2"})
    void noneNeverAsksForTheClientCertificate(HttpClient.Version version) throws Exception {
        start(ClientAuth.NONE);
        assertEquals("none", body(clientWithCertificate, version));
    }

    @Test
    void wantSharesTheChainAcrossHttp2Streams() throws Exception {
        start(ClientAuth.WANT);
        try (var client = client(clientWithCertificate, HttpClient.Version.HTTP_2)) {
            for (int i = 0; i < 3; i++) {
                assertEquals(CLIENT_DN, get(client).body(), "stream " + i);
            }
        }
    }

    @Test
    void tlsWithoutClientAuthMeansNone() {
        var config = Server.builder()
                .port(0)
                .tls(serverContext)
                .handler(_ -> Response.ok())
                .build()
                .config();
        assertEquals(ClientAuth.NONE, config.clientAuth());
        assertEquals(ClientAuth.NONE, ServerConfig.DEFAULT.clientAuth());
    }

    @Test
    void clientAuthWithoutSslContextFailsAtBuild() {
        for (ClientAuth mode : new ClientAuth[] {ClientAuth.WANT, ClientAuth.NEED}) {
            var builder = Server.builder().port(0).tls(null, mode).handler(_ -> Response.ok());
            assertThrows(IllegalArgumentException.class, builder::build, mode.name());
        }
    }

    @Test
    void nullClientAuthIsRejected() {
        assertThrows(NullPointerException.class, () -> Server.builder().tls(serverContext, null));
    }
}
```

`chappe-tests/pom.xml` declares the aggregate `org.junit.jupiter:junit-jupiter` (lines 40-42), which includes `junit-jupiter-params`, so no POM change is needed.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=TlsClientAuthTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: class ClientAuth`.

- [ ] **Step 3: Create `ClientAuth`**

Create `chappe-api/src/main/java/io/vidocq/chappe/api/ClientAuth.java` (license header, then):

```java
package io.vidocq.chappe.api;

/**
 * Whether a TLS listener asks clients for a certificate. Applied to every TLS handshake of
 * the listener; the client certificate is never requested later in the connection (no
 * renegotiation, and HTTP/2 forbids it). The trust material that validates the certificate
 * comes from the listener's {@link javax.net.ssl.SSLContext}.
 *
 * <p>The chain the client sent is exposed through {@link Request#tls()}
 * ({@link TlsInfo#peerCertificates()}).
 */
public enum ClientAuth {

    /** No certificate is requested; {@link TlsInfo#peerCertificates()} is always empty. */
    NONE,

    /**
     * A certificate is requested and optional: a client that sends none is accepted, with an
     * empty {@link TlsInfo#peerCertificates()}. A client that sends one the trust managers
     * reject fails the handshake, as with {@link #NEED}: optional does not mean unchecked.
     */
    WANT,

    /** A certificate is required: a client that sends none, or an untrusted one, fails the handshake. */
    NEED
}
```

- [ ] **Step 4: Add the `ServerConfig` component**

In `ServerConfig.java` (as re-read in "Before Task 1"; PR 1's component stays where PR 1 put it):

1. Javadoc: after `@param sslContext ...`, add
   ` * @param clientAuth whether TLS clients are asked for a certificate (default: {@link ClientAuth#NONE}); anything but {@code NONE} requires an {@code sslContext}`
2. Components: insert `ClientAuth clientAuth,` immediately after `SSLContext sslContext,`.
3. `DEFAULT`: insert `ClientAuth.NONE,` immediately after the `null,` that is the `sslContext` argument.
4. Add this compact constructor right after the component list, before `DEFAULT`. If PR 1 already added a compact constructor, put these statements at its start:

```java
    public ServerConfig {
        Objects.requireNonNull(clientAuth, "clientAuth");
        if (sslContext == null && clientAuth != ClientAuth.NONE) {
            throw new IllegalArgumentException(
                    "clientAuth " + clientAuth + " requires TLS: configure an SSLContext");
        }
    }
```

Add `import java.util.Objects;` next to `import java.util.List;`.

- [ ] **Step 5: Add the builder overload**

In `Server.java`, replace

```java
        /** Configures TLS with the given {@link SSLContext}. */
        Builder tls(SSLContext sslContext);
```

with

```java
        /**
         * Configures TLS with the given {@link SSLContext}; clients are not asked for a
         * certificate. Same as {@code tls(sslContext, ClientAuth.NONE)}.
         */
        default Builder tls(SSLContext sslContext) {
            return tls(sslContext, ClientAuth.NONE);
        }

        /**
         * Configures TLS with the given {@link SSLContext} and client certificate mode. The
         * mode applies to every connection of this listener; the context's trust managers
         * validate the client certificate. {@link #build()} fails with an
         * {@link IllegalArgumentException} when {@code clientAuth} is not {@link ClientAuth#NONE}
         * and {@code sslContext} is {@code null}.
         *
         * @throws NullPointerException if {@code clientAuth} is {@code null}
         */
        Builder tls(SSLContext sslContext, ClientAuth clientAuth);
```

In `ChappeServerBuilder.java`:

1. Add a field after `private SSLContext sslContext;`:

```java
    private ClientAuth clientAuth = ServerConfig.DEFAULT.clientAuth();
```

2. Replace the `tls(SSLContext)` override with:

```java
    @Override
    public Server.Builder tls(SSLContext sslContext, ClientAuth clientAuth) {
        this.sslContext = sslContext;
        this.clientAuth = Objects.requireNonNull(clientAuth, "clientAuth");
        return this;
    }
```

(Add the imports `java.util.Objects` and `io.vidocq.chappe.api.ClientAuth`. Do not override `tls(SSLContext)`: the interface default delegates here.)

3. In `build()`, insert `clientAuth,` immediately after `sslContext,` in the `new ServerConfig(...)` call.

- [ ] **Step 6: Apply the mode to each engine**

In `ChappeServer.handleTlsConnection`, replace

```java
        // Configure ALPN
        var sslParams = engine.getSSLParameters();
        sslParams.setApplicationProtocols(config.alpnProtocols().toArray(String[]::new));
        engine.setSSLParameters(sslParams);
```

with

```java
        // Configure ALPN and client authentication (per listener, decided at the handshake)
        var sslParams = engine.getSSLParameters();
        sslParams.setApplicationProtocols(config.alpnProtocols().toArray(String[]::new));
        switch (config.clientAuth()) {
            case NEED -> sslParams.setNeedClientAuth(true);
            case WANT -> sslParams.setWantClientAuth(true);
            case NONE -> sslParams.setNeedClientAuth(false); // clears "want" too
        }
        engine.setSSLParameters(sslParams);
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest='TlsClientAuthTest,ConnectionInfoTest,TlsTest,TlsLingeringCloseTest,ConnectionUpgradeTlsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, with `TlsClientAuthTest` running 14 parameterized cases and 4 plain tests.

If `needRejectsAClientWithoutCertificate` fails with an `HttpTimeoutException`, the server did not close after the failed handshake. Investigate `SslHandler.doHandshake` and `ChappeServer.handleConnection`; do not raise the timeout. Use `superpowers:systematic-debugging`, and add a BUG.md entry if it is a real server defect.

- [ ] **Step 8: Run the whole module suite**

Run: `./mvnw -ntp install`
Expected: BUILD SUCCESS. That covers every module test, `chappe-conformance`, and Spotless `check` in `verify`.

- [ ] **Step 9: Commit**

```bash
./mvnw -ntp spotless:apply
git add chappe-api/src/main/java/io/vidocq/chappe/api/ClientAuth.java \
        chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java \
        chappe-api/src/main/java/io/vidocq/chappe/api/Server.java \
        chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java \
        chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServer.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsClientAuthTest.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsFixtures.java
git commit -S -s -F - <<'EOF'
feat(tls): ask TLS clients for a certificate per listener

Server.Builder.tls(SSLContext, ClientAuth) sets the client
certificate mode of a TLS listener: NONE, WANT (optional) or NEED
(required). It is a new ServerConfig component, applied to the
SSLParameters of every engine, so the certificate is requested at the
handshake only: no renegotiation, which HTTP/2 forbids anyway. The
SSLContext's trust managers validate the certificate.

tls(SSLContext) keeps its meaning (NONE). A mode other than NONE
without an SSLContext fails at build() instead of serving cleartext.
The chain is read through Request.tls().

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 4: Handshake hardening — refuse TLS 1.2 renegotiation, send the rejection alert (CHAPPE-023)

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java` (`doHandshake()` ~100-158 including the line Task 2 appended, `readInternal` ~195-200, new private helpers)
- Modify: `BUG.md` (new top entry CHAPPE-023)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsHandshakeHardeningTest.java` (create)

**Interfaces:**
- Consumes: `TlsFixtures.selfSigned/certificate/trustStore/context` (Task 2), `ClientAuth` and `Server.Builder.tls(SSLContext, ClientAuth)` (Task 3), `SslHandler.tlsInfo` field (Task 2).
- Produces: no public API change. Behaviour: (R-1) an `SslHandler.read` that unwraps a handshake message after the initial handshake on a TLS 1.2 (or older) session throws `SSLException("TLS renegotiation refused ...")`, and the connection closes. (R-3) `SslHandler.doHandshake()`, failing with an `SSLException`, first sends the alert JSSE queued, each write bounded by the handler's write timeout, then rethrows.

Why this way:
- R-1. `TlsInfo` is read once, at the end of the handshake. Today a TLS 1.2 client could renegotiate, which the JDK server engine accepts by default and `SslHandler.handlePostUnwrap` carries through, and the session or client certificate would change under a `TlsInfo` that no longer describes it. TLS 1.3 has no renegotiation. Its post-handshake messages (KeyUpdate) must keep working, so the check is limited to sessions whose protocol is not `TLSv1.3`. The JVM-wide `jdk.tls.rejectClientInitiatedRenegotiation` property is not used (ruling R-1).
- R-3. JSSE queues a fatal alert in the engine when it rejects a handshake (for example `bad_certificate` under TLS 1.2, `certificate_required` under TLS 1.3, for `NEED` without a certificate). The `SSLEngine` contract says the application must `wrap()` to send it. `doHandshake` rethrew without wrapping, so the client saw a bare close ("Remote host terminated the handshake") and not the reason.

- [ ] **Step 1: Write the failing tests**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsHandshakeHardeningTest.java` (license header, then):

```java
package io.vidocq.chappe.tests;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.api.ClientAuth;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Handshake hardening of {@code SslHandler}, driven with a raw {@link SSLSocket} so the TLS
 * version is pinned and the client's own exceptions are visible:
 * <ul>
 *   <li>a TLS 1.2 client-initiated renegotiation is refused (the connection closes), so the
 *       {@code TlsInfo} read at the handshake cannot go stale (ruling R-1);</li>
 *   <li>a handshake the server rejects sends its TLS alert before the close, so the client
 *       learns why (CHAPPE-023, ruling R-3).</li>
 * </ul>
 */
class TlsHandshakeHardeningTest {

    private static SSLContext serverContext;
    private static SSLContext clientContext; // presents no certificate, trusts the server

    private final AtomicInteger handled = new AtomicInteger();
    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private void start(ClientAuth clientAuth) {
        server = Server.builder()
                .port(0)
                .tls(serverContext, clientAuth)
                .handler(_ -> {
                    handled.incrementAndGet();
                    return Response.ok("ok");
                })
                .build();
        server.start();
    }

    private SSLSocket connect(String protocol) throws IOException {
        var socket = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", server.port());
        socket.setSoTimeout(5000);
        var params = socket.getSSLParameters();
        params.setProtocols(new String[] {protocol});
        params.setApplicationProtocols(new String[] {"http/1.1"});
        socket.setSSLParameters(params);
        return socket;
    }

    /** One keep-alive GET; returns the response up to its {@code ok} body, or what was read before EOF. */
    private static String exchange(SSLSocket socket) throws IOException {
        var out = socket.getOutputStream();
        out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(US_ASCII));
        out.flush();
        var in = socket.getInputStream();
        var text = new StringBuilder();
        int b;
        while (!text.toString().endsWith("\r\n\r\nok") && (b = in.read()) != -1) {
            text.append((char) b);
        }
        return text.toString();
    }

    @Test
    void tls12KeepAliveWithoutRenegotiationStillWorks() throws Exception {
        start(ClientAuth.NONE);
        try (var socket = connect("TLSv1.2")) {
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"));
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"), "the renegotiation check must not fire on data");
        }
        assertEquals(2, handled.get());
    }

    @Test
    void tls12ClientInitiatedRenegotiationIsRefused() throws Exception {
        start(ClientAuth.NONE);
        try (var socket = connect("TLSv1.2")) {
            assertTrue(exchange(socket).startsWith("HTTP/1.1 200"));
            String second;
            try {
                socket.startHandshake(); // a new handshake on an established TLS 1.2 session = renegotiation
                second = exchange(socket);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the server must close the connection, not stall", e);
            } catch (IOException e) {
                second = "failed: " + e;
            }
            assertFalse(second.startsWith("HTTP/1.1 200"), "renegotiation must be refused, got: " + second);
        }
        assertEquals(1, handled.get(), "no request may be served after a renegotiation attempt");
    }

    @Test
    void tls12NeedRejectionSendsTheAlert() throws Exception {
        start(ClientAuth.NEED);
        try (var socket = connect("TLSv1.2")) {
            var failure = assertThrows(SSLHandshakeException.class, socket::startHandshake);
            assertTrue(
                    String.valueOf(failure.getMessage()).contains("Received fatal alert"),
                    "the client must get the server's alert, not a bare close: " + failure);
        }
        assertEquals(0, handled.get());
    }

    @Test
    void tls13NeedRejectionSendsTheAlert() throws Exception {
        start(ClientAuth.NEED);
        try (var socket = connect("TLSv1.3")) {
            // Under TLS 1.3 the client's side of the handshake completes before the server checks
            // the certificate, so the alert arrives on the first read.
            var failure = assertThrows(SSLException.class, () -> {
                socket.startHandshake();
                socket.getInputStream().read();
            });
            assertTrue(
                    String.valueOf(failure.getMessage()).contains("certificate_required"),
                    "the client must get the server's alert, not a bare close: " + failure);
        }
        assertEquals(0, handled.get());
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=TlsHandshakeHardeningTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `tls12KeepAliveWithoutRenegotiationStillWorks` passes. Three tests FAIL:
- `tls12ClientInitiatedRenegotiationIsRefused`: `renegotiation must be refused, got: HTTP/1.1 200 ...`, or the "must close, not stall" error if the current code hangs mid-renegotiation;
- `tls12NeedRejectionSendsTheAlert`: the message is `Remote host terminated the handshake`, without "Received fatal alert";
- `tls13NeedRejectionSendsTheAlert`: `read()` returns `-1` (nothing thrown), or the exception has no `certificate_required`.

If the renegotiation test passes before the fix, `startHandshake()` did not renegotiate on this JDK. Stop and report to the controller; do not weaken the test.

- [ ] **Step 3: Send the pending alert on a rejected handshake**

In `SslHandler.java`:

1. Add the import `javax.net.ssl.SSLException`.
2. Rename the current `public void doHandshake() throws IOException` to `private void runHandshake() throws IOException`, and delete from its end the line `tlsInfo = describe(engine.getSession());` that Task 2 added. The loop body is unchanged.
3. Add above it:

```java
    public void doHandshake() throws IOException {
        try {
            runHandshake();
        } catch (SSLException e) {
            // A rejected handshake (no or untrusted client certificate, no shared protocol...)
            // leaves JSSE's fatal alert in the engine: send it, so the client learns why
            // instead of seeing a bare close (CHAPPE-023). The caller then closes the socket.
            sendPendingAlert();
            throw e;
        }
        tlsInfo = describe(engine.getSession());
    }

    /** Best-effort: wraps and writes what the engine still has to send, each write bounded. */
    private void sendPendingAlert() {
        try {
            for (int i = 0; i < 4 && !engine.isOutboundDone(); i++) {
                netOutBuffer.clear();
                SSLEngineResult result = engine.wrap(EMPTY, netOutBuffer);
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    BoundedWrites.writeWithTimeout(channel, channel, netOutBuffer, closeWriteTimeout);
                }
                if (result.bytesProduced() == 0) {
                    break;
                }
            }
        } catch (IOException _) {
            // best-effort: the connection is closed by the caller anyway
        }
    }
```

The handshake runs on one thread before any concurrent read or write, so `sendPendingAlert` needs no lock, like the handshake loop itself.

- [ ] **Step 4: Refuse renegotiation in `readInternal`**

In `readInternal`, right after

```java
            netInBuffer.compact();
            appInBuffer.flip();
```

and before `// Post-handshake events (TLS 1.3 key updates)` / `handlePostUnwrap(result);`, insert:

```java
            // TLS 1.2 renegotiation would change the session (and client certificate) under the
            // TlsInfo read at the handshake: refuse it and let the caller close the connection.
            if (isRenegotiationAttempt(result)) {
                throw new SSLException("TLS renegotiation refused: the session is fixed at the handshake");
            }
```

and add the helper next to `handlePostUnwrap`:

```java
    /**
     * A handshake message unwrapped after the initial handshake of a TLS 1.2 (or older) session
     * is a renegotiation attempt. TLS 1.3 has no renegotiation, and its post-handshake messages
     * (KeyUpdate) stay allowed; a close_notify is not one either.
     */
    private boolean isRenegotiationAttempt(SSLEngineResult result) {
        if (result.getStatus() == Status.CLOSED) {
            return false;
        }
        HandshakeStatus hs = result.getHandshakeStatus();
        if (hs == HandshakeStatus.NOT_HANDSHAKING || hs == HandshakeStatus.FINISHED) {
            return false;
        }
        TlsInfo info = tlsInfo;
        return info != null && !"TLSv1.3".equals(info.protocol());
    }
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest='TlsHandshakeHardeningTest,TlsClientAuthTest,ConnectionInfoTest,TlsTest,TlsLingeringCloseTest,ConnectionUpgradeTlsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (4 tests in `TlsHandshakeHardeningTest`, nothing else regresses: TLS 1.3 keep-alive and lingering close still work).

- [ ] **Step 6: File the bug**

Insert at the top of `BUG.md`, above CHAPPE-022:

```markdown
## CHAPPE-023 — a TLS handshake the server rejected ended with a bare close, without its alert

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(tls): refuse TLS 1.2 renegotiation and send the handshake alert (CHAPPE-023)`,
  branch `pr/ybl/chappe-tls-client-auth`)
- **Module**: `chappe-http` (`SslHandler`)
- **Symptom**: when JSSE rejected a handshake (a client without a trusted certificate under
  `ClientAuth.NEED`, no common protocol or cipher suite), the client saw the connection close
  ("Remote host terminated the handshake", or EOF on its first read under TLS 1.3) instead of the
  alert that says why (`bad_certificate`, `certificate_required`, `handshake_failure`).
- **Minimal reproduction**: `chappe-tests/.../TlsHandshakeHardeningTest#tls12NeedRejectionSendsTheAlert`
  and `#tls13NeedRejectionSendsTheAlert`.
- **Cause**: `SslHandler.doHandshake()` rethrew the engine's `SSLException` without calling
  `wrap()`, which the `SSLEngine` contract requires to emit the alert the engine queued.
- **Fix**: on an `SSLException`, `doHandshake()` wraps and writes the pending alert (each write
  bounded by the write timeout), then rethrows; the server closes the socket as before. In the same
  commit, `SslHandler` refuses TLS 1.2 client-initiated renegotiation, so the `TlsInfo` read at the
  handshake cannot go stale.

---
```

- [ ] **Step 7: Commit**

```bash
./mvnw -ntp spotless:apply
git add chappe-http/src/main/java/io/vidocq/chappe/http/SslHandler.java \
        chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsHandshakeHardeningTest.java \
        BUG.md
git commit -S -s -F - <<'EOF'
fix(tls): refuse TLS 1.2 renegotiation and send the handshake alert (CHAPPE-023)

SslHandler read the TLS session once, at the handshake, but a TLS 1.2
client could renegotiate afterwards and change the session or its
certificate under the TlsInfo handlers see. A handshake message
unwrapped after the initial handshake of a TLS 1.2 session now fails
the read and closes the connection. TLS 1.3 post-handshake messages
(KeyUpdate) are unaffected.

When JSSE rejected a handshake (for example NEED without a trusted
client certificate), doHandshake rethrew without wrapping the alert the
engine queued, so the client saw a bare close. It now sends the alert,
each write bounded by the write timeout, before rethrowing.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 5: Documentation and cross-repository check

**Files:**
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (TLS section, after "Key points", before `== HTTP/2`)
- Modify: `docs/en/modules/ROOT/pages/reference.adoc` (`Server` builder list ~98; new sections after `=== \`RequestContext\``)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`
- Modify: `docs/en/modules/ROOT/pages/migration.adoc` (`== Upgrading from {release-version}`)
- Modify: `README.md` (TLS snippet ~154-161, connection metadata table ~264-276)

**Interfaces:**
- Consumes: the behaviour of Task 4 (renegotiation refused, alert sent) and the public API of Tasks 1-3, exactly: `ClientAuth`, `TlsInfo`, `Request.tls()`, `Server.Builder.tls(SSLContext, ClientAuth)`, `ServerConfig.clientAuth()`.
- Produces: anchors `usage.adoc#client-certificates` and `reference.adoc#connection-metadata`, which `whats-new.adoc` links to.

Docs have no automated test, so the check is a render and link review (Step 6).

- [ ] **Step 1: Usage page — client certificates**

In `usage.adoc`, after the "Key points" bullet list of `== TLS / HTTPS` and before `== HTTP/2`, insert:

````asciidoc
[#client-certificates]
=== Client certificates [.tag-new]#NEW#

A TLS listener can ask clients for a certificate. Pass a `ClientAuth` mode with the context; the context's trust managers decide which certificates are accepted, so build it with a `TrustManagerFactory` over the certificates or CAs you trust:

[source,java]
----
import java.security.KeyStore;
import javax.net.ssl.*;
import io.vidocq.chappe.api.*;

KeyStore serverKeys = ...;      // the server's key pair (PKCS12)
KeyStore trustedClients = ...;  // the client certificates or CAs you accept

var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(serverKeys, password);
var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustedClients);
var sslContext = SSLContext.getInstance("TLS");
sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

Server.builder()
        .port(8443)
        .tls(sslContext, ClientAuth.WANT)
        .handler(req -> req.tls()
                .filter(tls -> !tls.peerCertificates().isEmpty())
                .map(tls -> Response.ok("Hello " + tls.peerCertificates().getFirst().getSubjectX500Principal().getName()))
                .orElseGet(() -> Response.of(StatusCode.FORBIDDEN)))
        .build();
----

[cols="1,3"]
|===
| Mode | Behaviour

| `NONE` (default, and what `tls(sslContext)` means)
| No certificate is requested. `peerCertificates()` is empty.

| `WANT`
| A certificate is requested and optional. A client without one is accepted, with an empty `peerCertificates()`; the handler decides what to do. A client that sends a certificate the trust managers reject fails the TLS handshake, as with `NEED`: optional does not mean unchecked.

| `NEED`
| A certificate is required. A client that sends none, or one the trust managers reject, fails the TLS handshake: the client receives the TLS alert (for example `certificate_required`), the connection is closed and no handler runs.
|===

The mode is set per listener and applies at the TLS handshake, for HTTP/1.1 and for HTTP/2 alike. Chappe never asks for a certificate later in the connection: TLS 1.3 servers in the JDK cannot request one after the handshake, and HTTP/2 forbids TLS 1.2 renegotiation (RFC 9113 §9.2.1). A TLS 1.2 client that tries to renegotiate is disconnected, so the session a handler sees cannot change during the connection. If only part of an application needs client certificates, serve it from its own listener in `WANT` or `NEED` mode.

A `ClientAuth` other than `NONE` without an `SSLContext` fails at `build()` with an `IllegalArgumentException`.

[#request-tls]
=== Reading the TLS session in a handler [.tag-new]#NEW#

`Request.tls()` returns the `TlsInfo` of the connection, or `Optional.empty()` on cleartext. It is present exactly when `isSecure()` is `true`:

* `protocol()`: for example `TLSv1.3`.
* `cipherSuite()`: for example `TLS_AES_128_GCM_SHA256`.
* `sessionId()`: a copy of the session id bytes, empty when the session has none.
* `peerCertificates()`: the client's chain, its own certificate first, empty unless the listener asked for one and the client sent it.

Chappe reads the session once, when the handshake completes, and every request of the connection sees the same value: HTTP/1.1 keep-alive requests and all HTTP/2 streams.
````

- [ ] **Step 2: Reference page**

In `reference.adoc`, in the `Server` builder list, replace the line `* \`tls(SSLContext)\`` with:

```asciidoc
* `tls(SSLContext)` — TLS, no client certificate (`ClientAuth.NONE`)
* `tls(SSLContext, ClientAuth)` [.tag-new]#NEW# — TLS with a client certificate mode, see xref:usage.adoc#client-certificates[Usage — client certificates]
```

After the `=== \`RequestContext\`` section (before `== \`chappe serve\` CLI configuration`), insert:

````asciidoc
=== `ClientAuth` [.tag-new]#NEW#

[source,java]
----
public enum ClientAuth { NONE, WANT, NEED }
----

The client certificate mode of a TLS listener, set with `Server.Builder.tls(SSLContext, ClientAuth)` and read back as `ServerConfig.clientAuth()` (default `NONE`). See xref:usage.adoc#client-certificates[Usage — client certificates].

[cols="1,2,2,2"]
|===
| Mode | No certificate | Trusted certificate | Untrusted certificate

| `NONE` | accepted | not requested | not requested
| `WANT` | accepted, empty `peerCertificates()` | accepted, chain in `peerCertificates()` | handshake fails (JSSE), TLS alert sent
| `NEED` | handshake fails, TLS alert sent | accepted, chain in `peerCertificates()` | handshake fails, TLS alert sent
|===

The mode is applied at the handshake only. A TLS 1.2 client-initiated renegotiation is refused and the connection closed, so `Request.tls()` describes the session for the whole connection.

[#connection-metadata]
=== Connection metadata on `Request`

[cols="2,3"]
|===
| Method | Value

| `remoteAddress()` / `localAddress()`
| The client and server socket addresses, over cleartext and TLS, HTTP/1.1 and HTTP/2.

| `isSecure()`
| `true` on a TLS connection.

| `scheme()`
| `"https"` on a TLS connection, `"http"` otherwise.

| `tls()` [.tag-new]#NEW#
| `Optional<TlsInfo>`: the TLS session, empty on cleartext.
|===

[source,java]
----
public record TlsInfo(String protocol, String cipherSuite, byte[] sessionId,
                      List<X509Certificate> peerCertificates) { }
----

`TlsInfo` is immutable: the constructor copies `sessionId` and `peerCertificates`, `sessionId()` returns a copy, and `equals`/`hashCode` compare the session id by content. Wrappers built on `ForwardingRequest` forward `tls()`.
````

- [ ] **Step 3: What's new**

In `whats-new.adoc`, add as the first bullet under `== Fixes that change behaviour`:

```asciidoc
* **TLS and HTTP/2 requests report their connection** — over TLS, `Request.isSecure()` returned `false`, `scheme()` returned `"http"` and `remoteAddress()`/`localAddress()` returned `null`; HTTP/2 requests had no addresses over TLS or h2c. They now report the connection on every path, and `uri()` of an HTTP/2 request over TLS starts with `https://` (CHAPPE-022). xref:reference.adoc#connection-metadata[Reference — connection metadata].
```

and, as the next bullets in the same list:

```asciidoc
* **A rejected TLS handshake sends its alert** — when the TLS handshake failed (for example a client without a trusted certificate on a `NEED` listener), the client saw a bare close ("Remote host terminated the handshake"). It now receives the TLS alert that says why, such as `bad_certificate` or `certificate_required` (CHAPPE-023). xref:usage.adoc#client-certificates[Usage — client certificates].
* **TLS 1.2 renegotiation is refused** — a TLS 1.2 client that starts a new handshake on an established connection is disconnected, so the TLS session a handler reads through `Request.tls()` cannot change during the connection. TLS 1.3 has no renegotiation and is not affected. xref:reference.adoc#connection-metadata[Reference — connection metadata].
```

Then, if there is no `== New features` section yet (PR 1 may have added one), add it after the "Fixes that change behaviour" list. Add these bullets to it:

```asciidoc
== New features

* **TLS client certificates** — `Server.Builder.tls(SSLContext, ClientAuth)` asks clients for a certificate per listener: `WANT` (optional) or `NEED` (required). `tls(SSLContext)` keeps meaning `NONE`. xref:usage.adoc#client-certificates[Usage — client certificates].
* **`Request.tls()`** — the TLS session of the connection: protocol, cipher suite, session id and the client's certificate chain. xref:usage.adoc#request-tls[Usage — reading the TLS session].
```

Update the page's `:description:` line so it also mentions TLS client certificates.

- [ ] **Step 4: Migration**

In `migration.adoc`, under `== Upgrading from {release-version} [.tag-new]#NEW#`, add:

```asciidoc
* **`isSecure()` is now `true` over TLS** — up to {release-version}, a request received over TLS reported `isSecure() == false`, scheme `http` and no addresses, and HTTP/2 requests reported no addresses. Code that worked around this (for example by trusting `X-Forwarded-Proto` on a TLS listener) can drop the workaround. See xref:reference.adoc#connection-metadata[Reference — connection metadata].
* **TLS 1.2 renegotiation closes the connection** — a TLS 1.2 client that renegotiates on an established connection is now disconnected. Clients that renegotiate to refresh keys should use TLS 1.3 (KeyUpdate is unaffected) or open a new connection.
* **`ServerConfig` has a `clientAuth` component** — code that calls the canonical constructor `new ServerConfig(...)` directly must pass a `ClientAuth` right after the `SSLContext` (`ClientAuth.NONE` keeps the previous behaviour). Code that uses `Server.builder()` is not affected.
```

- [ ] **Step 5: README**

In `README.md`, in the `### TLS / HTTPS` snippet, add a second example below the existing block:

````markdown
Client certificates, per listener (`NONE` by default, `WANT` optional, `NEED` required):
```java
Server.builder()
    .port(8443)
    .tls(sslContext, ClientAuth.NEED)   // the SSLContext's trust managers validate the client
    .handler(req -> Response.ok(req.tls().orElseThrow().peerCertificates().getFirst()
            .getSubjectX500Principal().getName()))
    .build()
```
````

In the connection metadata table, after the `request.scheme()` row, add:

```markdown
| `request.tls()` | `Optional<TlsInfo>`: protocol, cipher suite, session id, client certificate chain |
```

- [ ] **Step 6: Review the docs**

Run: `git diff origin/main -- docs README.md BUG.md chappe-*/src | grep -n "J[P]MS"`. Expected: no output. The bracket keeps the forbidden abbreviation out of this plan; it matches the four letters.
Run: `grep -n "client-certificates\|request-tls\|connection-metadata" docs/en/modules/ROOT/pages/*.adoc`. Expected: each anchor is defined once, and every xref points to a defined anchor.
If the vidocq-docs site build is available locally (`/Users/yblazart/projects/perso/vidocq/vidocq-docs`), build it according to its README and check that the new sections render. If it is not, state in the PR description that the docs were reviewed as source only.

- [ ] **Step 7: Cross-repository check (no code change)**

Run from `/Users/yblazart/projects/perso/vidocq`:

```bash
grep -rnE "new ServerConfig\(|implements Server\.Builder|initConnectionInfo|new HttpConnection\(|new Http2Connection\(" \
  --include='*.java' cassini foy vidocq mansart | grep -v /target/
```

Expected: no hit. On 2026-10-11 the only `new ServerConfig(` sites were `ServerConfig.DEFAULT` and `ChappeServerBuilder`, and the only `Server.Builder` implementation was `ChappeServerBuilder`, so the change is additive for Cassini, Foy and Vidocq. If there is a hit, report it to the controller and do not edit the other repo. Put these follow-ups in the PR description, without implementing them:
  - Foy: CLIENT-CERT authenticator, §3.10 attributes from `Request.tls()` (key size derived in Foy), foy-tck HTTPS container (planned next).
  - Cassini: `ChappeRuntimeDelegate` copies `SeBootstrap` `SSL_CONTEXT` / `SSL_CLIENT_AUTHENTICATION` (lines ~93-94) but does not apply them; map `NONE/OPTIONAL/MANDATORY` to `ClientAuth.NONE/WANT/NEED`. `ChappeHttpExchange.remoteAddress()` (~214) returns `null` and could delegate to chappe now.
  - Vidocq: `ChappeListener.tls` (~38-40) is reserved and not implemented; it could expose TLS and client-auth keys.
  - Chappe: `SslHandler.doHandshake()` reads without a timeout, so a client that never finishes the handshake holds its virtual thread (same class as CHAPPE-005, found in the research, not fixed here).

- [ ] **Step 8: Commit**

```bash
git add docs/en/modules/ROOT/pages/usage.adoc docs/en/modules/ROOT/pages/reference.adoc \
        docs/en/modules/ROOT/pages/whats-new.adoc docs/en/modules/ROOT/pages/migration.adoc README.md
git commit -S -s -F - <<'EOF'
docs: document TLS client certificates and Request.tls()

Usage: the ClientAuth modes, why they are per listener (no
renegotiation), and reading the TLS session in a handler. Reference:
ClientAuth (including WANT with an untrusted certificate), TlsInfo and
the connection metadata of Request. What's new and migration:
isSecure() over TLS (CHAPPE-022), the handshake alert (CHAPPE-023),
TLS 1.2 renegotiation refused, and the new ServerConfig component.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

- [ ] **Step 9: Final verification**

Run: `./mvnw -ntp clean install`
Expected: BUILD SUCCESS on the whole reactor (tests, conformance, Spotless check).
Run: `git log --format='%h %s%n%(trailers)' origin/main..HEAD`
Expected: 5 commits, each with `Co-Authored-By`, `Claude-Session` and `Signed-off-by`. Each must also be GPG-signed (`git log --show-signature origin/main..HEAD` shows a good signature).

---

## Controller rulings folded into this plan

- **R-1** (TLS 1.2 client-initiated renegotiation refused in `SslHandler`, connection closed, no reliance on `jdk.tls.rejectClientInitiatedRenegotiation`): Task 4, `TlsHandshakeHardeningTest#tls12ClientInitiatedRenegotiationIsRefused`, with `#tls12KeepAliveWithoutRenegotiationStillWorks` as the guard against false positives. Documented in Task 5.
- **R-2** (`WANT` with an untrusted certificate: JSSE fails the handshake): Task 3, `TlsClientAuthTest#wantRejectsAnUntrustedClientCertificate` (h1 and h2), with `TlsFixtures.contextAlwaysOffering` so the certificate is really sent. Documented in the `ClientAuth` Javadoc (Task 3) and on the reference page (Task 5).
- **R-3** (alert sent before the close on a rejected handshake): Task 4, `TlsHandshakeHardeningTest#tls12NeedRejectionSendsTheAlert` (`SSLHandshakeException` "Received fatal alert") and `#tls13NeedRejectionSendsTheAlert` (`SSLException` with `certificate_required`, read after the client's side of the handshake completes). BUG.md CHAPPE-023.

