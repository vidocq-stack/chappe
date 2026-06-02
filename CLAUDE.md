# Chappe - Claude Code Guidelines

> Claude Chappe (1763–1805) invented the semaphore telegraph — a network of optical towers
> that covered all of France and transmitted messages across hundreds of km in a few minutes.
> It was literally the fastest HTTP server of its time.

## Project

High-performance HTTP server in pure Java 25 (zero dependencies outside the JDK), designed to serve
as the foundation for the future JAX-RS and Servlet projects of the Vidocq ecosystem.

### Implemented protocols
- **HTTP/1.1** — RFC 9110/9112 (keep-alive, chunked, pipelining, tested conformance)
- **HTTP/2** — RFC 9113 (multiplexing, HPACK Huffman, flow control, CONTINUATION)
- **HTTPS** — TLS via SSLContext/SSLEngine, ALPN h2 + http/1.1
- **WebSocket** — RFC 6455 over HTTP/1.1 (handshake `Sec-WebSocket-Accept`, TEXT/BINARY/PING/PONG/CLOSE framing, fragmentation, UTF-8 validation, mandatory client masking, bidirectional close handshake, auto-PONG)
- **gRPC (transport)** — core framing (5-byte prefix) over HTTP/2 + trailers (RFC 9113 §8.1); 4 modes (unary, server-stream, client-stream, bidi); byte-level `GrpcCall` SPI; serialization (protobuf, JSON…) delegated to extensions (`champollion` for protobuf)
- **HTTP/3** — RFC 9114 (future target, QUIC via JDK 26+)

### Architecture
- Virtual Threads (Project Loom) — one virtual thread per connection
- Scoped Values (JEP 506) — `RequestContext.CURRENT` propagated before each handler
- Zero-copy I/O — `FileChannel.transferTo()` for static files
- Java Modules (JPMS) — each module has a `module-info.java`
- ServiceLoader SPI — `ServerProvider` to discover the implementation

### Vauban integration (planned, not active yet)
Chappe works **autonomously without Vauban**. CDI integration will happen
through the `vidocq-servlet` and `vidocq-jaxrs` extensions, which will use
Vauban for component lifecycle. Chappe itself uses `ServiceLoader` (not CDI).
- `vauban-api`/`vauban-core` are declared in the parent POM but not used by the modules
- Vauban source: `../vauban/` (sibling project in the monorepo)

### Modules
| Module | Description |
|---|---|
| `chappe-api` | Public API: `Server`, `Router`, `Handler`, `Request`, `Response`, `Filter`, `StaticFileHandler`, `MimeTypes`, `AcceptEncoding`, `RequestContext`, `WebSocket`, `WebSocketHandler`, `CloseCodes`, `GrpcHandler`, `GrpcCall`, `GrpcStatus` |
| `chappe-http` | HTTP/1.1, HTTP/2 (with trailers), WebSocket (RFC 6455), and gRPC core (transport, `grpc` sub-package) protocols, TLS `SslHandler`, `ByteBufferPool` |
| `chappe-core` | Server engine, virtual threads, protocol detection, lifecycle |
| `chappe-cli` | Standalone `chappe serve` CLI launcher (mini-YAML, fat jar, jlink) — see `chappe-cli/README.md` |
| `chappe-tests` | Integration tests |
| `chappe-bench` | Benchmarks: Jetty/Helidon/JDK comparison, throughput/latency |
| `chappe-conformance` | HTTP/WS conformance suite (53 RFC 9110/9112/9113/6455 tests); gRPC tests in `chappe-tests/Http2GrpcTransportTest` |
| `chappe-examples` | Usage examples |
| `chappe-static-index-maven-plugin` | Maven plugin: O(1) index + `.gz` sidecars at build time (`<compress>gzip</compress>`) |

