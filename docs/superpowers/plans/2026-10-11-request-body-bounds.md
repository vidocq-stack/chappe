# Chappe Request Body Bounds (PR 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Date:** 2026-10-11

**Goal:** Bound every request body read in chappe: HTTP/1.1 chunked bodies are capped at `maxRequestSize`, the post-handler drain gets a byte budget, `readTimeout` is applied to body reads, and HTTP/2 stops tearing the connection down on frames for streams it is done with, opens the stream window only as the handler reads, and caps the body at `maxRequestSize`.

**Architecture:** HTTP/1.1 changes stay in `chappe-http` (`ChunkedInputStream`, `HttpBodyReader`, `HttpConnection`, a new `TimedReadChannel`); one new `ServerConfig` component (`maxDrainSize`) goes through the record, `ServerConfig.DEFAULT`, `Server.Builder` and `ChappeServerBuilder`; one new public exception (`RequestBodyTooLargeException`) in `chappe-api`. HTTP/2 changes stay in `chappe-http/h2` (`Http2Connection`, `Http2Stream`, `Http2BodyInputStream`, a new `ResetStreams`). Every behaviour is pinned by a socket-level test in `chappe-tests` (raw sockets for HTTP/1.1, a raw-frame client for HTTP/2).

**Tech Stack:** Java 25 (Temurin), Maven 3.9.16, JUnit Jupiter (JUnit 6), Java Modules, virtual threads. No dependency added.

**Spec:** decisions `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/chappe-tls-decisions.md` (PR 1 part, rulings Q6 and Q7) and research `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/chappe-clientcert-research.md` (part B, section B.3). Foy context: `/Users/yblazart/projects/perso/vidocq/foy/BUG.md`, entry BUG-20261011-11.

**Repository and branch:** `/Users/yblazart/projects/perso/vidocq/chappe`. Base: `origin/main` @ `7cef196` (verified 2026-10-11; the local branch `pr/ybl/chappe-008-014` points at the same commit). The controller creates the work branch; this plan assumes `pr/ybl/chappe-request-body-bounds`. If the branch has another name, use it in the BUG.md `Status` lines.

**Bug ids:** chappe/BUG.md tops out at CHAPPE-019. This PR takes **CHAPPE-020 to CHAPPE-025**. The research proposed CHAPPE-020 for the PR 2 TLS bug: PR 2 must take CHAPPE-026 and later.

## Global Constraints

- Java 25 (Temurin, `java=25-tem`) and Maven 3.9.16 (`maven=3.9.16`), pinned in `chappe/.sdkmanrc`: run `sdk env` in `/Users/yblazart/projects/perso/vidocq/chappe` first. POMs stay `modelVersion 4.0.0`.
- Zero runtime dependencies outside the JDK. Tests use JUnit Jupiter only.
- Java Modules, strict: the new public type goes in the already exported package `io.vidocq.chappe.api`; no new `requires`, `exports` or `opens`. Write "Java Modules" in prose, never the abbreviation.
- Virtual threads only (`Thread.ofVirtual()`); no platform thread pool.
- English only: code, Javadoc, comments, test names, commit messages, BUG.md, Antora docs.
- TDD: write the failing test, run it red, implement, run it green. Integration tests use real sockets (`chappe-tests`), no HTTP mocking.
- Every new `ServerConfig` component goes through the record, `ServerConfig.DEFAULT`, `Server.Builder` and `ChappeServerBuilder`. `new ServerConfig(` call sites, workspace-wide (verified 2026-10-11): `chappe-api/.../ServerConfig.java:63` (`DEFAULT`) and `chappe-core/.../ChappeServerBuilder.java:127`. No test, and nothing in foy, cassini or vidocq, constructs a `ServerConfig`. The copies under `/Users/yblazart/projects/perso/vidocq/.worktrees/docs-chappe` belong to another branch: do not touch them.
- Formatting: spotless with palantir-java-format (PALANTIR style), import order `java,javax,jakarta,io.vidocq,`. Run `./mvnw -ntp spotless:apply` before each commit; `./mvnw -ntp install` runs `spotless:check` at `verify`.
- Every source file starts with the licence header block already used in the module (copy it from a neighbouring file, e.g. `chappe-http/src/main/java/io/vidocq/chappe/http/ReadBuffers.java:1-19`).
- Commits: signed, `git commit -S -s`, Conventional Commits with the CHAPPE id, English. The message ends with this trailer paragraph, two lines with no blank line between them (`-s` then appends `Signed-off-by`):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
  ```
- Every bug gets an entry at the top of `chappe/BUG.md` (newest first, after the `---` under the header): id, date, status, module, symptom, minimal reproduction, cause, fix. Performance numbers go to `chappe/BENCHMARKS.md` (chappe's name for the workspace `BENCH.md`) only if a task produces some; this plan produces none.
- Docs: Antora component `chappe/docs/en`, English only, `[.tag-new]#NEW#` badge on changed sections, no UI customisation. Pages touched: `reference.adoc`, `whats-new.adoc`, `migration.adoc`, `internals.adoc`.
- Do not modify Foy. Foy's BUG-20261011-11 is closed later, from Foy.
- Build commands (from `/Users/yblazart/projects/perso/vidocq/chappe`):
  - one test class: `./mvnw -ntp -pl chappe-tests -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
  - full build, all tests, spotless check: `./mvnw -ntp install`

## Review Focus

1. An unread HTTP/1.1 body of exactly `maxDrainSize` bytes keeps the connection; one byte more closes it (off-by-one in the drain budget). Test: Task 2, `aBodyLeftUnreadWithinTheBudgetIsDrainedAndTheConnectionKept` and `aBodyLeftUnreadOneBytePastTheBudgetClosesTheConnection`.
2. A chunk-size line that alone announces more than `maxRequestSize` fails at once, without waiting for chunk data that will never be read. Test: Task 1, `aChunkSizeOverTheLimitFailsBeforeItsDataArrives`.
3. An upload slower than `readTimeout` in total, but whose every gap is shorter, is read whole: the bound is per read, not per body. Test: Task 3, `aSlowUploadWhoseGapsStayUnderTheReadTimeoutIsReadWhole`.
4. Late frames on a reset or refused HTTP/2 stream that carry HPACK state (a trailer block with incremental indexing, the header block of a refused stream) keep the connection's header table in sync. Tests: Task 4, `aLateTrailerBlockOnTheResetStreamStillUpdatesTheHpackContext` and `aRefusedStreamKeepsTheConnectionAndTheHpackContext`.
5. Padded HTTP/2 DATA frames: padding is flow-controlled but never read, so a long padded upload must not stall the stream window. Test: Task 5, `aPaddedUploadLargerThanTheWindowCompletes`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `chappe-api/src/main/java/io/vidocq/chappe/api/RequestBodyTooLargeException.java` | create (T1) | Public `IOException` thrown by a body read past `maxRequestSize`, carries `limit()` |
| `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java` | modify (T2, T3, T5) | `maxDrainSize` component; `readTimeout` Javadoc |
| `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java` | modify (T2, T3, T5) | `Builder.maxDrainSize(long)`; `readTimeout` Javadoc |
| `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java` | modify (T2) | `maxDrainSize` field, setter with validation, `build()` |
| `chappe-http/src/main/java/io/vidocq/chappe/http/ChunkedInputStream.java` | modify (T1) | Count announced chunk sizes, fail past the cap |
| `chappe-http/src/main/java/io/vidocq/chappe/http/HttpBodyReader.java` | modify (T1, T2) | `chunked(..., maxBodySize)`; `drain(InputStream, long)` returning `boolean` |
| `chappe-http/src/main/java/io/vidocq/chappe/http/TimedReadChannel.java` | create (T3) | Body-read channel bounded by `readTimeout` |
| `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java` | modify (T1, T2, T3) | Pass the cap, bounded drain on every path, timed body channel |
| `chappe-http/src/main/java/io/vidocq/chappe/http/h2/ResetStreams.java` | create (T4) | Bounded memory of the stream ids this endpoint reset |
| `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` | modify (T4, T5, T6) | RST_STREAM(NO_ERROR), late-frame tolerance, consumption-driven window, size cap |
| `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Stream.java` | modify (T5, T6) | Window credit on consumption, read timeout, body limit, response phase |
| `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2BodyInputStream.java` | modify (T5, T6) | Credit consumed bytes, poll with timeout, fail an over-limit body |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/ChunkedBodyLimitTest.java` | create (T1) | Raw-socket tests, chunked cap |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/UnreadBodyDrainTest.java` | create (T2) | Raw-socket tests, drain budget |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/RequestBodyReadTimeoutTest.java` | create (T3) | Raw-socket tests, `readTimeout` |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsLingeringCloseTest.java` | modify (T3) | `readTimeout` over TLS |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/H2TestConnection.java` | create (T4) | Raw HTTP/2 frame client for tests (HPACK-decodes server HEADERS) |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2EarlyResponseTest.java` | create (T4) | RST_STREAM(NO_ERROR), late frames, refused streams |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestFlowControlTest.java` | create (T5) | Consumption-driven window, overrun, padding, read timeout |
| `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestBodyLimitTest.java` | create (T6) | 413 / RST_STREAM(CANCEL), gRPC exemption |
| `BUG.md` | modify (all) | CHAPPE-020 to CHAPPE-025 |
| `docs/en/modules/ROOT/pages/{reference,whats-new,migration,internals}.adoc` | modify (all) | New config, behaviours, upgrade notes |

The existing HTTP/2 test helpers in `Http2StreamAbortTest` and `Http2TrailersTest` are private and copied per class. This plan adds one shared package-private helper, `H2TestConnection`, for the three new HTTP/2 test classes. The existing classes stay as they are (no unrelated refactor).

---

### Task 1: Cap chunked request bodies at `maxRequestSize`, answer 413 to an oversized Content-Length (CHAPPE-020)

**Files:**
- Create: `chappe-api/src/main/java/io/vidocq/chappe/api/RequestBodyTooLargeException.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/ChunkedInputStream.java:46-60,142-192`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpBodyReader.java:25-32`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java:170-175,518-520,533-535,542-546`
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/ChunkedBodyLimitTest.java`
- Modify test: `chappe-conformance/src/test/java/io/vidocq/chappe/conformance/Http11StatusCodesTest.java:80-94` (`payloadTooLarge413` accepted any status >= 400)
- Modify: `BUG.md`, `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`, `migration.adoc`

**Interfaces:**
- Consumes: `ServerConfig.maxRequestSize()` (existing, `long`, default 10 MiB).
- Produces: `public final class io.vidocq.chappe.api.RequestBodyTooLargeException extends java.io.IOException` with `public RequestBodyTooLargeException(long limit)` and `public long limit()`; `static InputStream HttpBodyReader.chunked(ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer, long maxBodySize)`; package-private test helpers in `ChunkedBodyLimitTest`, reused by Tasks 2 and 3: `static String chunk(int size)`, `static String readToEof(InputStream in)`, `static String head(String response)`.

- [ ] **Step 1: Write the failing test**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/ChunkedBodyLimitTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.RequestBodyTooLargeException;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-020: a chunked request body is capped at {@code maxRequestSize}, as a Content-Length one
 * is. The chunk that would take the body past the cap fails the read before its data is read, and
 * the connection is closed after the response: no other request is read from it.
 */
class ChunkedBodyLimitTest {

    private static final long LIMIT = 1024;

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private Server server;
    private int port;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).maxRequestSize(LIMIT).handler(this::handle).build();
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** {@code /read} reads the whole body and reports how the read ended; anything else answers {@code pong}. */
    private Response handle(Request request) {
        if (!"/read".equals(request.path())) return Response.ok("pong");
        try {
            byte[] body = request.body().asInputStream().readAllBytes();
            outcome.complete("read:" + body.length);
            return Response.ok("read:" + body.length);
        } catch (RequestBodyTooLargeException e) {
            outcome.complete("too large:" + e.limit());
            return Response.ok("too large:" + e.limit());
        } catch (IOException e) {
            outcome.complete("failed:" + e.getClass().getSimpleName());
            return Response.ok("failed");
        }
    }

    @Test
    void aChunkedBodyOverTheLimitFailsTheReadAndClosesTheConnection() throws Exception {
        String request = "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + chunk(400) + chunk(400) + chunk(400) + "0\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

        String response = sendThenRead(request);

        assertEquals("too large:1024", outcome.get(5, TimeUnit.SECONDS));
        assertTrue(response.startsWith("HTTP/1.1 200"), head(response));
        assertTrue(response.endsWith("too large:1024"), "one response, then the close: " + head(response));
        assertFalse(response.contains("pong"), "no request may be read after an over-limit body");
    }

    @Test
    void aChunkSizeOverTheLimitFailsBeforeItsDataArrives() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n100000\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // No chunk data is ever sent: the read must fail on the size line alone.
            assertEquals("too large:1024", outcome.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void aChunkedBodyAtTheLimitIsReadWholeAndKeepsTheConnection() throws Exception {
        String request = "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + chunk(1000) + chunk(24) + "0\r\n\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

        String response = sendThenRead(request);

        assertEquals("read:1024", outcome.get(5, TimeUnit.SECONDS));
        assertTrue(response.contains("read:1024"), head(response));
        assertTrue(response.endsWith("pong"), "the keep-alive request must be answered: " + head(response));
    }

    /** RFC 9110 §15.5.14: a Content-Length above the cap is answered 413, without running the handler. */
    @Test
    void aContentLengthOverTheLimitIsAnswered413BeforeTheHandlerRuns() throws Exception {
        String response = sendThenRead(
                "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1025\r\n\r\n" + "x".repeat(1025));

        assertTrue(response.startsWith("HTTP/1.1 413"), head(response));
        assertFalse(outcome.isDone(), "the handler must not run");
    }

    /** One chunk of {@code size} bytes, framed (RFC 9112 §7.1). */
    static String chunk(int size) {
        return Integer.toHexString(size) + "\r\n" + "x".repeat(size) + "\r\n";
    }

    private String sendThenRead(String request) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readToEof(socket.getInputStream());
        }
    }

    /** Reads to the end of the connection; a reset ends it too, the caller judges what arrived. */
    static String readToEof(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        } catch (SocketException reset) {
            // a reset ends the connection too
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    static String head(String response) {
        return response.length() <= 200 ? response : response.substring(0, 200) + "…";
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=ChunkedBodyLimitTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: class RequestBodyTooLargeException`.

