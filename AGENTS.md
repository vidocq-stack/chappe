# Repository Guidelines

## Project Structure & Module Organization

Chappe is a Maven 4 multi-module Java 25 project using the standard Maven layout:

- `chappe-api`: public API types, including `Server`, `Router`, `Request`, and `Response`.
- `chappe-http`: HTTP/1.1, HTTP/2, TLS, parsing, and wire-level code.
- `chappe-core`: server runtime, provider integration, lifecycle, and virtual threads.
- `chappe-cli`: standalone `chappe serve` launcher and mini-YAML configuration.
- `chappe-tests`: integration tests with real server sockets.
- `chappe-conformance`: protocol conformance tests.
- `chappe-bench`: JMH benchmarks and Docker benchmark tooling.
- `chappe-examples`, `chappe-static-index-maven-plugin`, and `docs`: examples, build plugin, and Antora docs.

Java code is under `src/main/java`; tests are under `src/test/java`; fixtures and static assets are under `src/test/resources`.

## Build, Test, and Development Commands

Use SDKMAN to match the repository toolchain:

- `sdk env`: selects Java 25 and Maven `4.0.0-rc-5` from `.sdkmanrc`.
- `mvn -ntp test`: runs unit, integration, and conformance tests in the reactor.
- `mvn -ntp -pl chappe-cli -am package`: builds the CLI and required modules.
- `mvn -ntp -Pquality verify`: runs verification with JaCoCo reporting.
- `java --enable-preview -jar chappe-cli/target/chappe-cli-*-shaded.jar serve --root ./site --port 8080`: runs the packaged CLI locally.

If Maven reports unsupported `modelVersion 4.1.0`, switch back to Maven 4.

## Coding Style & Naming Conventions

Use Java 25 with preview enabled. Keep packages and JPMS modules under `io.vidocq.chappe.*`. Follow existing 4-space indentation, fluent builder formatting, and package-private implementation classes where possible. Prefer records for immutable data, sealed interfaces for closed hierarchies, exhaustive switch expressions, and zero external runtime dependencies beyond the JDK.

## Testing Guidelines

Tests use JUnit Jupiter from JUnit 6 and Surefire with `--enable-preview`. Name test classes `*Test` and place socket-based integration tests in `chappe-tests` or protocol cases in `chappe-conformance`. Prefer real `HttpClient` or raw socket checks over HTTP mocking. Add focused tests near the changed module, then run a narrow command first, for example `mvn -ntp -pl chappe-http test`.

## Commit & Pull Request Guidelines

History uses short French descriptions and Conventional Commit-style prefixes, for example `docs: ...`, `ci(pr): ...`, and `fix(pr): ...`. Prefer `<type>(scope): summary` when practical. Pull requests should explain the behavioral change, list tests run, link issues, and include CLI output or screenshots only when user-visible behavior changes.

## Agent-Specific Instructions

Keep changes scoped and root-cause oriented. Do not introduce runtime dependencies without explicit approval. Preserve the zero-dependency server goal and avoid unrelated refactors while touching performance-sensitive HTTP paths.