### Extension SPI
Chappe provides the hooks for Servlet/JAX-RS/WebSocket/gRPC extensions:
- **`Router.webSocket(pattern, handler)`** — RFC 6455 WebSocket endpoint (automatic handshake, TEXT/BINARY/PING/PONG/CLOSE frames, fragmentation, UTF-8 validation, optional subprotocol)
- **`Router.grpc(pattern, GrpcHandler)`** — transport-only gRPC endpoint; exposes byte-level `GrpcCall.receive()/send()/complete(status, msg)` (protobuf/JSON serialization is the extension's responsibility, e.g. `champollion`); rejects HTTP/1.1 with 505 and non-grpc content-type with 415
- **`Router.mount(prefix, handler)`** — path-prefix registration with automatic stripping
- **`Request.contextPath()`/`pathInfo()`** — path relative to the mount point
- **`Request.attribute(key, value)`** — mutable per-request attributes (Servlet compatibility)
- **`Request.remoteAddress()`/`isSecure()`/`scheme()`** — connection metadata
- **`RequestContext.CURRENT`** — ScopedValue propagated before each handler
- **`Body.ofOutputStream()`** — streaming body (Servlet OutputStream compatibility)
- **`Body.ofFile()`** — zero-copy via `FileChannel.transferTo()` (sendfile)
- **`StaticFileHandler.builder()`** — static files with fallback chain (filesystem → classpath), in-memory cache, ETag, Cache-Control, `notFoundFile`/`spaFallback`, `preferPrecompressed` (`.br`/`.gz` sidecars), `cleanUrls` (extensionless `/admin` → `admin.html`)
- **`Filter.addHeader()`/`addHeaderIf()`/`addHeaderIfEnv()`/`gzip()`** — declarative header + compression middleware
- **`AcceptEncoding.parse()`/`accepts()`** — `Accept-Encoding` negotiation (RFC 9110 §12.5.3)
- **`MimeTypes.detect()`** — MIME detection by extension (26+ types)

### Server validation
The server must be validated on three axes:
- **Protocol conformance** — verify RFC compliance with an exhaustive test suite (headers, chunked encoding, status codes, HTTP/2 framing, HPACK, flow control, stream priorities)
- **Performance** — reproducible JMH benchmarks: throughput (req/sec), latency (p50/p99/p999), memory allocation (GC pressure), scalability (concurrent connections)
- **Robustness** — prolonged load tests (soak tests), malformed requests, slow clients (slowloris), abandoned connections, backpressure, memory limits

## 1. Default Plan Mode

- Enter plan mode for any non-trivial task (3+ steps or architecture decisions)
- If something goes wrong, STOP and re-plan immediately
- Use plan mode for verification steps, not just implementation
- Write detailed specs up front to reduce ambiguity

## 2. Subagent Strategy

- Use subagents frequently to keep the main context window clean
- Delegate research, exploration, and parallel analysis to subagents
- For complex problems, use more compute via subagents
- Assign one task per subagent for focused execution

## 3. Continuous Improvement Loop

- After any user correction, update `tasks/lessons.md`
- Write rules to avoid repeating the same mistake
- Iterate relentlessly on those lessons
- Review the lessons at the start of each session

## 4. Verification Before Completion

- Never mark a task complete without proof that it works
- Compare behavior between main and the changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate the fix

## 5. Demand Elegance (Balanced)

- For non-trivial changes, ask: "Is there a more elegant solution?"
- If a fix looks hacky, ask: "Knowing everything I know, implement the elegant solution."
- Skip this for simple fixes — do not over-engineer
- Challenge your own work before presenting it

## 6. Autonomous Bug Fixing

- When receiving a bug report: just fix it
- Use logs, errors, and failing tests to diagnose
- Require zero context switching from the user
- Fix failing CI tests automatically

## Task Management

1. **Plan** – Write the plan in `tasks/todo.md` with checkable items
2. **Verify the plan** – Confirm the plan before implementation
3. **Track progress** – Mark items complete as work proceeds
4. **Explain changes** – Provide a high-level summary at each step
5. **Document results** – Add a review section to `tasks/todo.md`
6. **Capture lessons** – Update `tasks/lessons.md` after fixes

## Environment

- Use **sdkman** to manage Java and Maven versions
- Required: **Java 25** (`sdk use java 25-open` or equivalent)
- Required: **Maven 4** (`sdk use maven 4.0.0-rc-5`)
- If `mvn` fails with "modelVersion 4.1.0 not supported", Maven `current` was reset to 3.x — switch back to 4.x

## Context Mode

- Use `ctx_batch_execute` for commands producing lots of output (builds, tests, logs)
- Use `ctx_search` for follow-up searches after a batch_execute
- Use `ctx_execute` / `ctx_execute_file` for data analysis, log parsing, transformations
- **Never** use Bash for commands producing >20 lines of output — use context-mode instead
- **Never** use ctx_execute/ctx_execute_file to create or modify files — use Write/Edit
- Read is reserved for files that will be edited afterward — for analysis, use ctx_execute_file

## Code Conventions

### Naming
- Packages: `io.vidocq.chappe.*`
- Maven GroupId: `io.vidocq.chappe`
- JPMS modules: `io.vidocq.chappe.*`

### Standards
- Zero dependencies outside the JDK — that is the project's absolute rule
- All code uses virtual threads — never use a classic thread pool
- Prefer records over classes for immutable objects
- Use sealed interfaces for closed type hierarchies
- Exhaustive pattern matching with switch expressions
- Use `java.lang.foreign` for critical memory operations

### Tests
- JUnit 6 (jupiter)
- Integration tests with real sockets (no HTTP mocking)
- Benchmarks with JMH in a separate module when needed

### Performance (measured results, see BENCHMARKS.md)
- **101K ops/s** JMH concurrent throughput on 8t (run 2026-05-20); **96K req/s** in-process closed-loop NIO (run 2026-04-16, validated)
- **p99 latency = 57 µs** raw socket (JMH run 2026-05-20, 317k samples) — 17× below the 1ms target
- Optimizations: write coalescing, zero-alloc headers, thread-local buffer pool, fast path 200 OK
- Zero allocation on the hot path (reuse buffers)
- **Language** — commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

## Core Principles

### Simplicity first
Every change must be as simple as possible and minimize impact on the code.

### No laziness
Find root causes. Avoid temporary fixes. Maintain senior engineering standards.

### Zero dependency
If a feature requires an external dependency, it has no place in Chappe.
The only exception is the test framework (JUnit).