- [ ] **Step 3: Add the exception type**

Create `chappe-api/src/main/java/io/vidocq/chappe/api/RequestBodyTooLargeException.java` (licence header first):

```java
package io.vidocq.chappe.api;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown by a request body read that would go past the server's {@link ServerConfig#maxRequestSize()
 * maxRequestSize}. A handler can tell it from a cut or malformed body and answer {@code 413}. The
 * body is unusable afterwards: over HTTP/1.1 the connection is closed after the response, over
 * HTTP/2 the stream is reset.
 */
public final class RequestBodyTooLargeException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long limit;

    /** @param limit the cap the body went past, in bytes */
    public RequestBodyTooLargeException(long limit) {
        super("Request body exceeds the limit of " + limit + " bytes");
        this.limit = limit;
    }

    /** The cap the body went past, in bytes. */
    public long limit() {
        return limit;
    }
}
```

- [ ] **Step 4: Run the test to verify it fails on behaviour**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=ChunkedBodyLimitTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `aChunkedBodyOverTheLimitFailsTheReadAndClosesTheConnection` FAILS (the handler reads `read:1200`, then the pipelined GET is answered and the read hits the 5 s socket timeout); `aChunkSizeOverTheLimitFailsBeforeItsDataArrives` FAILS with a `TimeoutException` (the handler waits for data); `aContentLengthOverTheLimitIsAnswered413BeforeTheHandlerRuns` FAILS (the response starts with `HTTP/1.1 400`). `aChunkedBodyAtTheLimitIsReadWholeAndKeepsTheConnection` passes: it guards the boundary.

Also tighten the conformance test. In `chappe-conformance/.../Http11StatusCodesTest.java`, `payloadTooLarge413`, replace

```java
        // Server should reject with 413 Content Too Large or similar error
        assertTrue(
                status == 413 || status == 404 || status >= 400, "Oversized payload should be handled, got: " + status);
```

with

```java
        // RFC 9110 §15.5.14: 413 Content Too Large
        assertEquals(413, status, "Oversized payload must be answered 413");
```

(add `import static org.junit.jupiter.api.Assertions.assertEquals;` if the class does not import `Assertions.*`). Run `./mvnw -ntp -pl chappe-conformance -am test -Dtest=Http11StatusCodesTest -Dsurefire.failIfNoSpecifiedTests=false`: `payloadTooLarge413` FAILS with `expected: <413> but was: <400>`.

- [ ] **Step 5: Count chunk sizes in `ChunkedInputStream`**

In `ChunkedInputStream.java` add the import `import io.vidocq.chappe.api.RequestBodyTooLargeException;` (after `import io.vidocq.chappe.api.Headers;`). Replace the fields and constructors (lines 46-60):

```java
    private final ByteBuffer buffer;
    private final ReadableByteChannel channel;
    private final Consumer<Headers> trailersConsumer;
    private final long maxBodySize;
    private ChunkState chunkState = ChunkState.READ_SIZE;
    private long chunkRemaining;
    /** Sum of the chunk sizes announced so far (CHAPPE-020). */
    private long announcedBytes;

    ChunkedInputStream(ByteBuffer buffer, ReadableByteChannel channel) {
        this(buffer, channel, null, Long.MAX_VALUE);
    }

    ChunkedInputStream(
            ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer, long maxBodySize) {
        this.buffer = buffer;
        this.channel = channel;
        this.trailersConsumer = trailersConsumer;
        this.maxBodySize = maxBodySize;
    }
```

In `readChunkSize()`, both branches that set `chunkState = ChunkState.READ_DATA;` (the `'\n'` branch and the `';'` branch) become:

```java
                } else {
                    countChunk(size);
                    chunkState = ChunkState.READ_DATA;
                }
```

Add the method after `readChunkSize()`:

```java
    /**
     * Counts a chunk against {@code maxBodySize} as soon as its size line is read: a chunk that would
     * take the body past the cap fails before any of its data is read (CHAPPE-020). The stream is then
     * {@link #failed()}, so the connection closes instead of draining it.
     */
    private void countChunk(long size) throws RequestBodyTooLargeException {
        if (size > maxBodySize - announcedBytes) throw new RequestBodyTooLargeException(maxBodySize);
        announcedBytes += size;
    }
```

- [ ] **Step 6: Pass the cap through `HttpBodyReader` and `HttpConnection`**

`HttpBodyReader.java`, replace `chunked` (lines 25-32):

```java
    /**
     * Creates an InputStream for a chunked-transfer-encoded body. Trailer
     * fields after the terminal chunk are delivered to {@code trailersConsumer}
     * (RFC 9112 §7.1.2). A body announcing more than {@code maxBodySize} bytes
     * fails the read with {@link io.vidocq.chappe.api.RequestBodyTooLargeException}.
     */
    static InputStream chunked(
            ByteBuffer buffer, ReadableByteChannel channel, Consumer<Headers> trailersConsumer, long maxBodySize) {
        return new ChunkedInputStream(buffer, channel, trailersConsumer, maxBodySize);
    }
```

`HttpConnection.setupBody()`, line 519:

```java
            return HttpBodyReader.chunked(readBuffer, readChannel, request::setTrailers, config.maxRequestSize());
```

- [ ] **Step 6b: Answer 413 to a Content-Length above the cap**

`HttpConnection.java`, replace `BadBodyException` (lines 542-546) with:

```java
    private static final class BadBodyException extends Exception {
        private final StatusCode status;

        BadBodyException(String message) {
            this(StatusCode.BAD_REQUEST, message);
        }

        BadBodyException(StatusCode status, String message) {
            super(message);
            this.status = status;
        }

        StatusCode status() {
            return status;
        }
    }
```

In `setupBody()` (lines 533-535):

```java
            if (cl > config.maxRequestSize()) {
                // RFC 9110 §15.5.14: the body is refused for its size, not for its framing.
                throw new BadBodyException(
                        StatusCode.PAYLOAD_TOO_LARGE,
                        "Content-Length " + cl + " exceeds max " + config.maxRequestSize());
            }
```

In `run()`, step 4 (line 173), `sendError(StatusCode.BAD_REQUEST, e.getMessage());` becomes:

```java
                    sendError(e.status(), e.getMessage());
```

(`sendError` sets `lingerOnClose`: the 413 survives the unread body, as the 400 did.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl chappe-tests,chappe-conformance -am test -Dtest=ChunkedBodyLimitTest+Http11StatusCodesTest+FixedLengthRequestBodyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 8: Record CHAPPE-020 in BUG.md**

Insert at the top of `BUG.md`, right after the first `---` line (above CHAPPE-019):

```markdown
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
- **Fix**: `ChunkedInputStream` counts the chunk sizes it reads. A chunk that would take the body past
  `maxRequestSize` fails the read with the new `RequestBodyTooLargeException` (an `IOException`
  carrying `limit()`), before any of its data is read. The body stream is then `failed()`, so the
  connection closes with a lingering close and reads no other request (CHAPPE-013, CHAPPE-014). A
  `Content-Length` above `maxRequestSize` is now answered `413` instead of `400`
  (`ChunkedBodyLimitTest#aContentLengthOverTheLimitIsAnswered413BeforeTheHandlerRuns`,
  `Http11StatusCodesTest#payloadTooLarge413`).

