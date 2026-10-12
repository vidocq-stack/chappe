# HTTP/2 Header-List Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The HTTP/2 header-list limit is derived from `ServerConfig.maxHeaderSize`, as HTTP/1.1's is. A request whose header list is over the limit but still decodable gets **431** on its own stream, and the other streams of the connection are not affected. Abusive header blocks (a CONTINUATION flood, HPACK amplification) still end the connection with `GOAWAY(ENHANCE_YOUR_CALM)`. This fixes the open follow-up (b) of CHAPPE-024, as CHAPPE-032.

**Architecture:** There are three limits.
- **Soft limit S.** It is advertised as `SETTINGS_MAX_HEADER_LIST_SIZE` and enforced per stream, with a 431.
- **Hard encoded cap H_enc.** It bounds the buffered header block plus its CONTINUATIONs, and so the heap. Going over it sends GOAWAY.
- **Hard decoded cap H_dec.** It catches HPACK amplification. Going over it sends GOAWAY.

`HpackDecoder` always decodes a whole block, so the dynamic table stays in sync. It reports whether the soft limit was exceeded, and throws only at H_dec.

**Tech Stack:** Java 25, zero dependencies, JUnit 5 in `chappe-tests` (raw h2 frames through `H2TestConnection`).

**Spec:** the maintainer's go, plus section D of the controller research (`chappe-timeouts-research.md`, D section, with file:line). RFC 9113 §6.5.2 and §10.5.1, RFC 7541 §4.1.

## Global Constraints

- **Language and runtime.** English everywhere. Say "Java Modules", never the abbreviation. Zero runtime dependencies. Use virtual threads, and `ReentrantLock`, never `synchronized` around I/O.
- **TDD.** Write the failing test first. Record RED and GREEN with the number of tests that ran.
- **Formulas (controller rulings).**
  - `S = maxHeaderSize + 32 * 100`, clamped to `Integer.MAX_VALUE`. That is 11,392 with the default `maxHeaderSize` of 8,192. The 32 is the per-field overhead of RFC 9113 §6.5.2, and 100 is HTTP/1.1's header-count cap. A head that fits in HTTP/1.1 therefore fits in HTTP/2.
  - `H_enc = max(4 * S, 65_536)`.
  - `H_dec = max(8 * S, 262_144)`.
  - Every product and sum saturates at `Integer.MAX_VALUE`.
- **Units.** Header-list size counts octets: name length plus value length plus 32 per field, following RFC 9113 §6.5.2. Check how `HpackDecoder` turns octets into `String`s. If it uses ISO-8859-1, `String.length()` already equals the octet count, so keep it and add a comment. Otherwise, count the octets.
- **HPACK consistency.** Every header block that arrives is fully decoded, whether its stream is served, refused, reset or over S. A decoder that stops mid-block is a bug. The only exception is a hard-cap GOAWAY, which ends the connection.
- **No handler call over S.** For a request over S, the application handler is never invoked. Its body is never read: after the 431, if the request's END_STREAM has not arrived, send `RST_STREAM(NO_ERROR)`, as CHAPPE-023 does.
- **Trailers over S.** They are refused with `RST_STREAM(ENHANCE_YOUR_CALM)` on that stream only, and the connection stays up.
- **Advertised setting.** `Http2Settings.DEFAULT` stays as it is: it is public. Add `Http2Settings.serverDefaults(int maxHeaderListSize)`. The server advertises S.
- **Bug ids.** CHAPPE-032 is new, and FIXED. In CHAPPE-024, rewrite follow-up (b) as "(FIXED in CHAPPE-032)". CHAPPE-031 is already taken by the idle-timeout overflow; re-check the top of BUG.md.
- **Commits.**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - The last paragraph is `Co-Authored-By: Claude <model> <noreply@anthropic.com>` followed by `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **Build.**
  - Run `./mvnw -ntp …` from the chappe root with `-Dmaven.repo.local=<scratchpad>/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository`.
  - Select tests with `-Dtest='A,B'`. Spotless must pass.

## Review Focus

1. **The HPACK dynamic table after an oversized block.** A later request on the same connection that references an entry the oversized block inserted must decode correctly (Task 2, `theHpackContextSurvivesAnOversizedBlock`).
2. **A CONTINUATION flood.** It must still be cut at H_enc. Heap per connection is bounded by H_enc, because CONTINUATIONs are contiguous (Task 2, `aContinuationFloodStillEndsTheConnection`).
3. **HPACK amplification.** Indexed fields that point at a large dynamic-table entry must be cut at H_dec (Task 1 unit test and Task 2, `hpackAmplificationEndsTheConnection`).
4. **Oversized trailers.** They are refused on their stream only (Task 2, `oversizedTrailersResetOnlyTheirStream`).
5. **A refused or reset stream whose late HEADERS are oversized.** They are decoded under the hard cap only, so neither GOAWAY nor a 431 is sent for a stream that is no longer served (Task 2, `lateOversizedHeadersOnAResetStreamAreIgnored`).

---

### Task 1: Soft and hard limits in `HpackDecoder`

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/HpackDecoder.java`
- Test: add a test class next to existing HPACK unit tests (find them with `grep -rl HpackDecoder chappe-tests chappe-http/src/test 2>/dev/null`), or create `chappe-tests/.../HpackDecoderLimitTest.java` if the decoder is reachable from `chappe-tests`. Check the module exports; if it is not reachable, test through Task 2's end-to-end tests and say so.

