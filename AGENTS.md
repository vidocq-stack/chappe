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

- `sdk env`: selects Java 25 and Maven `3.9.16` from `.sdkmanrc`.
- `mvn -ntp test`: runs unit, integration, and conformance tests in the reactor.
- `mvn -ntp -pl chappe-cli -am package`: builds the CLI and required modules.
- `mvn -ntp -Pquality verify`: runs verification with JaCoCo reporting.
- `java --enable-preview -jar chappe-cli/target/chappe-cli-*-shaded.jar serve --root ./site --port 8080`: runs the packaged CLI locally.

If Maven reports unsupported `modelVersion 4.1.0`, switch back to Maven 4.

## Coding Style & Naming Conventions

Use Java 25 with preview enabled. Keep packages and Java modules under `io.vidocq.chappe.*`. Follow existing 4-space indentation, fluent builder formatting, and package-private implementation classes where possible. Prefer records for immutable data, sealed interfaces for closed hierarchies, exhaustive switch expressions, and zero external runtime dependencies beyond the JDK.

## Testing Guidelines

Tests use JUnit Jupiter from JUnit 6 and Surefire with `--enable-preview`. Name test classes `*Test` and place socket-based integration tests in `chappe-tests` or protocol cases in `chappe-conformance`. Prefer real `HttpClient` or raw socket checks over HTTP mocking. Add focused tests near the changed module, then run a narrow command first, for example `mvn -ntp -pl chappe-http test`.

## Commit & Pull Request Guidelines

Commit messages, Javadoc, and the content of all `.md` files must be written in **English**.

History uses short English descriptions and Conventional Commit-style prefixes, for example `docs: ...`, `ci(pr): ...`, and `fix(pr): ...`. Prefer `<type>(scope): summary` when practical. Pull requests should explain the behavioral change, list tests run, link issues, and include CLI output or screenshots only when user-visible behavior changes.

## Agent-Specific Instructions

Keep changes scoped and root-cause oriented. Do not introduce runtime dependencies without explicit approval. Preserve the zero-dependency server goal and avoid unrelated refactors while touching performance-sensitive HTTP paths.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` and `docs/fr` as Antora modules and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` + `docs/fr` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, `version: ~`, `nav:`, `lang: en`.
- `docs/fr/antora.yml` → `name: <project>-fr`, same `title`, `lang: fr`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **EN/FR parity**: every page exists in both languages with translated content.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