---
```

- [ ] **Step 9: Document the cap**

`docs/en/modules/ROOT/pages/reference.adoc`: replace the line

```
=== `Router`
```

with

```
[#request-body-limits]
==== Request body limits [.tag-new]#NEW#

* `maxRequestSize` (default 10 MiB) caps a request body. Over HTTP/1.1, a `Content-Length` above it is answered `413 Content Too Large` (RFC 9110 §15.5.14) before the handler runs, and the connection is closed. A chunked body is counted as it is decoded: the chunk that would take it past the cap fails the read with `RequestBodyTooLargeException`, an `IOException` whose `limit()` is the cap, before any of that chunk's data is read. The handler can still answer (a `413` is the usual choice); Chappe then closes the connection gracefully and reads no other request from it.

=== `Router`
```

`docs/en/modules/ROOT/pages/whats-new.adoc`: append as the last bullet of the `== Fixes that change behaviour` list (the end of the file):

```
* **Chunked request bodies are capped** — `maxRequestSize` (10 MiB by default) used to bound only a `Content-Length` body; a chunked body was decoded to its end whatever its size. Reading past the cap now fails with `RequestBodyTooLargeException`, and the connection is closed after the response. A `Content-Length` above the cap is now answered `413` instead of `400` (CHAPPE-020). xref:reference.adoc#request-body-limits[Reference — request body limits].
```

`docs/en/modules/ROOT/pages/migration.adoc`: append as the last bullets of the `== Upgrading from {release-version}` list (after the bullet that ends with `[Concepts — header and trailer validation].`, before the blank line and `== Overview`):

```
* **An oversized `Content-Length` now gets `413`, not `400`** — a request whose `Content-Length` is above `maxRequestSize` is answered `413 Content Too Large` (RFC 9110 §15.5.14) instead of `400 Bad Request`, still before the handler runs. If a client or a test of yours checks for the `400`, update it. See xref:reference.adoc#request-body-limits[Reference — request body limits].
* **Chunked request bodies over `maxRequestSize` now fail** — reading a chunked body past `maxRequestSize` (10 MiB by default) throws `RequestBodyTooLargeException`, and the connection closes after the response. If your service accepts larger chunked uploads, raise `maxRequestSize`. See xref:reference.adoc#request-body-limits[Reference — request body limits].
```

- [ ] **Step 10: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS, all modules' tests green.

- [ ] **Step 11: Commit**

```bash
git add chappe-api/src/main/java/io/vidocq/chappe/api/RequestBodyTooLargeException.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/ChunkedInputStream.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/HttpBodyReader.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/ChunkedBodyLimitTest.java \
  chappe-conformance/src/test/java/io/vidocq/chappe/conformance/Http11StatusCodesTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc \
  docs/en/modules/ROOT/pages/migration.adoc
git commit -S -s -F - <<'EOF'
fix(http): cap chunked request bodies at maxRequestSize (CHAPPE-020)

maxRequestSize bounded only Content-Length bodies: a chunked body was
decoded to its end whatever its size, by the handler or by the drain
before the next request.

ChunkedInputStream now counts the chunk sizes it reads. The chunk that
would take the body past maxRequestSize fails the read with the new
RequestBodyTooLargeException (chappe-api, an IOException carrying the
limit) before any of its data is read. The failed body closes the
connection with a lingering close, as any failed body read does.

A Content-Length above maxRequestSize is now answered 413 Content Too
Large (RFC 9110 15.5.14) instead of 400.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 2: Bound the drain of an unread request body (CHAPPE-021)

**Files:**
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java:27-74`
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java` (`Builder`, after `maxHeaderSize(int)`)
- Modify: `chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java:37-48,98-102,127-138`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpBodyReader.java:34-44`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java:212-247,281-290,430-457`
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/UnreadBodyDrainTest.java`
- Modify: `BUG.md`, `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`, `migration.adoc`, `internals.adoc`

**Interfaces:**
- Consumes: `ChunkedBodyLimitTest.chunk(int)`, `ChunkedBodyLimitTest.readToEof(InputStream)`, `ChunkedBodyLimitTest.head(String)` (Task 1).
- Produces: `ServerConfig.maxDrainSize()` (`long`, record component placed right after `maxHeaderSize`, default `1024L * 1024`); `Server.Builder maxDrainSize(long bytes)` (throws `IllegalArgumentException` for a negative value); `static boolean HttpBodyReader.drain(InputStream bodyStream, long maxBytes) throws IOException` (the one-argument `drain` is removed); `private boolean HttpConnection.drained(InputStream)`; `private void HttpConnection.runUpgrade(ConnectionUpgrade upgrade)` (body parameter removed).

- [ ] **Step 1: Write the failing test**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/UnreadBodyDrainTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import io.vidocq.chappe.api.ConnectionUpgrade;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.ServerConfig;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-021: a body the handler left unread is drained for keep-alive within {@code maxDrainSize};
 * past it the connection is closed gracefully instead (the Foy BUG-20261011-11 scenario).
 */
class UnreadBodyDrainTest {

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** A server whose handler never reads the body and answers {@code pong}. */
    private int start(long maxDrainSize) {
        server = Server.builder()
                .port(0)
                .maxDrainSize(maxDrainSize)
                .maxRequestSize(Long.MAX_VALUE) // isolate the drain budget from the CHAPPE-020 cap
                .handler(_ -> Response.ok("pong"))
                .build();
        server.start();
        return server.port();
    }

    @Test
    void aBodyLeftUnreadWithinTheBudgetIsDrainedAndTheConnectionKept() throws Exception {
        int port = start(100);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)
                        + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertEquals(2, count(response, "pong"), ChunkedBodyLimitTest.head(response));
    }

    @Test
    void aBodyLeftUnreadOneBytePastTheBudgetClosesTheConnection() throws Exception {
        int port = start(100);
        String response = sendThenRead(
                port,
                "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 101\r\n\r\n" + "x".repeat(101)
                        + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertEquals(1, count(response, "pong"), "the pipelined request must not be read: " + response);
    }

    @Test
    void anEndlessChunkedUploadIgnoredByTheHandlerIsCutOff() throws Exception {
        int port = start(64 * 1024);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread sender = Thread.ofVirtual().start(() -> {
                byte[] chunk = ChunkedBodyLimitTest.chunk(4096).getBytes(StandardCharsets.US_ASCII);
                long end = System.nanoTime() + 15_000_000_000L;
                try {
                    while (System.nanoTime() < end) {
                        out.write(chunk);
                        out.flush();
                    }
                } catch (IOException _) {
                    // the server closed the connection: expected
                }
            });
            long start = System.nanoTime();
            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(response.startsWith("HTTP/1.1 200"), ChunkedBodyLimitTest.head(response));
            assertTrue(response.endsWith("pong"), ChunkedBodyLimitTest.head(response));
            assertTrue(elapsedMs < 8_000, "the drain must stop at its budget, took " + elapsedMs + " ms");
            sender.interrupt();
        }
    }

    @Test
    void aConnectionUpgradeWithABodyPastTheBudgetIsRefused() throws Exception {
        var upgraded = new CountDownLatch(1);
        server = Server.builder()
                .port(0)
                .maxDrainSize(16)
                .handler(_ -> new ConnectionUpgrade(
                        StatusCode.SWITCHING_PROTOCOLS, Headers.of("Upgrade", "echo"), conn -> {
                            upgraded.countDown();
                            conn.close();
                        }))
                .build();
        server.start();

        String response = sendThenRead(
                server.port(),
                "POST /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\nContent-Length: 1024\r\n\r\n"
                        + "x".repeat(1024));

        assertFalse(response.contains("101"), response);
        assertEquals(1, upgraded.getCount(), "the connection must not be handed over");
    }

    @Test
    void theDrainBudgetDefaultsToOneMebibyteAndRefusesANegativeValue() {
        assertEquals(1024L * 1024, ServerConfig.DEFAULT.maxDrainSize());
        assertThrows(IllegalArgumentException.class, () -> Server.builder().maxDrainSize(-1));
    }

    private static String sendThenRead(int port, String request) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return ChunkedBodyLimitTest.readToEof(socket.getInputStream());
        }
    }

    private static int count(String text, String word) {
        return text.split(word, -1).length - 1;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=UnreadBodyDrainTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: method maxDrainSize(int)` / `maxDrainSize()`.

- [ ] **Step 3: Add `maxDrainSize` to `ServerConfig`**

In `ServerConfig.java`, add the Javadoc line after `@param maxHeaderSize ...`:

```java
 * @param maxDrainSize most bytes of a request body the handler left unread that the server reads
 *     and discards to keep an HTTP/1.1 connection (default: 1 MiB); past it the connection is
 *     closed instead, and an upgrade is refused
```

Record components (insert after `int maxHeaderSize,`):

```java
        long maxRequestSize,
        int maxHeaderSize,
        long maxDrainSize,
        SSLContext sslContext,
```

`DEFAULT` (insert after `8192,`):

```java
            10L * 1024 * 1024,
            8192,
            1024L * 1024,
            null,
```

- [ ] **Step 4: Add the builder method**

`Server.java`, in `interface Builder`, right after `Builder maxHeaderSize(int bytes);`:

```java
        /**
         * Most bytes of a request body the handler left unread that the server reads and discards
         * to keep an HTTP/1.1 connection (default: 1 MiB). Past it the connection is closed instead.
         * {@code 0} closes the connection whenever a body was left unread.
         *
         * @throws IllegalArgumentException if {@code bytes} is negative
         */
        Builder maxDrainSize(long bytes);
```

`ChappeServerBuilder.java`: field after `maxHeaderSize`:

```java
    private long maxDrainSize = ServerConfig.DEFAULT.maxDrainSize();
```

method after `maxHeaderSize(int)`:

```java
    @Override
    public Server.Builder maxDrainSize(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("maxDrainSize must be >= 0, got " + bytes);
        this.maxDrainSize = bytes;
        return this;
    }
```

`build()`: insert `maxDrainSize,` after `maxHeaderSize,` in the `new ServerConfig(...)` call.

Check that no other construction exists: `grep -rn "new ServerConfig(" --include='*.java' /Users/yblazart/projects/perso/vidocq/chappe | grep -v /target/` must list exactly `ServerConfig.java` and `ChappeServerBuilder.java`.

- [ ] **Step 5: Bound `HttpBodyReader.drain`**

Replace `drain(InputStream)` (lines 34-44) with:

```java
    /**
     * Reads and discards what is left of a request body, at most {@code maxBytes} bytes: a handler
     * that did not read its body leaves the rest on the connection, ahead of the next request.
     *
     * @return {@code true} when the body ended within the budget; {@code false} when more than
     *     {@code maxBytes} bytes were left, and the caller closes the connection instead (CHAPPE-021)
     */
    static boolean drain(InputStream bodyStream, long maxBytes) throws IOException {
        byte[] buf = new byte[4096];
        long left = maxBytes;
        while (true) {
            // One byte past the budget tells a body longer than the budget from one that ends at it.
            int len = left >= buf.length ? buf.length : (int) left + 1;
            int n = bodyStream.read(buf, 0, len);
            if (n == -1) return true;
            left -= n;
            if (left < 0) return false;
        }
    }
```

- [ ] **Step 6: Use the bounded drain on every `HttpConnection` path**

Replace the upgrade guard (lines 212-221) with:

```java
                    // An upgrade drains the request body before taking the connection over. After a
                    // failed body read (or a closed read channel) the byte stream position is unknown,
                    // and a body longer than maxDrainSize is not worth reading: close the connection,
                    // no upgrade head (CHAPPE-013, CHAPPE-021), gracefully while the channel is still
                    // open (CHAPPE-014).
                    if ((response instanceof WebSocketUpgrade || response instanceof ConnectionUpgrade)
                            && bodyStream != null
                            && (bodyReadFailed(bodyStream) || !readChannel.isOpen() || !drained(bodyStream))) {
                        lingerOnClose = readChannel.isOpen();
                        break;
                    }
```

In the WebSocket branch (line 225), delete the line `if (bodyStream != null) HttpBodyReader.drain(bodyStream);` (the guard above drained it).

In the `ConnectionUpgrade` branch (line 245), call `runUpgrade(upgrade);`. In `runUpgrade` (lines 430-457), change the signature to `private void runUpgrade(ConnectionUpgrade upgrade) throws IOException`, delete its first line `if (bodyStream != null) HttpBodyReader.drain(bodyStream);`, and change its Javadoc first sentence to: `Takes the connection over for a {@link ConnectionUpgrade} (the request body is already drained): writes the status line and the application's headers, then calls {@link UpgradeHandler#onUpgrade} on this thread.`

Replace step 6 (lines 281-290) with:

```java
                // 6. Drain the unread body (for keep-alive), within maxDrainSize. A drain that fails
                // (bad framing, a body cut short) is a failed body read too, and a body longer than
                // the budget is not worth reading (CHAPPE-021): same graceful close, no next request.
                if (bodyStream != null && !drained(bodyStream)) {
                    lingerOnClose = readChannel.isOpen();
                    break;
                }
```

Add the helper after `bodyReadFailed(...)`:

```java
    /**
     * Drains what the handler left of the request body, within {@code config.maxDrainSize()}
     * (CHAPPE-021). {@code false} when the budget ran out or the read failed: the connection must
     * then close instead of reading a next request or switching protocols.
     */
    private boolean drained(InputStream bodyStream) {
        try {
            return HttpBodyReader.drain(bodyStream, config.maxDrainSize());
        } catch (IOException _) {
            return false;
        }
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=UnreadBodyDrainTest+ChunkedBodyLimitTest+LingeringCloseTest+UpgradeAfterFailedBodyReadTest+ConnectionUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS. (Before Step 6, `anEndlessChunkedUploadIgnoredByTheHandlerIsCutOff` failed with a `SocketTimeoutException`, `aBodyLeftUnreadOneBytePastTheBudgetClosesTheConnection` with 2 `pong`, and the upgrade test with a `101`.)

- [ ] **Step 8: Record CHAPPE-021 in BUG.md**

Insert at the top of `BUG.md` (above CHAPPE-020):

```markdown
## CHAPPE-021 — the drain of an unread request body has no bound

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): bound the drain of an unread request body (CHAPPE-021)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`HttpBodyReader`, `HttpConnection`), `chappe-api` (`ServerConfig`,
  `Server.Builder`), `chappe-core` (`ChappeServerBuilder`)
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
  the same way, without its `101`.