**Interfaces:**
- Produces:
  - `public HpackDecoder(int maxTableSize, int softLimit, int hardLimit)`. The existing two-argument constructor means `softLimit == hardLimit`, which keeps today's behaviour.
  - `public boolean decode(ByteBuffer block, HeaderSink sink)`. Keep the existing sink type. It returns `true` when the list is within the soft limit.
    - It always decodes the whole block, updating the dynamic table.
    - It forwards fields to `sink` only while the running total is within `softLimit`.
    - It throws `Http2ConnectionException(ENHANCE_YOUR_CALM)` only when the running total exceeds `hardLimit`.
  - If `decode` returns `void` today and many callers exist, add an overload instead and report it.

- [ ] **Step 1: Write the failing tests.**
  - A block within S: all fields reach the sink, and `decode` returns `true`.
  - A block over S but under H: `decode` returns `false`. Only the fields within S reached the sink. A **second** block that references, by index, a dynamic-table entry inserted at the end of the first block decodes to the right name and value. This proves the whole first block updated the table.
  - A block that goes over H throws `ENHANCE_YOUR_CALM`.
  - Amplification: one large literal-with-indexing entry of about 4 KiB, then a block of N indexed references to it whose decoded total exceeds H, while its encoded size stays tiny. It throws.
  - The size counts octets plus 32 per field. Use a non-ASCII obs-text value (bytes ≥ 0x80) to check that the count is in octets.

- [ ] **Step 2: RED.** Expected: a compile failure on the new constructor or return value, or failing assertions.

- [ ] **Step 3: Implement.**
  - Keep a running `total`. Decode every field.
  - On each field: `total += nameOctets + valueOctets + 32`.
  - If `total > hardLimit`, throw.
  - Else, if `total <= softLimit`, call `sink.accept(...)`.
  - Else, set `within = false` and do not forward the field.
  - Return `within`.
  - Size-table updates and dynamic-table insertions happen as today, whatever the soft limit says.

- [ ] **Step 4: GREEN.** Run the new tests and every existing `Http2*` and `Hpack*` test. Report the counts.

- [ ] **Step 5: Commit.** `refactor(http): let the HPACK decoder separate a soft header-list limit from a hard one`.

---

### Task 2: Derived limit, 431 per stream, and hard caps in `Http2Connection` (CHAPPE-032)

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Settings.java` (`serverDefaults`)
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java`. The changes are:
  - local settings;
  - the decoder construction;
  - `boundHeaderBlock`, now limited by H_enc;
  - `completeHeaders`;
  - `discardHeaderBlock` and `handleContinuation` on not-served paths;
  - trailers;
  - a new 431 responder.
- Modify: `chappe-tests/src/test/java/io/vidocq/chappe/tests/Http2HeaderBlockLimitTest.java` (its `MAX_HEADER_LIST_SIZE = 8_192` constant and the existing flood tests).
- Modify: `BUG.md`; `docs/en/modules/ROOT/pages/{reference,internals,whats-new,migration}.adoc`; the `maxHeaderSize` Javadoc in `chappe-api/.../ServerConfig.java`.

**Interfaces:**
- Consumes: Task 1's `HpackDecoder(int, int, int)` and the boolean `decode`.
- Produces: `public static Http2Settings serverDefaults(int maxHeaderListSize)`.

- [ ] **Step 1: Write the failing tests** in `Http2HeaderBlockLimitTest`. Use the existing raw-frame helpers; keep the existing tests and update their constants to the new caps.
  - **Limits (with defaults: S = 11,392; H_enc = 65,536; H_dec = 262,144):**
    - `theAdvertisedLimitDerivesFromMaxHeaderSize`: the server's SETTINGS carries `MAX_HEADER_LIST_SIZE = 11392`. With `maxHeaderSize(32768)` it carries `32768 + 3200`.
    - `aHeaderListOverTheLimitGets431OnItsStreamOnly`: stream 1 carries a header of about 12 KiB, encoded as a literal without indexing, so it is under H_enc. It gets `:status 431`. Stream 3, sent afterwards on the same connection, gets 200. The handler counted only one call, for stream 3.
    - `theHpackContextSurvivesAnOversizedBlock`: stream 1's oversized block ends with a literal **with incremental indexing** (`x-marker: m1`). Stream 3 references that entry by its dynamic index, and the handler echoes `x-marker`, which must be `m1`. This follows the `Http2EarlyResponseTest#aRefusedStreamKeepsTheConnectionAndTheHpackContext` pattern.
    - `aRaisedMaxHeaderSizeLetsALargeHeaderListThrough`: with `maxHeaderSize(32768)`, a 20 KiB header gives 200.
    - `anOversizedRequestWithABodyIsResetAfterThe431`: HEADERS over S, without END_STREAM. The client gets 431, then `RST_STREAM(NO_ERROR)`, and DATA sent afterwards does not kill the connection.
  - **Abuse:**
    - `aContinuationFloodStillEndsTheConnection`: CONTINUATION frames without END_HEADERS past 65,536 encoded bytes give GOAWAY(ENHANCE_YOUR_CALM). This adapts the existing flood tests.
    - `hpackAmplificationEndsTheConnection`: insert one 4 KiB entry, then send a block of 70 indexed references to it, about 287 KiB decoded. That gives GOAWAY(ENHANCE_YOUR_CALM).
  - **Trailers and not-served streams:**
    - `oversizedTrailersResetOnlyTheirStream`: a POST with a small body, then trailers of about 12 KiB. That stream gets `RST_STREAM(ENHANCE_YOUR_CALM)`, and a new stream on the same connection gets 200.
    - `lateOversizedHeadersOnAResetStreamAreIgnored`: after the server has reset a stream, a HEADERS block on it over S but under H_enc draws no GOAWAY and no 431. The next stream gets 200.
  - Keep `aHeaderBlockSplitAcrossContinuationFramesIsStillServed` unchanged.

- [ ] **Step 2: RED.** Run `-Dtest='Http2HeaderBlockLimitTest'`. Report which tests fail and why: today's code sends GOAWAY at 8,192.