---
```

- [ ] **Step 9: Document the budget**

`reference.adoc`: replace the builder line

```
* `maxRequestSize(long)` / `maxHeaderSize(int)`
```

with

```
* `maxRequestSize(long)` / `maxHeaderSize(int)` / `maxDrainSize(long)` [.tag-new]#NEW#
```

and, in the `[#request-body-limits]` section, insert after the `maxRequestSize` bullet (the one ending with `reads no other request from it.`):

```
* `maxDrainSize` (default 1 MiB) bounds what Chappe reads of a body the handler left unread. Over HTTP/1.1, the rest of the body sits between the response and the next request, so Chappe reads and discards it to keep the connection. When more than `maxDrainSize` bytes are left, it closes the connection gracefully instead, after the response; a `WebSocketUpgrade` or `ConnectionUpgrade` answer is refused the same way, and the connection closes without the `101`. `0` closes the connection whenever a body was left unread. A negative value is refused with `IllegalArgumentException`.
```

`whats-new.adoc`: append as the last bullet of the list:

```
* **An unread body is drained within a budget** — when a handler did not read its request body, Chappe read the rest before the next request with no bound, so a client that kept uploading held the connection as long as it liked. It now reads at most `maxDrainSize` (1 MiB by default, new `Server.Builder.maxDrainSize(long)`) and closes the connection past it (CHAPPE-021). xref:reference.adoc#request-body-limits[Reference — request body limits].
```

`migration.adoc`: append as the last bullet of the `Upgrading` list:

```
* **Unread bodies over 1 MiB close the connection** — a handler that answers without reading a body larger than `maxDrainSize` (1 MiB) now ends the keep-alive connection instead of having Chappe read the whole body. Clients reconnect for their next request. Raise `maxDrainSize` to keep such connections. See xref:reference.adoc#request-body-limits[Reference — request body limits].
```

`internals.adoc` (the `* **Lazy body**` bullet of the threading-model section): replace

```
If a handler returns without consuming, the body is drained cleanly before the next request (HTTP/1.1 keep-alive) or the stream is reset (HTTP/2).
```

with

```
If a handler returns without consuming, the body is drained before the next request (HTTP/1.1 keep-alive), up to `maxDrainSize` (1 MiB by default); a longer body closes the connection instead, gracefully. Over HTTP/2 the stream is reset.
```

- [ ] **Step 10: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java \
  chappe-api/src/main/java/io/vidocq/chappe/api/Server.java \
  chappe-core/src/main/java/io/vidocq/chappe/core/ChappeServerBuilder.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/HttpBodyReader.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/UnreadBodyDrainTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc \
  docs/en/modules/ROOT/pages/migration.adoc docs/en/modules/ROOT/pages/internals.adoc
git commit -S -s -F - <<'EOF'
fix(http): bound the drain of an unread request body (CHAPPE-021)

A body the handler left unread was drained to its end before the next
request or an upgrade: a client that kept uploading held the connection
and its virtual thread for as long as it sent.

New ServerConfig.maxDrainSize component (Server.Builder.maxDrainSize,
default 1 MiB, the lingering-close budget). Past it the connection is
closed gracefully after the response, and an upgrade is refused
without its 101.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 3: Apply `readTimeout` to HTTP/1.1 request body reads (CHAPPE-022)

**Files:**
- Create: `chappe-http/src/main/java/io/vidocq/chappe/http/TimedReadChannel.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java:47-58,80-110,512-540`
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java:33` (Javadoc)
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/Server.java` (`Builder.readTimeout` Javadoc)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/RequestBodyReadTimeoutTest.java`
- Modify test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsLingeringCloseTest.java`
- Modify: `BUG.md`, `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`, `migration.adoc`

**Interfaces:**
- Consumes: `BoundedReads.readWithTimeout(ReadableByteChannel, Closeable, ByteBuffer, Duration)` (existing, `chappe-http/.../BoundedReads.java:33`); `ChunkedBodyLimitTest.readToEof`, `ChunkedBodyLimitTest.head` (Task 1).
- Produces: `static ReadableByteChannel TimedReadChannel.of(ReadableByteChannel delegate, Closeable closeable, Duration timeout)` (returns `delegate` itself when `timeout` is `null`, zero or negative); a timed-out read throws `java.net.SocketTimeoutException`.

- [ ] **Step 1: Write the failing tests**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/RequestBodyReadTimeoutTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** CHAPPE-022: {@code readTimeout} bounds each blocking read of an HTTP/1.1 request body. */
class RequestBodyReadTimeoutTest {

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private int start(Duration readTimeout) {
        server = Server.builder().port(0).readTimeout(readTimeout).handler(this::handle).build();
        server.start();
        return server.port();
    }

    /** {@code /read} reads the body and reports how the read ended; anything else ignores the body. */
    private Response handle(Request request) {
        if (!"/read".equals(request.path())) return Response.ok("pong");
        try {
            byte[] body = request.body().asInputStream().readAllBytes();
            outcome.complete("body:" + new String(body, StandardCharsets.US_ASCII));
            return Response.ok("read");
        } catch (IOException e) {
            outcome.complete(e.getClass().getSimpleName());
            return Response.ok("failed");
        }
    }

    @Test
    void aStalledContentLengthBodyFailsTheReadAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            long sent = System.nanoTime();
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");

            assertEquals("SocketTimeoutException", outcome.get(5, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - sent) / 1_000_000;
            assertTrue(elapsedMs >= 250, "the read gave up before the timeout: " + elapsedMs + " ms");
            ChunkedBodyLimitTest.readToEof(socket.getInputStream()); // the server closed the connection
        }
    }

    @Test
    void aStalledChunkedBodyFailsTheReadAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc");

            assertEquals("SocketTimeoutException", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSlowUploadWhoseGapsStayUnderTheReadTimeoutIsReadWhole() throws Exception {
        int port = start(Duration.ofSeconds(1));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n");
            for (char c : "hello".toCharArray()) {
                Thread.sleep(300); // 1.5 s in total, more than the timeout; each gap is less
                send(socket, String.valueOf(c));
            }

            assertEquals("body:hello", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aStalledBodyTheHandlerIgnoredClosesTheConnectionAfterTheReadTimeout() throws Exception {
        int port = start(Duration.ofMillis(300));
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");

            // The response comes first, then the drain times out and the connection closes.
            String response = ChunkedBodyLimitTest.readToEof(socket.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 200"), ChunkedBodyLimitTest.head(response));
            assertTrue(response.endsWith("pong"), ChunkedBodyLimitTest.head(response));
        }
    }

    @Test
    void aZeroReadTimeoutDisablesTheBound() throws Exception {
        int port = start(Duration.ZERO);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc");
            Thread.sleep(500);
            send(socket, "defghij");

            assertEquals("body:abcdefghij", outcome.get(5, TimeUnit.SECONDS));
        }
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
```

In `TlsLingeringCloseTest.java`, add the imports `java.time.Duration`, `java.util.concurrent.CompletableFuture`, `java.util.concurrent.TimeUnit` and `io.vidocq.chappe.api.Response`, and this test after `lingeringIsBoundedForAClientThatKeepsSending`:

```java
    /** CHAPPE-022 over TLS: readTimeout bounds a body read through the SslHandler too. */
    @Test
    void aStalledBodyOverTlsFailsTheReadAfterTheReadTimeout() throws Exception {
        var outcome = new CompletableFuture<String>();
        var timed = Server.builder()
                .port(0)
                .tls(serverSslContext)
                .readTimeout(Duration.ofMillis(300))
                .handler(req -> {
                    try {
                        req.body().asInputStream().readAllBytes();
                        outcome.complete("read");
                    } catch (IOException e) {
                        outcome.complete(e.getClass().getSimpleName());
                    }
                    return Response.ok("done");
                })
                .build();
        timed.start();
        try (var socket = (SSLSocket) trustAllContext.getSocketFactory().createSocket("127.0.0.1", timed.port())) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            out.write("POST /read HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            assertEquals("SocketTimeoutException", outcome.get(5, TimeUnit.SECONDS));
        } finally {
            timed.stop();
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=RequestBodyReadTimeoutTest+TlsLingeringCloseTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the two stalled-body tests and the TLS test FAIL with `TimeoutException` (the read never ends); `aStalledBodyTheHandlerIgnoredClosesTheConnectionAfterTheReadTimeout` FAILS with `SocketTimeoutException` in the client (the drain never ends). `aSlowUpload...` and `aZeroReadTimeout...` pass: they guard the per-read semantics and the opt-out.

- [ ] **Step 3: Create `TimedReadChannel`**

`chappe-http/src/main/java/io/vidocq/chappe/http/TimedReadChannel.java` (licence header first):

```java
package io.vidocq.chappe.http;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;

/**
 * The channel request bodies are read from: each blocking read is bounded by {@code readTimeout}
 * through {@link BoundedReads} (CHAPPE-022). At the deadline the watchdog closes the connection,
 * which wakes the read; the read then throws {@link SocketTimeoutException}. It bounds each read, not
 * the whole body: an upload that keeps sending, however slowly, is not cut.
 */
final class TimedReadChannel implements ReadableByteChannel {

    private final ReadableByteChannel delegate;
    private final Closeable closeable;
    private final Duration timeout;

    private TimedReadChannel(ReadableByteChannel delegate, Closeable closeable, Duration timeout) {
        this.delegate = delegate;
        this.closeable = closeable;
        this.timeout = timeout;
    }

    /**
     * {@code delegate} bounded by {@code timeout}, or {@code delegate} itself when {@code timeout}
     * is {@code null}, zero or negative (no bound).
     *
     * @param closeable what the watchdog closes at the deadline: the connection
     */
    static ReadableByteChannel of(ReadableByteChannel delegate, Closeable closeable, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return delegate;
        return new TimedReadChannel(delegate, closeable, timeout);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        long start = System.nanoTime();
        try {
            return BoundedReads.readWithTimeout(delegate, closeable, dst, timeout);
        } catch (IOException e) {
            if (System.nanoTime() - start < timeout.toNanos()) throw e;
            var timedOut = new SocketTimeoutException(
                    "Request body read timed out after " + timeout.toMillis() + " ms");
            timedOut.initCause(e);
            throw timedOut;
        }
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
```

- [ ] **Step 4: Read bodies through it in `HttpConnection`**

Add the field after `private final ReadableByteChannel readChannel;`:

```java
    /** {@link #readChannel} bounded by {@code readTimeout}: request bodies are read from it (CHAPPE-022). */
    private final ReadableByteChannel bodyChannel;
```

In the 7-argument constructor, after `this.config = config;`:

```java
        this.bodyChannel = TimedReadChannel.of(readChannel, closeable, config.readTimeout());
```

In `setupBody()`, use `bodyChannel` for both bodies:

```java
            return HttpBodyReader.chunked(readBuffer, bodyChannel, request::setTrailers, config.maxRequestSize());
```

```java
            return HttpBodyReader.fixedLength(readBuffer, bodyChannel, cl);
```

(The timed-out read leaves the channel closed: step 5 of `run()` then sees `!readChannel.isOpen()` and closes without a linger, as for any interrupted read.)

- [ ] **Step 5: Update the `readTimeout` Javadoc**

`ServerConfig.java`, replace `@param readTimeout maximum read timeout (default: 30s)` with:

```java
 * @param readTimeout bound on each blocking read of an HTTP/1.1 request body (default: 30s),
 *     including the drain of a body the handler left unread: when no byte arrives for that long, the
 *     read throws {@link java.net.SocketTimeoutException} and the connection is closed. It bounds
 *     each read, not the whole body. Zero or negative disables it. The wait for a next request is
 *     bounded by {@code idleTimeout} instead.
```

`Server.java`, replace `Builder readTimeout(Duration timeout);` with:

```java
        /**
         * Bound on each blocking read of an HTTP/1.1 request body (default: 30s); see {@link
         * ServerConfig#readTimeout()}. {@link Duration#ZERO} or a negative value disables it.
         */
        Builder readTimeout(Duration timeout);
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=RequestBodyReadTimeoutTest+TlsLingeringCloseTest+FixedLengthRequestBodyTest+ChunkedTransferTest+InterruptedBodyReadTest+StreamingBodyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 7: Record CHAPPE-022 in BUG.md**

Insert at the top of `BUG.md` (above CHAPPE-021):

```markdown
## CHAPPE-022 — ServerConfig.readTimeout is never applied

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): apply readTimeout to HTTP/1.1 request body reads (CHAPPE-022)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`HttpConnection`, new `TimedReadChannel`), `chappe-api` (Javadoc)
- **Symptom**: `Server.Builder.readTimeout(Duration)` was stored and never read. A request body read
  blocked for as long as the client sent nothing: a stalled upload pinned the handler, and the drain
  of an unread body held the connection until the client closed it.
- **Minimal reproduction**: `chappe-tests/.../RequestBodyReadTimeoutTest#aStalledContentLengthBodyFailsTheReadAfterTheReadTimeout`
  (`Content-Length: 10`, 3 bytes sent: the handler waited forever).
- **Cause**: body streams read the raw channel. `SO_TIMEOUT` does not apply to channel reads, and only
  the idle wait was bounded (CHAPPE-005).
- **Fix**: body streams read through `TimedReadChannel`, which bounds each read with
  `BoundedReads.readWithTimeout`. At the deadline the connection is closed and the read throws
  `SocketTimeoutException`; zero or negative disables the bound. The HTTP/2 counterpart is CHAPPE-024.

---
```

- [ ] **Step 8: Document `readTimeout`**

`reference.adoc`, timeouts section: replace

```
* `readTimeout` (default 30 s) bounds a blocking read of the request.
```

with

```
* `readTimeout` (default 30 s) [.tag-new]#NEW# bounds *each* blocking read of an HTTP/1.1 request body, over plaintext and TLS, including the drain of a body the handler left unread. When no byte arrives for that long, the read throws `java.net.SocketTimeoutException` and Chappe closes the connection. It bounds each read, not the whole body: an upload that keeps sending, however slowly, is not cut. `Duration.ZERO` or a negative value disables it. Until 0.3.0 the value was accepted by the builder and never applied.
```

`whats-new.adoc`: append as the last bullet:

```
* **`readTimeout` is applied** — `Server.Builder.readTimeout(Duration)` was accepted and never read. It now bounds each blocking read of an HTTP/1.1 request body, 30 s by default: a body that stalls fails with `SocketTimeoutException` and the connection is closed (CHAPPE-022). xref:reference.adoc#timeouts[Reference — timeouts].
```

`migration.adoc`: append as the last bullet of the `Upgrading` list:

```
* **`readTimeout` now closes stalled uploads** — up to {release-version}, `readTimeout` was never applied. A request body read that receives nothing for 30 s now fails with `SocketTimeoutException`, and the HTTP/1.1 connection closes. Set a larger `readTimeout`, or `Duration.ZERO` to disable it, if your clients pause longer mid-upload. See xref:reference.adoc#timeouts[Reference — timeouts].
```

- [ ] **Step 9: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS. No benchmark is run by this plan; the watchdog costs one virtual thread per blocking body read (not per request without a body). If a benchmark is run anyway, log it in `BENCHMARKS.md` with date, hardware/JVM, command, raw results and delta.

- [ ] **Step 10: Commit**

```bash
git add chappe-http/src/main/java/io/vidocq/chappe/http/TimedReadChannel.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java \
  chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java \
  chappe-api/src/main/java/io/vidocq/chappe/api/Server.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/RequestBodyReadTimeoutTest.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/TlsLingeringCloseTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc \
  docs/en/modules/ROOT/pages/migration.adoc
git commit -S -s -F - <<'EOF'
fix(http): apply readTimeout to HTTP/1.1 request body reads (CHAPPE-022)

ServerConfig.readTimeout was stored and never read: a stalled request
body held the handler, or the drain after the response, for as long as
the client kept the connection open.

Request bodies are now read through TimedReadChannel, which bounds each
blocking read with BoundedReads.readWithTimeout, over plaintext and
TLS. At the deadline the connection is closed and the read throws
SocketTimeoutException. Zero or a negative timeout disables the bound.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 4: HTTP/2 early response: RST_STREAM(NO_ERROR) and late frames tolerated (CHAPPE-023)

**Files:**
- Create: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/ResetStreams.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java:58-96,195-227,240-303,346-381,383-419,449-509`
- Create test helper: `chappe-tests/src/test/java/io/vidocq/chappe/tests/H2TestConnection.java`
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2EarlyResponseTest.java`
- Modify: `BUG.md`, `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`, `internals.adoc`

**Interfaces:**
- Consumes: `Http2Stream(int streamId, int initialRecvWindow, int initialSendWindow)` (existing), `Http2Stream.beginHeaders/appendHeaderFragment/completeHeaderBlock/isEndStreamReceived/cancel` (existing), `HpackDecoder.decode(ByteBuffer, HeaderSink)` (existing, public).
- Produces: `final class ResetStreams { ResetStreams(int capacity); synchronized void add(int streamId); synchronized boolean contains(int streamId); }` (package `io.vidocq.chappe.http.h2`); in `Http2Connection`: `private void resetStream(Http2Stream stream, Http2ErrorCode error) throws IOException`, `private void discardData(int streamId, int flowLength) throws IOException`, `private static int flowControlledLength(Http2Frame.DataFrame frame)`, `private void replenishConnectionWindow() throws IOException`, `private void stopUnreadRequestBody(Http2Stream stream)`, constant `CONNECTION_WINDOW = 65_535`. Test helper `H2TestConnection` (API below), used by Tasks 5 and 6.

- [ ] **Step 1: Write the raw HTTP/2 test client**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/H2TestConnection.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.vidocq.chappe.http.h2.HpackDecoder;

/**
 * A raw HTTP/2 client (h2c, prior knowledge) for tests that send what a well-behaved client never
 * sends: frames on reset streams, overrun windows, padding. The HEADERS frames the server sends are
 * HPACK-decoded in order, so {@link Frame#headers()} holds their fields.
 */
final class H2TestConnection implements AutoCloseable {

    static final int TYPE_DATA = 0x0;
    static final int TYPE_HEADERS = 0x1;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_WINDOW_UPDATE = 0x8;
    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_ACK = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int NO_ERROR = 0x0;
    static final int PROTOCOL_ERROR = 0x1;
    static final int FLOW_CONTROL_ERROR = 0x3;
    static final int STREAM_CLOSED = 0x5;
    static final int REFUSED_STREAM = 0x7;
    static final int CANCEL = 0x8;

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** A frame from the server; {@code headers} is empty unless it is a HEADERS frame. */
    record Frame(int type, int flags, int streamId, byte[] payload, Map<String, String> headers) {
        boolean endStream() {
            return (flags & FLAG_END_STREAM) != 0;
        }

        /** The error code of a RST_STREAM frame. */
        int errorCode() {
            return ByteBuffer.wrap(payload).getInt();
        }

        /** The error code of a GOAWAY frame. */
        int goawayErrorCode() {
            return ByteBuffer.wrap(payload, 4, 4).getInt();
        }

        int windowIncrement() {
            return ByteBuffer.wrap(payload).getInt() & 0x7FFFFFFF;
        }
    }

    /** A whole response: its {@code :status} and its body. */
    record Reply(String status, String body) {}

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final HpackDecoder decoder = new HpackDecoder(4096, 64 * 1024);

    private H2TestConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    /** Connects, sends the preface and an empty SETTINGS, and completes the SETTINGS exchange. */
    static H2TestConnection open(int port) throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        var h2 = new H2TestConnection(socket);
        h2.out.write(PREFACE);
        h2.frame(TYPE_SETTINGS, 0, 0, new byte[0]);
        h2.flush();
        for (int i = 0; i < 2; i++) h2.read(); // the server's SETTINGS and its ACK of ours
        h2.frame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
        h2.flush();
        return h2;
    }

    void headers(int streamId, boolean endStream, byte[] block) throws IOException {
        frame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block);
    }

    void data(int streamId, boolean endStream, byte[] bytes) throws IOException {
        frame(TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, bytes);
    }

    /** A PADDED DATA frame: the pad-length byte, the data, then {@code padLength} zero bytes. */
    void paddedData(int streamId, boolean endStream, byte[] bytes, int padLength) throws IOException {
        byte[] payload = new byte[1 + bytes.length + padLength];
        payload[0] = (byte) padLength;
        System.arraycopy(bytes, 0, payload, 1, bytes.length);
        frame(TYPE_DATA, FLAG_PADDED | (endStream ? FLAG_END_STREAM : 0), streamId, payload);
    }

    void frame(int type, int flags, int streamId, byte[] payload) throws IOException {
        int len = payload.length;
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(type & 0xFF);
        out.write(flags & 0xFF);
        out.writeInt(streamId & 0x7FFFFFFF);
        out.write(payload);
    }

    void flush() throws IOException {
        out.flush();
    }

    /** The next frame; a HEADERS block is decoded (the server sends small blocks in one frame). */
    Frame read() throws IOException {
        int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int streamId = in.readInt() & 0x7FFFFFFF;
        byte[] payload = in.readNBytes(len);
        Map<String, String> headers = new HashMap<>();
        if (type == TYPE_HEADERS) decoder.decode(ByteBuffer.wrap(payload), headers::put);
        return new Frame(type, flags, streamId, payload, headers);
    }

    /** Reads up to the first frame of {@code type} on {@code streamId}; a GOAWAY on the way fails. */
    Frame await(int type, int streamId) throws IOException {
        while (true) {
            Frame f = read();
            if (f.type() == type && f.streamId() == streamId) return f;
            if (f.type() == TYPE_GOAWAY) {
                throw new AssertionError("GOAWAY (error " + f.goawayErrorCode() + ") while waiting for frame type "
                        + type + " on stream " + streamId);
            }
        }
    }

    /** The next frame within {@code millis}, or {@code null} when none came. */
    Frame poll(int millis) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(millis);
        try {
            return read();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    /** Reads the response of {@code streamId} up to its END_STREAM; a GOAWAY or a reset fails. */
    Reply reply(int streamId) throws IOException {
        String status = null;
        var body = new ByteArrayOutputStream();
        while (true) {
            Frame f = read();
            if (f.type() == TYPE_GOAWAY) {
                throw new AssertionError("GOAWAY (error " + f.goawayErrorCode() + ") before the end of stream " + streamId);
            }
            if (f.streamId() != streamId) continue;
            if (f.type() == TYPE_RST_STREAM) {
                throw new AssertionError("stream " + streamId + " reset (error " + f.errorCode() + ") before its end");
            }
            if (f.type() == TYPE_HEADERS && status == null) status = f.headers().get(":status");
            if (f.type() == TYPE_DATA) body.write(f.payload(), 0, f.payload().length);
            if (f.endStream()) return new Reply(status, body.toString(StandardCharsets.US_ASCII));
        }
    }

    /** Request pseudo-headers, then {@code extra} name/value pairs, as literals without indexing. */
    static byte[] requestHeaders(String method, String path, String... extra) {
        var block = new ByteArrayOutputStream();
        literal(block, 0x00, ":method", method);
        literal(block, 0x00, ":scheme", "http");
        literal(block, 0x00, ":authority", "127.0.0.1");
        literal(block, 0x00, ":path", path);
        for (int i = 0; i < extra.length; i += 2) literal(block, 0x00, extra[i], extra[i + 1]);
        return block.toByteArray();
    }

    /** A literal field with incremental indexing and a new name (RFC 7541 §6.2.1): it enters the dynamic table. */
    static byte[] literalWithIndexing(String name, String value) {
        var block = new ByteArrayOutputStream();
        literal(block, 0x40, name, value);
        return block.toByteArray();
    }

    /** An indexed field (RFC 7541 §6.1); 62 is the newest dynamic table entry. */
    static byte[] indexed(int index) {
        return new byte[] {(byte) (0x80 | index)};
    }

    static byte[] concat(byte[]... parts) {
        var all = new ByteArrayOutputStream();
        for (byte[] part : parts) all.write(part, 0, part.length);
        return all.toByteArray();
    }

    private static void literal(ByteArrayOutputStream out, int pattern, String name, String value) {
        out.write(pattern);
        rawString(out, name);
        rawString(out, value);
    }

    private static void rawString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length >= 127) throw new IllegalArgumentException("too long for this encoder: " + s);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2EarlyResponseTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-023: when the response is complete before the request body ended, the server resets the
 * stream with RST_STREAM(NO_ERROR) (RFC 9113 §8.1), and the frames the client had already sent on a
 * stream the server reset or refused are ignored, not a connection error (§5.1). Their header blocks
 * are still decoded, to keep the HPACK context.
 */
class Http2EarlyResponseTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop();
    }

    /** {@code /block} waits for the test; {@code /echo} answers its {@code x-late} field; others ignore the body. */
    private Response handle(Request request) throws InterruptedException {
        return switch (request.path()) {
            case "/block" -> {
                release.await(10, TimeUnit.SECONDS);
                yield Response.ok("unblocked");
            }
            case "/echo" -> Response.ok("x-late=" + request.headers().firstOrNull("x-late"));
            default -> Response.ok("early");
        };
    }

    /** Stream 1 carries a body the handler never reads; returns the RST_STREAM that follows its response. */
    private static Frame earlyResponse(H2TestConnection h2) throws IOException {
        h2.headers(1, false, requestHeaders("POST", "/upload"));
        h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
        h2.flush();
        assertEquals(new Reply("200", "early"), h2.reply(1));
        return h2.await(TYPE_RST_STREAM, 1);
    }

    @Test
    void anEarlyResponseResetsTheUnreadRequestBodyWithNoError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            assertEquals(NO_ERROR, earlyResponse(h2).errorCode());
        }
    }

    @Test
    void lateDataOnTheResetStreamIsIgnoredButCountsAgainstTheConnectionWindow() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            earlyResponse(h2);
            h2.data(1, false, new byte[16_384]); // already in flight when the reset arrived
            h2.data(1, false, new byte[16_384]);
            h2.data(1, true, new byte[7_232]);
            h2.flush();
            // 3 + 32,768 discarded bytes take the connection window under its threshold: credited back.
            assertTrue(h2.await(TYPE_WINDOW_UPDATE, 0).windowIncrement() >= 32_768);
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(3));
        }
    }

    @Test
    void aLateTrailerBlockOnTheResetStreamStillUpdatesTheHpackContext() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            earlyResponse(h2);
            h2.headers(1, true, literalWithIndexing("x-late", "v1")); // trailers already in flight
            h2.headers(3, true, concat(requestHeaders("GET", "/echo"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "x-late=v1"), h2.reply(3));
        }
    }

    @Test
    void aRefusedStreamKeepsTheConnectionAndTheHpackContext() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            for (int id = 1; id <= 199; id += 2) h2.headers(id, true, requestHeaders("GET", "/block"));
            // The 101st concurrent stream is refused (MAX_CONCURRENT_STREAMS = 100); its block inserts x-late.
            h2.headers(201, false, concat(requestHeaders("POST", "/echo"), literalWithIndexing("x-late", "v1")));
            h2.data(201, true, "abc".getBytes(StandardCharsets.US_ASCII)); // in flight when the refusal arrives
            h2.flush();
            assertEquals(REFUSED_STREAM, h2.await(TYPE_RST_STREAM, 201).errorCode());

            release.countDown();
            var ended = new HashSet<Integer>();
            while (ended.size() < 100) {
                Frame f = h2.read();
                assertNotEquals(TYPE_GOAWAY, f.type(), "the connection must survive the refused stream");
                if (f.endStream() && f.streamId() <= 199) ended.add(f.streamId());
            }
            h2.headers(203, true, concat(requestHeaders("GET", "/echo"), indexed(62)));
            h2.flush();
            assertEquals(new Reply("200", "x-late=v1"), h2.reply(203));
        }
    }

    @Test
    void dataOnAStreamClosedNormallyIsAStreamErrorNotAConnectionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(1));
            Thread.sleep(300); // the server removes the stream right after its response
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(STREAM_CLOSED, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "early"), h2.reply(3));
        }
    }

    @Test
    void dataOnAStreamNeverOpenedIsStillAConnectionError() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.data(5, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush();
            assertEquals(PROTOCOL_ERROR, h2.await(TYPE_GOAWAY, 0).goawayErrorCode());
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=Http2EarlyResponseTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the first four FAIL (no RST_STREAM arrives, so `await` hits the 5 s socket timeout, or a `GOAWAY` assertion fires); `dataOnAStreamClosedNormally...` FAILS with a GOAWAY assertion. `dataOnAStreamNeverOpenedIsStillAConnectionError` passes: it guards the idle-stream rule.

- [ ] **Step 4: Create `ResetStreams`**

`chappe-http/src/main/java/io/vidocq/chappe/http/h2/ResetStreams.java` (licence header first):

```java
package io.vidocq.chappe.http.h2;

/**
 * The ids of the last streams this endpoint reset or refused. The peer may have sent frames on them
 * before it saw the RST_STREAM; RFC 9113 §5.1 requires ignoring those frames, not failing the
 * connection. Bounded: a frame on a stream forgotten since is handled as on any closed stream.
 * Written by stream threads and read by the connection thread; {@code synchronized} does not pin a
 * virtual thread since JDK 24 (JEP 491).
 */
final class ResetStreams {

    private final int[] ids;
    private int next;
    private int size;

    ResetStreams(int capacity) {
        this.ids = new int[capacity];
    }

    synchronized void add(int streamId) {
        ids[next] = streamId;
        next = (next + 1) % ids.length;
        if (size < ids.length) size++;
    }

    synchronized boolean contains(int streamId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == streamId) return true;
        }
        return false;
    }
}
```

- [ ] **Step 5: Reset an unread request body after a complete response**

In `Http2Connection.java`:

Constants (after `WINDOW_UPDATE_THRESHOLD`):

```java
    private static final int CONNECTION_WINDOW = 65_535;

    /** How many reset stream ids are remembered, so the frames the peer already sent on them are ignored. */
    private static final int REMEMBERED_RESET_STREAMS = 256;
```

Change `connectionRecvWindow` to `new AtomicInteger(CONNECTION_WINDOW)`. Fields, after `goawaySent`:

```java
    // Streams this endpoint reset or refused: frames already in flight on them are ignored (RFC 9113 §5.1)
    private final ResetStreams resetStreams = new ResetStreams(REMEMBERED_RESET_STREAMS);

    // Header block of a stream that is not served (reset, refused), still decoded for the HPACK context
    private Http2Stream discardedHeaderBlock;
```

In `dispatchStream` (lines 449-509): declare `boolean completed = false;` next to `Response handled = null;`; in the two gRPC branches, set `completed = true;` right before each `return;`; after `sendResponse(stream, response);` add `completed = true;`; replace the `finally` block with:

```java
        } finally {
            if (completed && hasBody) stopUnreadRequestBody(stream);
            stream.close();
            streams.remove(stream.streamId());
            if (handled != null) releaseBody(handled);
        }
```

Add after `resetAbortedStream`:

```java
    /**
     * The response is complete, the request body is not: RFC 9113 §8.1 lets the server ask the client
     * to stop sending it with RST_STREAM(NO_ERROR). The id is remembered first, so the DATA already in
     * flight is ignored instead of failing the whole connection (§5.1) (CHAPPE-023).
     */
    private void stopUnreadRequestBody(Http2Stream stream) {
        if (stream.isCancelled() || stream.isEndStreamReceived()) return;
        resetStreams.add(stream.streamId());
        streams.remove(stream.streamId());
        stream.cancel(); // a reader still waiting for the body fails instead of waiting forever
        try {
            frameWriter.writeRstStream(stream.streamId(), Http2ErrorCode.NO_ERROR);
        } catch (IOException _) {
            // connection lost: nothing more to tell the peer
        }
    }

    /**
     * Resets {@code stream} with {@code error} (a stream error, RFC 9113 §5.4.2): remembers its id so
     * the frames the peer already sent on it are ignored, cancels it (its body reader fails, the body it
     * was sending is released, its sender stops waiting), then sends RST_STREAM.
     */
    private void resetStream(Http2Stream stream, Http2ErrorCode error) throws IOException {
        resetStreams.add(stream.streamId());
        streams.remove(stream.streamId());
        stream.cancel();
        wakeConnectionWindowWaiters();
        frameWriter.writeRstStream(stream.streamId(), error);
    }
```

In `handleSettings` (lines 214-219) replace the three lines of the overflow branch with `resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);`. In `handleWindowUpdate` (lines 409-413) replace the three lines before `return;` with `resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);`.

- [ ] **Step 6: Ignore DATA on reset streams, still counting it**

Replace `handleData` (lines 346-381) with:

```java
    private void handleData(Http2Frame.DataFrame frame) throws IOException {
        int streamId = frame.streamId();
        int flowLength = flowControlledLength(frame);
        var stream = streams.get(streamId);
        if (stream == null) {
            discardData(streamId, flowLength);
            return;
        }

        // Update flow control windows (atomic)
        connectionRecvWindow.addAndGet(-flowLength);
        stream.consumeRecvWindow(flowLength);

        // Copy data and offer to stream
        int dataLength = frame.data().remaining();
        byte[] copy = new byte[dataLength];
        frame.data().get(copy);
        stream.offerData(ByteBuffer.wrap(copy));

        if (frame.endStream()) {
            stream.signalEndStream();
            stream.halfCloseRemote();
        }

        replenishConnectionWindow();
        // Auto WINDOW_UPDATE if the stream recv window is getting low
        if (stream.recvWindow() < WINDOW_UPDATE_THRESHOLD) {
            int increment = localSettings.initialWindowSize() - stream.recvWindow();
            stream.incrementRecvWindow(increment);
            frameWriter.writeWindowUpdate(streamId, increment);
        }
    }

    /**
     * DATA for a stream that is not open. On a stream this endpoint reset (an early response, a refused
     * or overrun stream) the peer may have sent it before it saw the RST_STREAM: ignored (RFC 9113
     * §5.1). On another closed stream it is a stream error STREAM_CLOSED (§6.1). Either way it counts
     * against the connection window, credited back like any received DATA (§6.9). DATA on stream 0 or
     * on a stream never opened stays a connection error (§6.1, §5.1).
     */
    private void discardData(int streamId, int flowLength) throws IOException {
        if (streamId == 0 || streamId % 2 == 0 || streamId > lastStreamId) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "DATA for idle stream " + streamId);
        }
        connectionRecvWindow.addAndGet(-flowLength);
        if (!resetStreams.contains(streamId)) {
            resetStreams.add(streamId);
            frameWriter.writeRstStream(streamId, Http2ErrorCode.STREAM_CLOSED);
        }
        replenishConnectionWindow();
    }

    /** The flow-controlled size of a DATA frame: its whole payload, padding included (RFC 9113 §6.9.1). */
    private static int flowControlledLength(Http2Frame.DataFrame frame) {
        boolean padded = (frame.flags() & Http2Frame.FLAG_PADDED) != 0;
        return frame.data().remaining() + (padded ? frame.padding() + 1 : 0);
    }

    /** Credits the connection receive window back once it runs low: the connection level is credited on receipt. */
    private void replenishConnectionWindow() throws IOException {
        int connRecv = connectionRecvWindow.get();
        if (connRecv < WINDOW_UPDATE_THRESHOLD) {
            int increment = CONNECTION_WINDOW - connRecv;
            connectionRecvWindow.addAndGet(increment);
            frameWriter.writeWindowUpdate(0, increment);
        }
    }
```

- [ ] **Step 7: Decode and drop header blocks of reset and refused streams**

In `handleHeaders`, insert after the `existing != null` block (before `// Stream ID must be odd ...`):

```java
        // A stream this endpoint reset: the peer may have sent HEADERS (trailers) before it saw the
        // RST_STREAM. Ignored, but decoded: the block may update the HPACK dynamic table (§5.1).
        if (resetStreams.contains(streamId)) {
            discardHeaderBlock(streamId, frame);
            return;
        }
```

Replace the refusal block (lines 268-272) with:

```java
        // Check max concurrent streams (our limit, not the client's)
        if (streams.size() >= localSettings.maxConcurrentStreams()) {
            resetStreams.add(streamId); // the DATA the client already sent on it is ignored
            frameWriter.writeRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            discardHeaderBlock(streamId, frame); // still decoded: it may update the HPACK dynamic table
            return;
        }
```

Add after `handleHeaders`:

```java
    /**
     * Decodes and drops the header block of a stream that is not served (reset or refused). HPACK is
     * connection state: skipping a block that inserts into the dynamic table would desynchronise every
     * later block (RFC 7541 §2.3.2, RFC 9113 §4.3).
     */
    private void discardHeaderBlock(int streamId, Http2Frame.HeadersFrame frame) throws Http2ConnectionException {
        var discarded = new Http2Stream(streamId, 0, 0);
        discarded.beginHeaders(frame.headerBlock(), frame.endStream());
        if (frame.endHeaders()) {
            hpackDecoder.decode(discarded.completeHeaderBlock(), (name, value) -> {});
        } else {
            discardedHeaderBlock = discarded;
            expectingContinuationForStream = streamId;
        }
    }
```

At the start of `handleContinuation`, after `int streamId = frame.streamId();`:

```java
        if (discardedHeaderBlock != null && discardedHeaderBlock.streamId() == streamId) {
            discardedHeaderBlock.appendHeaderFragment(frame.headerBlock());
            if (frame.endHeaders()) {
                expectingContinuationForStream = -1;
                var discarded = discardedHeaderBlock;
                discardedHeaderBlock = null;
                hpackDecoder.decode(discarded.completeHeaderBlock(), (name, value) -> {});
            }
            return;
        }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl chappe-tests,chappe-conformance -am test -Dtest='Http2*Test,Grpc*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS. If a gRPC test fails on an unexpected `RST_STREAM(NO_ERROR)` after a complete response, that is the new, RFC 9113 §8.1 behaviour: check that the call's response really was complete, then make the test client ignore a NO_ERROR reset that follows END_STREAM (RFC 9113 §8.1: a client must not discard the response because of it). Record any such test change in the commit message.

- [ ] **Step 9: Record CHAPPE-023 in BUG.md**

Insert at the top of `BUG.md` (above CHAPPE-022):

```markdown
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
  DATA padding was not counted in flow control.
- **Minimal reproduction**: `chappe-tests/.../Http2EarlyResponseTest#lateDataOnTheResetStreamIsIgnoredButCountsAgainstTheConnectionWindow`
  and `#aRefusedStreamKeepsTheConnectionAndTheHpackContext` (GOAWAY before the fix).
- **Cause**: `dispatchStream` removed the stream without telling the client; every frame on a stream
  missing from `streams` was a connection error.
- **Fix**: after a complete response, a stream whose request END_STREAM has not arrived is reset with
  `RST_STREAM(NO_ERROR)` (RFC 9113 §8.1). The ids of the last 256 streams the server reset or refused
  are remembered: DATA on them is ignored but counted against the connection window, and their HEADERS
  and CONTINUATION blocks are decoded and dropped (§5.1). DATA on another closed stream is a stream
  error `STREAM_CLOSED` (§6.1); DATA on stream 0 or a stream never opened stays a connection error.
  The flow-controlled length of a DATA frame now includes its padding.

---
```

- [ ] **Step 10: Document the behaviour**

`reference.adoc`, in the `[#request-body-limits]` section, insert after the `maxDrainSize` bullet (the one ending with `refused with `IllegalArgumentException`.`):

```
* Over HTTP/2, when the response is complete before the request body ended, Chappe resets the stream with `RST_STREAM(NO_ERROR)` (RFC 9113 §8.1), so the client stops uploading. The DATA, HEADERS and CONTINUATION frames the client had already sent on that stream, or on a stream Chappe refused or reset, are dropped without failing the connection; they still count against the connection window, and their header blocks are still decoded to keep the HPACK table in sync. DATA on a stream that closed normally is answered with `RST_STREAM(STREAM_CLOSED)`; DATA on a stream that was never opened is still a connection error.
```

`reference.adoc`, HTTP capabilities table: replace

```
| RFC 9113 (HTTP/2)         | OK | framing, flow control, lifecycle, GOAWAY, SETTINGS, CONTINUATION
```

with

```
| RFC 9113 (HTTP/2)         | OK | framing, flow control, lifecycle, GOAWAY, SETTINGS, CONTINUATION, `RST_STREAM(NO_ERROR)` after an early response (§8.1), frames on reset streams ignored (§5.1) [.tag-new]#NEW#
```

`whats-new.adoc`: append as the last bullet:

```
* **An early HTTP/2 response no longer kills the connection** — when a handler answered before the end of its request body, the frames the client was still sending on that stream made Chappe tear the whole connection down with `GOAWAY(PROTOCOL_ERROR)`, cutting every other stream. Chappe now resets the stream with `RST_STREAM(NO_ERROR)` once the response is complete and ignores the frames already in flight; the same holds for a stream it refused (CHAPPE-023). xref:reference.adoc#request-body-limits[Reference — request body limits].
```

`internals.adoc`: replace

```
a longer body closes the connection instead, gracefully. Over HTTP/2 the stream is reset.
```

with

```
a longer body closes the connection instead, gracefully. Over HTTP/2, once the response is complete, the stream is reset with `RST_STREAM(NO_ERROR)` (RFC 9113 §8.1), and the frames already in flight on it are ignored.
```

- [ ] **Step 11: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS.

- [ ] **Step 12: Commit**

```bash
git add chappe-http/src/main/java/io/vidocq/chappe/http/h2/ResetStreams.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/H2TestConnection.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2EarlyResponseTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc \
  docs/en/modules/ROOT/pages/internals.adoc
git commit -S -s -F - <<'EOF'
fix(http): reset an unread HTTP/2 request body and ignore late frames (CHAPPE-023)

A handler answering before the end of its request body left the client
uploading on a stream the server had dropped: its next DATA or trailers
was a connection error, and GOAWAY cut every other stream. A refused
stream did the same, and its header block was never HPACK-decoded.

After a complete response, a stream whose request has not ended is
reset with RST_STREAM(NO_ERROR) (RFC 9113 8.1). The last 256 reset or
refused stream ids are remembered: their DATA is ignored but counted
against the connection window, their header blocks are decoded and
dropped (5.1). DATA on another closed stream is a STREAM_CLOSED stream
error (6.1). DATA padding now counts in flow control.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 5: Open the HTTP/2 stream window as the body is read (CHAPPE-024)

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Stream.java:22-31,50-79,184-186,242-244`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2BodyInputStream.java:22-88`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java:67-68,274-277,handleData`
- Modify: `chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java`, `Server.java` (`readTimeout` Javadoc)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestFlowControlTest.java`
- Modify: `BUG.md`, `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`

**Interfaces:**
- Consumes: `H2TestConnection` (Task 4); `Http2Connection.resetStream`, `discardData`, `flowControlledLength`, `replenishConnectionWindow` (Task 4); `ServerConfig.readTimeout()`.
- Produces: `public Http2Stream(int streamId, int initialRecvWindow, int initialSendWindow, Duration bodyReadTimeout, IntConsumer windowCredit)` (the 3-argument constructor delegates with `null` and a no-op credit); `void Http2Stream.bodyBytesConsumed(int n)`; `ByteBuffer Http2Stream.pollData() throws InterruptedException` (returns `null` when the read timeout expired; replaces `takeData()`); `Duration Http2Stream.bodyReadTimeout()`; `Http2Stream.incrementRecvWindow(int)` is removed; `private void Http2Connection.creditStreamWindow(int streamId, int increment)`.

- [ ] **Step 1: Write the failing tests**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestFlowControlTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-024: the HTTP/2 stream window reopens as the handler reads the body, so a stream buffers at
 * most its window; a client overrunning it gets a stream FLOW_CONTROL_ERROR; padding is credited back;
 * readTimeout bounds the wait for the next DATA frame.
 */
class Http2RequestFlowControlTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private Server server;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop();
    }

    private void start(Duration readTimeout) {
        server = Server.builder().port(0).readTimeout(readTimeout).handler(this::handle).build();
        server.start();
    }

    /**
     * {@code /wait-then-read} reads 49,152 bytes once the test releases it; {@code /block} never reads;
     * {@code /read} reads the whole body; a failed read answers {@code failed:<exception>}.
     */
    private Response handle(Request request) throws InterruptedException {
        try {
            return switch (request.path()) {
                case "/wait-then-read" -> {
                    release.await(10, TimeUnit.SECONDS);
                    yield Response.ok("read:" + request.body().asInputStream().readNBytes(49_152).length);
                }
                case "/block" -> {
                    release.await(10, TimeUnit.SECONDS);
                    yield Response.ok("released");
                }
                case "/read" -> Response.ok("read:" + request.body().asInputStream().readAllBytes().length);
                default -> Response.ok("pong");
            };
        } catch (IOException e) {
            return Response.ok("failed:" + e.getClass().getSimpleName());
        }
    }

    @Test
    void theStreamWindowReopensOnlyAsTheHandlerReadsTheBody() throws Exception {
        start(Duration.ofSeconds(30));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/wait-then-read"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[16_384]);
            h2.flush();
            // The handler has read nothing yet: no WINDOW_UPDATE may reopen stream 1.
            long end = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < end) {
                Frame f = h2.poll(100);
                if (f != null) {
                    assertFalse(
                            f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 1,
                            "stream 1 credited before any read");
                }
            }
            release.countDown();
            assertTrue(h2.await(TYPE_WINDOW_UPDATE, 1).windowIncrement() >= 32_767);
            assertEquals(new Reply("200", "read:49152"), h2.reply(1));
        }
    }

    @Test
    void aClientOverrunningTheStreamWindowGetsAStreamFlowControlError() throws Exception {
        start(Duration.ofSeconds(30));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/block"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[16_384]);
            h2.data(1, false, new byte[16_383]); // the whole 65,535-byte window
            h2.data(1, false, new byte[1]); // one byte past it
            h2.flush();
            assertEquals(FLOW_CONTROL_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
        }
    }

    @Test
    void aPaddedUploadLargerThanTheWindowCompletes() throws Exception {
        start(Duration.ofSeconds(30));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            byte[] data = new byte[100];
            int frames = 2_000;
            int padLength = 255;
            int flow = 1 + data.length + padLength; // 356 flow-controlled bytes for 100 data bytes
            int streamWindow = 65_535;
            int connectionWindow = 65_535;
            for (int i = 0; i < frames; i++) {
                while (streamWindow < flow || connectionWindow < flow) {
                    h2.flush();
                    Frame f = h2.read(); // a SocketTimeoutException here means the upload stalled
                    assertNotEquals(TYPE_GOAWAY, f.type());
                    if (f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 1) streamWindow += f.windowIncrement();
                    if (f.type() == TYPE_WINDOW_UPDATE && f.streamId() == 0) connectionWindow += f.windowIncrement();
                }
                h2.paddedData(1, i == frames - 1, data, padLength);
                streamWindow -= flow;
                connectionWindow -= flow;
            }
            h2.flush();
            assertEquals(new Reply("200", "read:200000"), h2.reply(1));
        }
    }

    @Test
    void aStalledHttp2BodyFailsTheReadAfterTheReadTimeout() throws Exception {
        start(Duration.ofMillis(300));
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            h2.data(1, false, "abc".getBytes(StandardCharsets.US_ASCII));
            h2.flush(); // and nothing more: the body stalls
            assertEquals(new Reply("200", "failed:SocketTimeoutException"), h2.reply(1));
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=Http2RequestFlowControlTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `theStreamWindowReopensOnlyAsTheHandlerReadsTheBody` FAILS ("stream 1 credited before any read"); `aClientOverrunning...` FAILS (no RST_STREAM, socket timeout); `aPaddedUpload...` FAILS with `SocketTimeoutException` (the window leaks the padding and stalls); `aStalledHttp2Body...` FAILS with `SocketTimeoutException` (the read waits forever).

- [ ] **Step 3: Credit the stream window on consumption in `Http2Stream`**

Imports: add `java.time.Duration`, `java.util.concurrent.TimeUnit`, `java.util.function.IntConsumer`.

Fields, after `sendWindowAvailable`:

```java
    // Stream receive window, credited back as the request body is consumed (CHAPPE-024)
    private final int windowUpdateThreshold;
    private final AtomicInteger consumedSinceUpdate = new AtomicInteger();
    private final IntConsumer windowCredit;
    private final Duration bodyReadTimeout;
```

Replace the constructor (lines 72-79):

```java
    public Http2Stream(int streamId, int initialRecvWindow, int initialSendWindow) {
        this(streamId, initialRecvWindow, initialSendWindow, null, _ -> {});
    }

    /**
     * @param bodyReadTimeout bound on a request body read waiting for the next DATA frame
     *     ({@code null}, zero or negative: no bound)
     * @param windowCredit sends a stream WINDOW_UPDATE of the given increment, once the request body
     *     reader consumed that many bytes
     */
    public Http2Stream(
            int streamId,
            int initialRecvWindow,
            int initialSendWindow,
            Duration bodyReadTimeout,
            IntConsumer windowCredit) {
        this.streamId = streamId;
        this.state = State.IDLE;
        this.recvWindow = new AtomicInteger(initialRecvWindow);
        this.sendWindow = new AtomicInteger(initialSendWindow);
        this.windowUpdateThreshold = Math.max(1, initialRecvWindow / 2);
        this.bodyReadTimeout = bodyReadTimeout;
        this.windowCredit = windowCredit;
        this.request = new HttpRequestImpl();
        this.request.setVersion(HttpVersion.HTTP_2);
    }
```

Delete `incrementRecvWindow(int)` (lines 184-186). Add after `consumeRecvWindow`:

```java
    /**
     * Records {@code n} request body bytes the application consumed (or padding, which is never read)
     * and gives them back to the peer's stream window once half of it is pending. The window reopens
     * only as fast as the body is read, so the bytes buffered for a stream never exceed it (RFC 9113
     * §6.9, CHAPPE-024).
     */
    void bodyBytesConsumed(int n) {
        if (n <= 0 || endStreamReceived || cancelled) return;
        if (consumedSinceUpdate.addAndGet(n) < windowUpdateThreshold) return;
        int increment = consumedSinceUpdate.getAndSet(0);
        if (increment <= 0) return;
        recvWindow.addAndGet(increment);
        windowCredit.accept(increment);
    }

    Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }
```

Replace `takeData()` (lines 242-244):

```java
    /** The next body chunk, waiting at most the body read timeout; {@code null} when it expired. */
    ByteBuffer pollData() throws InterruptedException {
        Duration timeout = bodyReadTimeout;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return dataQueue.take();
        return dataQueue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
```

- [ ] **Step 4: Credit and time out in `Http2BodyInputStream`**

Add the import `java.net.SocketTimeoutException`. Replace `read()`, `read(byte[], int, int)` and `ensureBuffer()`; add `readTimedOut()`; update the class Javadoc's `{@link Http2Stream#takeData()}` to `{@link Http2Stream#pollData()}`:

```java
    @Override
    public int read() throws IOException {
        if (!ensureBuffer()) return -1;
        int b = current.get() & 0xFF;
        stream.bodyBytesConsumed(1);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!ensureBuffer()) return -1;
        int toRead = Math.min(len, current.remaining());
        current.get(b, off, toRead);
        stream.bodyBytesConsumed(toRead);
        return toRead;
    }

    /**
     * Makes bytes available, or returns {@code false} at a clean end of the body (END_STREAM).
     * A stream reset by the peer, or cut by the end of its connection, before its END_STREAM
     * fails the read: a truncated body must never pass for a complete one. A wait for the next
     * DATA frame longer than {@code readTimeout} fails it too (CHAPPE-024).
     */
    private boolean ensureBuffer() throws IOException {
        while (current == null || !current.hasRemaining()) {
            if (stream.isDataQueueEmpty()) {
                if (stream.isEndStreamReceived()) return false;
                if (stream.isCancelled()) throw streamReset();
            }
            try {
                current = stream.pollData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading HTTP/2 body", e);
            }
            if (current == null) throw readTimedOut();
            if (!current.hasRemaining()) {
                if (stream.isEndStreamReceived()) return false;
                if (stream.isCancelled()) throw streamReset();
            }
        }
        return true;
    }

    private SocketTimeoutException readTimedOut() {
        return new SocketTimeoutException("HTTP/2 request body read on stream " + stream.streamId()
                + " timed out after " + stream.bodyReadTimeout().toMillis() + " ms");
    }
```

- [ ] **Step 5: Wire it in `Http2Connection`**

Remove the `@SuppressWarnings("UnusedVariable")` line and its comment above `private final ServerConfig config;` (the field is now read).

In `handleHeaders`, replace the stream creation (line 275):

```java
        var stream = new Http2Stream(
                streamId,
                localSettings.initialWindowSize(),
                remoteSettings.initialWindowSize(),
                config.readTimeout(),
                increment -> creditStreamWindow(streamId, increment));
```

Replace `handleData` (the Task 4 version) with:

```java
    private void handleData(Http2Frame.DataFrame frame) throws IOException {
        int streamId = frame.streamId();
        int flowLength = flowControlledLength(frame);
        var stream = streams.get(streamId);
        if (stream == null) {
            discardData(streamId, flowLength);
            return;
        }

        // The connection window is credited back on receipt (replenishConnectionWindow), the stream
        // window only as the body is read (Http2Stream.bodyBytesConsumed): what a stream buffers is
        // bounded by its window, and a stream nobody reads never blocks the others (CHAPPE-024).
        connectionRecvWindow.addAndGet(-flowLength);
        if (flowLength > stream.recvWindow()) {
            // The peer overran the stream window (RFC 9113 §6.9.1): a stream error, the connection lives on.
            resetStream(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
            replenishConnectionWindow();
            return;
        }
        stream.consumeRecvWindow(flowLength);

        int dataLength = frame.data().remaining();
        if (dataLength > 0) {
            byte[] copy = new byte[dataLength];
            frame.data().get(copy);
            stream.offerData(ByteBuffer.wrap(copy));
        }
        // Padding is flow-controlled but never read: give it back to the stream at once.
        stream.bodyBytesConsumed(flowLength - dataLength);

        if (frame.endStream()) {
            stream.signalEndStream();
            stream.halfCloseRemote();
        }
        replenishConnectionWindow();
    }

    /** Sends the stream WINDOW_UPDATE the body reader earned by consuming bytes, unless the stream is gone. */
    private void creditStreamWindow(int streamId, int increment) {
        if (!streams.containsKey(streamId)) return;
        try {
            frameWriter.writeWindowUpdate(streamId, increment);
        } catch (IOException _) {
            // connection lost: the reader fails when the connection cancels its stream
        }
    }
```

- [ ] **Step 6: Update the `readTimeout` Javadoc for HTTP/2**

`ServerConfig.java`, replace the whole `@param readTimeout` paragraph written in Task 3 with:

```java
 * @param readTimeout bound on each blocking read of a request body (default: 30s): an HTTP/1.1
 *     socket read, including the drain of a body the handler left unread (the connection is then
 *     closed), or the wait for an HTTP/2 stream's next DATA frame (the stream is reset once the
 *     response is sent). The read throws {@link java.net.SocketTimeoutException}. It bounds each
 *     read, not the whole body. Zero or negative disables it. The wait for a next request is bounded
 *     by {@code idleTimeout} instead.
```

`Server.java`, in the `readTimeout` Javadoc, replace `Bound on each blocking read of an HTTP/1.1 request body (default: 30s)` with `Bound on each blocking read of a request body, HTTP/1.1 or HTTP/2 (default: 30s)`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl chappe-tests,chappe-conformance -am test -Dtest='Http2*Test,Grpc*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS, including `Http2StreamAbortTest`, `Http2TrailersTest`, `Http2FlowControlTest`, `Http2GrpcTransportTest`, `GrpcurlConformanceTest`.

- [ ] **Step 8: Record CHAPPE-024 in BUG.md**

Insert at the top of `BUG.md` (above CHAPPE-023):

```markdown
## CHAPPE-024 — the HTTP/2 stream window reopened on receipt, so a stream's buffer was unbounded

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): open the HTTP/2 stream window as the request body is read (CHAPPE-024)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`h2/Http2Connection`, `h2/Http2Stream`, `h2/Http2BodyInputStream`)
- **Symptom**: `handleData` sent the stream WINDOW_UPDATE as DATA arrived, not as the handler read it.
  A handler that did not read let the client push without limit into the stream's unbounded queue
  (heap). The stream window was not enforced either: DATA past it was accepted. DATA padding was never
  credited back, and `readTimeout` did not bound an HTTP/2 body read.
- **Minimal reproduction**: `chappe-tests/.../Http2RequestFlowControlTest#theStreamWindowReopensOnlyAsTheHandlerReadsTheBody`
  (a WINDOW_UPDATE for the stream came before any read).
- **Cause**: flow control was written for throughput (credit on receipt), with no back-pressure.
- **Fix**: `Http2BodyInputStream` reports what it consumes; `Http2Stream.bodyBytesConsumed` sends the
  stream WINDOW_UPDATE once half the window is pending. Padding is credited at receipt. DATA past the
  stream window is a stream error `FLOW_CONTROL_ERROR`. The connection window is still credited on
  receipt, so an unread stream never blocks the others; a connection buffers at most
  `MAX_CONCURRENT_STREAMS` (100) × 64 KiB. A body read waits at most `readTimeout` for the next DATA
  frame, then throws `SocketTimeoutException` (the HTTP/2 side of CHAPPE-022).

---
```

- [ ] **Step 9: Document the flow control and the HTTP/2 timeout**

`reference.adoc`, in the `[#request-body-limits]` section, insert after the bullet that ends with `DATA on a stream that was never opened is still a connection error.`:

```
* Over HTTP/2, the stream flow-control window reopens as the handler reads the body, not as DATA arrives: a stream buffers at most its window (64 KiB) of unread body, and a client that sends past the window gets `RST_STREAM(FLOW_CONTROL_ERROR)` for that stream. The connection window is still credited on receipt, so a stream that is not read never blocks the others. DATA padding counts against the window and is credited back at once.
```

`reference.adoc`, timeouts section: replace

```
bounds *each* blocking read of an HTTP/1.1 request body, over plaintext and TLS, including the drain of a body the handler left unread. When no byte arrives for that long, the read throws `java.net.SocketTimeoutException` and Chappe closes the connection.
```

with

```
bounds *each* blocking read of a request body. Over HTTP/1.1 (plaintext and TLS) it bounds each socket read, including the drain of a body the handler left unread; when it expires, the read throws `java.net.SocketTimeoutException` and Chappe closes the connection. Over HTTP/2 it bounds the wait for the stream's next DATA frame; when it expires, the read throws `java.net.SocketTimeoutException`, and the stream is reset once the response is sent.
```

`whats-new.adoc`: append as the last bullet:

```
* **HTTP/2 uploads follow the handler's reads** — the stream window used to reopen as DATA arrived, so a handler that did not read let a client push its whole upload into memory. It now reopens as the handler reads, a client overrunning it gets `RST_STREAM(FLOW_CONTROL_ERROR)`, and `readTimeout` bounds the wait for the next DATA frame (CHAPPE-024). xref:reference.adoc#request-body-limits[Reference — request body limits].
```

- [ ] **Step 10: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Stream.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2BodyInputStream.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java \
  chappe-api/src/main/java/io/vidocq/chappe/api/ServerConfig.java \
  chappe-api/src/main/java/io/vidocq/chappe/api/Server.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestFlowControlTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc
git commit -S -s -F - <<'EOF'
fix(http): open the HTTP/2 stream window as the request body is read (CHAPPE-024)

The stream WINDOW_UPDATE was sent as DATA arrived: a handler that did
not read let a client push its whole upload into the stream's queue.
The stream window was not enforced and padding was never credited.

The body reader now reports what it consumes, and the stream window is
credited once half of it is pending; padding is credited at receipt.
DATA past the stream window is a FLOW_CONTROL_ERROR stream error. The
connection window is still credited on receipt, so an unread stream
never blocks the others. readTimeout bounds the wait for the next DATA
frame (SocketTimeoutException).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 6: Cap HTTP/2 request bodies at `maxRequestSize` (CHAPPE-025), Foy cross-reference

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Stream.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2BodyInputStream.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` (`completeHeaders`, `handleData`, `dispatchStream`)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestBodyLimitTest.java`
- Modify: `BUG.md` (CHAPPE-025, plus the Foy cross-reference on CHAPPE-020 and CHAPPE-021), `docs/en/modules/ROOT/pages/reference.adoc`, `whats-new.adoc`, `migration.adoc`

**Interfaces:**
- Consumes: `RequestBodyTooLargeException` (Task 1); `H2TestConnection` (Task 4); `Http2Connection.resetStream`, `stopUnreadRequestBody`, `replenishConnectionWindow` (Task 4); the Task 5 `handleData`.
- Produces: in `Http2Stream`: `void bodyLimit(long limit)`, `long bodyLimit()`, `boolean exceedsBodyLimit(int n)` (connection thread only), `boolean startResponse()`, `boolean rejectBody()`, `boolean bodyTooLarge()`; in `Http2Connection`: `private void rejectOversizedBody(Http2Stream stream, boolean endStream) throws IOException`, `private static boolean isGrpc(HttpRequestImpl request)`.

- [ ] **Step 1: Write the failing tests**

Create `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestBodyLimitTest.java` (licence header first):

```java
package io.vidocq.chappe.tests;

import static io.vidocq.chappe.tests.H2TestConnection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHAPPE-025: {@code maxRequestSize} caps an HTTP/2 request body. Before the response starts, the read
 * fails with RequestBodyTooLargeException and the server answers 413; once the response is on its way,
 * the stream is reset with CANCEL. gRPC streams are not capped.
 */
class Http2RequestBodyLimitTest {

    private static final long LIMIT = 1000;

    private final CompletableFuture<String> outcome = new CompletableFuture<>();
    private final ObservedBody liveBody = new ObservedBody();
    private Server server;

    @BeforeEach
    void setUp() {
        server = Server.builder().port(0).maxRequestSize(LIMIT).handler(this::handle).build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        liveBody.close();
        if (server != null) server.stop();
    }

    /** {@code /read} reads the body and reports how it ended; {@code /live} answers a live body; else {@code pong}. */
    private Response handle(Request request) {
        return switch (request.path()) {
            case "/read" -> {
                try {
                    byte[] body = request.body().asInputStream().readAllBytes();
                    outcome.complete("read:" + body.length);
                    yield Response.ok("read:" + body.length);
                } catch (IOException e) {
                    outcome.complete(e.getClass().getSimpleName());
                    yield Response.ok("handler answer");
                }
            }
            case "/live" -> Response.builder().body(Body.of(liveBody, -1)).build();
            default -> Response.ok("pong");
        };
    }

    /** A live response body with nothing to send until it is closed; its close is observable. */
    private static final class ObservedBody extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                if (closed.await(20, TimeUnit.SECONDS)) throw new IOException("body closed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void aBodyOverTheLimitIsAnswered413WhenTheResponseHasNotStarted() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            for (int i = 0; i < 3; i++) h2.data(1, false, new byte[400]); // the third frame passes the limit
            h2.flush();
            assertEquals("RequestBodyTooLargeException", outcome.get(5, TimeUnit.SECONDS));
            assertEquals("413", h2.reply(1).status());
            assertEquals(NO_ERROR, h2.await(TYPE_RST_STREAM, 1).errorCode());
            h2.data(1, true, new byte[400]); // still in flight: ignored
            h2.headers(3, true, requestHeaders("GET", "/"));
            h2.flush();
            assertEquals(new Reply("200", "pong"), h2.reply(3));
        }
    }

    @Test
    void aBodyOverTheLimitResetsAStreamWhoseResponseHasStarted() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/live"));
            h2.flush();
            assertEquals("200", h2.await(TYPE_HEADERS, 1).headers().get(":status"));
            h2.data(1, false, new byte[1_500]);
            h2.flush();
            assertEquals(CANCEL, h2.await(TYPE_RST_STREAM, 1).errorCode());
            assertTrue(liveBody.closed.await(5, TimeUnit.SECONDS), "the response body of the reset stream must be released");
        }
    }

    @Test
    void aBodyAtTheLimitIsReadWhole() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read"));
            h2.data(1, false, new byte[600]);
            h2.data(1, true, new byte[400]);
            h2.flush();
            assertEquals(new Reply("200", "read:1000"), h2.reply(1));
        }
    }

    @Test
    void aGrpcStreamIsNotCappedByMaxRequestSize() throws Exception {
        try (var h2 = H2TestConnection.open(server.port())) {
            h2.headers(1, false, requestHeaders("POST", "/read", "content-type", "application/grpc"));
            h2.data(1, false, new byte[1_000]);
            h2.data(1, true, new byte[500]);
            h2.flush();
            assertEquals(new Reply("200", "read:1500"), h2.reply(1));
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl chappe-tests -am test -Dtest=Http2RequestBodyLimitTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `aBodyOverTheLimitIsAnswered413...` FAILS with a `TimeoutException` (the handler waits for more data); `aBodyOverTheLimitResetsAStream...` FAILS (no RST_STREAM, socket timeout). `aBodyAtTheLimitIsReadWhole` and `aGrpcStreamIsNotCappedByMaxRequestSize` pass: they guard the boundary and the gRPC exemption.

- [ ] **Step 3: Body limit and response phase in `Http2Stream`**

Fields, after `cancelled`:

```java
    // Request body limit (maxRequestSize) and the race between a 413 and the response (CHAPPE-025)
    private static final int RESPONSE_PENDING = 0;
    private static final int RESPONSE_STARTED = 1;
    private static final int BODY_TOO_LARGE = 2;
    private final AtomicInteger responsePhase = new AtomicInteger(RESPONSE_PENDING);
    private volatile long bodyLimit = Long.MAX_VALUE;
    private long bodyBytesReceived; // connection thread only
```

Methods, after `createBody()`:

```java
    void bodyLimit(long limit) {
        this.bodyLimit = limit;
    }

    long bodyLimit() {
        return bodyLimit;
    }

    /** Counts {@code n} received body bytes; {@code true} once the body went past its limit. Connection thread only. */
    boolean exceedsBodyLimit(int n) {
        bodyBytesReceived += n;
        return bodyBytesReceived > bodyLimit;
    }

    /**
     * Claims the response for the dispatch. {@code false} when the request body already went past its
     * limit: the dispatch then answers 413 instead of the handler's response.
     */
    boolean startResponse() {
        return responsePhase.compareAndSet(RESPONSE_PENDING, RESPONSE_STARTED);
    }

    /**
     * Marks the request body as over its limit. {@code true} when the response had not started: the
     * buffered body is dropped and a waiting reader wakes up and fails. {@code false} when the response
     * is already on its way: the caller resets the stream.
     */
    boolean rejectBody() {
        if (!responsePhase.compareAndSet(RESPONSE_PENDING, BODY_TOO_LARGE)) return false;
        dataQueue.clear();
        dataQueue.add(ByteBuffer.allocate(0)); // wakes a waiting reader, which then fails
        return true;
    }

    boolean bodyTooLarge() {
        return responsePhase.get() == BODY_TOO_LARGE;
    }
```

- [ ] **Step 4: Fail the read in `Http2BodyInputStream`**

Add the import `io.vidocq.chappe.api.RequestBodyTooLargeException`. In `ensureBuffer()`, make the first statement of the `while` body:

```java
            if (stream.bodyTooLarge()) throw new RequestBodyTooLargeException(stream.bodyLimit());
```

and make the first statement inside `if (!current.hasRemaining()) {` the same line.

- [ ] **Step 5: Count, reject and answer 413 in `Http2Connection`**

In `completeHeaders`, after `extractPseudoHeaders(req);`:

```java
        // maxRequestSize caps the body (CHAPPE-025), except for gRPC: a stream of messages, not one body.
        stream.bodyLimit(isGrpc(req) ? Long.MAX_VALUE : config.maxRequestSize());
```

In `handleData` (the Task 5 version), replace

```java
        int dataLength = frame.data().remaining();
        if (dataLength > 0) {
```

with

```java
        int dataLength = frame.data().remaining();
        if (stream.bodyTooLarge() || stream.exceedsBodyLimit(dataLength)) {
            rejectOversizedBody(stream, frame.endStream());
            replenishConnectionWindow();
            return;
        }
        if (dataLength > 0) {
```

Add after `handleData`:

```java
    /**
     * The request body went past maxRequestSize (CHAPPE-025). Before the response starts, the reader
     * fails with RequestBodyTooLargeException and the dispatch answers 413, then resets the rest of the
     * upload with RST_STREAM(NO_ERROR); once the response is on its way, the stream is reset with
     * CANCEL. The DATA is dropped either way and not credited back to the stream window: the client
     * stops at it.
     */
    private void rejectOversizedBody(Http2Stream stream, boolean endStream) throws IOException {
        if (stream.bodyTooLarge() || stream.rejectBody()) {
            if (endStream) {
                stream.signalEndStream();
                stream.halfCloseRemote();
            }
            return;
        }
        resetStream(stream, Http2ErrorCode.CANCEL);
    }

    private static boolean isGrpc(HttpRequestImpl request) {
        String contentType = request.headers().firstOrNull("content-type");
        return contentType != null && contentType.regionMatches(true, 0, "application/grpc", 0, 16);
    }
```

In `dispatchStream`, insert right before the `// A head HPACK cannot carry as is ...` comment (after the two gRPC branches):

```java
            // The request body went past maxRequestSize before the response started: 413 instead of the
            // handler's response, which is still released in the finally block (CHAPPE-025).
            if (!stream.startResponse()) {
                response = Response.of(StatusCode.PAYLOAD_TOO_LARGE);
            }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl chappe-tests,chappe-conformance -am test -Dtest='Http2*Test,Grpc*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 7: Record CHAPPE-025 and the Foy cross-reference in BUG.md**

Insert at the top of `BUG.md` (above CHAPPE-024):

```markdown
## CHAPPE-025 — HTTP/2 request bodies are not capped by maxRequestSize

- **Date**: 2026-10-11
- **Status**: FIXED (commit `fix(http): cap HTTP/2 request bodies at maxRequestSize (CHAPPE-025)`,
  branch `pr/ybl/chappe-request-body-bounds`)
- **Module**: `chappe-http` (`h2/Http2Connection`, `h2/Http2Stream`, `h2/Http2BodyInputStream`)
- **Symptom**: nothing in `h2/` read `maxRequestSize`: an HTTP/2 request body of any size was handed
  to the handler.
- **Minimal reproduction**: `chappe-tests/.../Http2RequestBodyLimitTest#aBodyOverTheLimitIsAnswered413WhenTheResponseHasNotStarted`
  (`maxRequestSize(1000)`, 1,200 bytes: the handler kept waiting for more).
- **Cause**: the limit was only wired in the HTTP/1.1 `setupBody()`.
- **Fix**: each stream counts its DATA. Past `maxRequestSize`, while the response has not started, the
  buffered body is dropped, the read fails with `RequestBodyTooLargeException`, the dispatch answers
  `413` and then resets the rest of the upload with `RST_STREAM(NO_ERROR)` (CHAPPE-023); once the
  response has started, the stream is reset with `RST_STREAM(CANCEL)`. A compare-and-set on the
  stream's response phase decides between the two. gRPC streams (`content-type: application/grpc…`)
  are not capped.
- **Related**: Foy BUG-20261011-11 (`foy/BUG.md`), which CHAPPE-020, CHAPPE-021 and this entry fix on
  the chappe side. That entry is closed from Foy, once Foy builds against a chappe carrying them.

---
```

In the CHAPPE-020 and CHAPPE-021 entries, add as the last bullet of each (after `- **Fix**: ...`):

```markdown
- **Related**: Foy BUG-20261011-11 (`foy/BUG.md`), closed from Foy once it builds against this fix.
```

- [ ] **Step 8: Document the HTTP/2 cap**

`reference.adoc`, in the `[#request-body-limits]` section, insert after the bullet that ends with `DATA padding counts against the window and is credited back at once.`:

```
* Over HTTP/2, `maxRequestSize` caps the DATA of each stream. While the response has not started, the body read fails with `RequestBodyTooLargeException`, Chappe answers `413` instead of the handler's response and resets the rest of the upload with `RST_STREAM(NO_ERROR)`; once the response is on its way, the stream is reset with `RST_STREAM(CANCEL)`. gRPC streams (`content-type: application/grpc…`) are not capped: they carry a sequence of messages, not one body, and gRPC bounds each message with its own limits (the receive message size of the gRPC layer above Chappe). Flow control still bounds what a gRPC stream buffers.
```

`whats-new.adoc`: replace the `:description:` line

```
:description: Everything that changed in Chappe since the {release-version} release — the write timeout is applied, and connections close without destroying the last response.
```

with

```
:description: Everything that changed in Chappe since the {release-version} release — the write and read timeouts are applied, request bodies are bounded, and connections close without destroying the last response.
```

and append as the last bullet:

```
* **HTTP/2 request bodies are capped** — `maxRequestSize` now applies to HTTP/2: `413` while the response has not started, `RST_STREAM(CANCEL)` after; gRPC streams are exempt (CHAPPE-025). xref:reference.adoc#request-body-limits[Reference — request body limits].
```

`migration.adoc`: append as the last bullet of the `Upgrading` list:

```
* **HTTP/2 bodies over `maxRequestSize` are refused** — an HTTP/2 request body past `maxRequestSize` now gets a `413` (or `RST_STREAM(CANCEL)` once the response started) instead of being read to its end. gRPC streams are not capped. See xref:reference.adoc#request-body-limits[Reference — request body limits].
```

- [ ] **Step 9: Full build**

Run: `./mvnw -ntp spotless:apply && ./mvnw -ntp install`
Expected: BUILD SUCCESS, every module's tests green.

- [ ] **Step 10: Commit**

```bash
git add chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Stream.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2BodyInputStream.java \
  chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java \
  chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2RequestBodyLimitTest.java \
  BUG.md docs/en/modules/ROOT/pages/reference.adoc docs/en/modules/ROOT/pages/whats-new.adoc \
  docs/en/modules/ROOT/pages/migration.adoc
git commit -S -s -F - <<'EOF'
fix(http): cap HTTP/2 request bodies at maxRequestSize (CHAPPE-025)

maxRequestSize was never applied to HTTP/2: a request body of any size
reached the handler.

Each stream now counts its DATA. Past the cap, before the response
starts, the read fails with RequestBodyTooLargeException and the
server answers 413, then resets the rest of the upload with
RST_STREAM(NO_ERROR); once the response has started, the stream is
reset with CANCEL. gRPC streams are not capped.

With CHAPPE-020 and CHAPPE-021 this fixes the chappe side of Foy
BUG-20261011-11, which is closed from Foy.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

## Self-Review

0. **Controller rulings (2026-10-11).** P-1 gRPC exemption kept and documented (Task 6 reference text); P-2 `maxDrainSize` default 1 MiB (Task 2); P-3 `RST_STREAM(CANCEL)` after a started response (Task 6); P-4 an HTTP/1.1 `Content-Length` above `maxRequestSize` answers `413` instead of `400`, failing test first, migration note (Task 1).
1. **Spec coverage.** PR 1 (c): chunked cap and 413 for an oversized Content-Length → Task 1; bounded drain with a new `ServerConfig` component (ruling Q6) → Task 2; `readTimeout` wired (ruling Q7) → Task 3 (HTTP/1.1) and Task 5 (HTTP/2). PR 1 (d): RST_STREAM(NO_ERROR) on early response and late-frame tolerance with connection-window accounting → Task 4; consumption-driven WINDOW_UPDATE and bounded per-stream buffer → Task 5; size cap with 413 / RST_STREAM → Task 6. Every task has a BUG.md entry and a docs update; the last task carries the Foy BUG-20261011-11 cross-reference. No BENCHMARKS.md entry: no numbers are produced.
2. **Placeholders.** None: every code step carries the code, every docs step the exact text and anchor.
3. **Type consistency.** `RequestBodyTooLargeException(long)` / `limit()` (T1, T6); `HttpBodyReader.chunked(…, long)` (T1, used in T3); `drain(InputStream, long)` (T2); `ServerConfig.maxDrainSize()` (T2); `TimedReadChannel.of(…)` (T3); `ResetStreams.add/contains`, `resetStream`, `discardData`, `flowControlledLength`, `replenishConnectionWindow`, `stopUnreadRequestBody` (T4, used in T5, T6); `Http2Stream` 5-argument constructor, `bodyBytesConsumed`, `pollData`, `bodyReadTimeout` (T5, used in T6); `bodyLimit`, `exceedsBodyLimit`, `startResponse`, `rejectBody`, `bodyTooLarge` (T6); `H2TestConnection.{open, headers, data, paddedData, frame, flush, read, await, poll, reply, requestHeaders, literalWithIndexing, indexed, concat}`, `Frame`, `Reply` (T4, used in T5, T6).
4. **Review Focus.** Each of the five lines has its test in the owning task (named in the section).