- [ ] **Step 3: Implement.**
  - **Settings.**
    - Compute `S`, `H_enc` and `H_dec` once per connection from `config.maxHeaderSize()`, with saturating arithmetic.
    - Advertise `localSettings = Http2Settings.serverDefaults(S)`.
    - Build the decoder as `new HpackDecoder(localSettings.headerTableSize(), S, H_dec)`.
  - **`boundHeaderBlock`.** It compares against `H_enc`, not against `localSettings.maxHeaderListSize()`.
  - **`completeHeaders`, request headers.**
    - When `decode` returns `false`, skip pseudo-header extraction and the handler.
    - Start one virtual thread that sends `Response.of(StatusCode.of(431, "Request Header Fields Too Large"))` through the existing `sendResponse(stream, …)`. If the request's END_STREAM has not arrived, send `RST_STREAM(NO_ERROR)` through the existing early-response path. Then close the stream, so its id joins the reset ring.
    - Reuse the code that finishes `dispatchStream` (the early-response tail), rather than duplicating it. Extract a small private method if needed.
  - **`completeHeaders`, trailers.** When `decode` returns `false`, call `resetStream(stream, ENHANCE_YOUR_CALM)` through the existing helper. The connection stays up.
  - **Not-served paths.** `discardHeaderBlock` and `handleContinuation` for refused or reset streams decode with the hard cap only, and ignore the soft result. A not-served stream never gets a 431.
  - **`StatusCode`.** Use `StatusCode.of(431, …)`. Add a constant only if `StatusCode` already has similar ones and adding one is idiomatic.

- [ ] **Step 4: GREEN.**
  - Run `Http2HeaderBlockLimitTest` 3 times.
  - Then run every `Http2*`, `Grpc*` and `GrpcWeb*` class (list them with `ls chappe-tests/src/test/java/io/vidocq/chappe/tests | grep -i -e http2 -e grpc -e hpack`).
  - Then run the full `./mvnw -ntp install`. Report the totals.

- [ ] **Step 5: Mutation checks.**
  - Make `HpackDecoder.decode` stop at the soft limit, as before. `theHpackContextSurvivesAnOversizedBlock` must fail.
  - Make `boundHeaderBlock` use `Integer.MAX_VALUE`. `aContinuationFloodStillEndsTheConnection` must fail.
  - Restore both, and report the checks.

- [ ] **Step 6: Update BUG.md and the docs.**
  - **BUG.md.** Add a new top entry, CHAPPE-032 (FIXED): symptom, minimal repro (the tests), cause, and the three limits with their formulas. In CHAPPE-024, rewrite follow-up (b) as "(FIXED in CHAPPE-032)".
  - **reference.adoc.** Rewrite the `maxHeaderSize` bullet so it states:
    - the derived HTTP/2 limit, with its formula;
    - the 431 per stream;
    - the hard caps, which answer with GOAWAY;
    - that the setting is advisory, since RFC-compliant clients do not send larger lists.

    Also update the RFC 9113 conformance row if it mentions the limit.
  - **internals.adoc.** Update the `Http2Settings` line and the HTTP/2 section.
  - **whats-new.adoc.** Check the "8,192" wording of the CONTINUATION cap bullet.
  - **migration.adoc.** Add a behaviour note:
    - the advertised `SETTINGS_MAX_HEADER_LIST_SIZE` changes from 8,192 to 11,392 by default;
    - an oversized but decodable header list now gets 431 instead of GOAWAY;
    - the CONTINUATION cap is now 65,536 encoded bytes by default.
  - **`ServerConfig` Javadoc.** The `maxHeaderSize` Javadoc gets the same explanation.

- [ ] **Step 7: Commit.** `fix(http): answer 431 per stream for an oversized HTTP/2 header list (CHAPPE-032)`.

---

## Self-review notes (plan author)

- **Coverage.**
  - The derived limit: Task 2.
  - The 431 per stream: Task 2.
  - The hard caps H_enc and H_dec: Tasks 1 and 2.
  - HPACK consistency: Tasks 1 and 2.
  - Trailers: Task 2.
  - Not-served streams: Task 2.
  - Docs: Task 2.
- **Risk.** This plan is high-risk for HPACK state and the CHAPPE-024 flood defence. Both are covered by mutation-checked tests.
