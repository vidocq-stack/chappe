# Chappe — Study: Compile-time Optimizations (APT / BuildCompatibleExtension / Maven Plugin)

## Verdict

No, it is **not** a lost cause — but the gain will be more modest than for Vidocq/Vauban,
and for structurally different reasons.

## Why Vidocq/Vauban benefit so much

In Vidocq (JAX-RS) and Vauban (CDI), APT replaces **runtime reflection** (classpath
scanning, bean introspection, injection resolution). That is where the 10× gain comes from,
because reflection is intrinsically slow.

## In Chappe, the situation is different

Chappe already has **no reflection at all** on the hot path:

- `ServiceLoader` is used once at boot (`ServerProvider`), not per request
- Handlers are lambdas/instances passed programmatically
- No `@Route` annotations to scan — the API is fluent (`router.get(...)`)
- The HTTP parser is already a state machine with a reused `StringBuilder` (zero-alloc)

So APT cannot **replace** slow code, only **specialize** structures that are
already correct.

## Real opportunities (by decreasing ROI)

| Target | Current runtime | Possible precomputation | Realistic gain |
|---|---|---|---|
| **HPACK static table** (`HpackStaticTable:110-130`) | O(61) linear scan per header | generated perfect-hash switch | H/2 hot path — **measurable** |
| **MimeTypes.detect()** | `lastIndexOf + substring + toLowerCase + map` | compiled extension switch | -40% allocations, but not on the hot path except statics |
| **StaticFileHandler classpath** | `loader.getResource()` + `URLConnection` per request | build-time `META-INF/chappe-resources` index | -90% classpath file latency |
| **Router trie/DFA** | linear route scan | precompiled trie | useful only above ~50 routes |
| **Mount registry** | `LinkedHashMap` assembly in `onStart` | generated registry | -20% startup, not runtime |

## What will not gain anything

- **HTTP/1.1 parser** — already optimal, APT will not beat a manual state machine
- **`RequestContext` ScopedValue** — already almost free (JEP 506)
- **Virtual threads, buffer pool, write coalescing** — orthogonal to APT

## Recommendation

**Do not copy the Vidocq/Vauban model as-is.** Where they generate *injection proxies*
(reflection replacement), Chappe should instead move toward a **Maven plugin** that:

1. **Indexes classpath resources** at build time → `META-INF/chappe-static-index` (clear gain, Quarkus pattern)
2. **Generates HPACK/MIME tables** as perfect-hash switches (micro-opt, but H/2 hot path)
3. **Possibly** provides a `@ChappeApp` that compiles `router.mount(...)` into a trie — *only if*
   a usage profile with many routes emerges

The **main tradeoff**: the current simplicity (zero build step, fluent API) is a feature.
A mandatory APT plugin would break that. Keeping it **optional** (`chappe-codegen` opt-in)
preserves the dev experience.

---

## Chosen POC: Maven plugin for static resource indexing

### Current problem

`StaticFileHandler` with classpath fallback does, **per request**:

- `loader.getResource(resourcePath)` — scans all parent ClassLoaders
- `URLConnection.openConnection()` — opens a stream to obtain `contentLength`, `lastModified`
- `MimeTypes.detect()` — `lastIndexOf + substring + toLowerCase + map lookup`
- Allocation of a `ResolvedResource` wrapper

On a JAR with a few thousand resources, `getResource()` traverses the entire
ClassLoader hierarchy on every hit. On nested classpaths (Spring Boot, fat JAR,
uber JAR), it is worse.

### Solution: build-time pre-generated index

A `chappe-static-index-maven-plugin` Maven plugin that:

1. Scans configured resource directories (`src/main/resources/static/**` by default)
2. For each file, precomputes: `path`, `size`, `lastModified`, `mimeType`, `etag` (truncated SHA-256)
3. Writes `META-INF/chappe-static-index.properties` (simple format, loadable once at boot)
4. At runtime: `StaticFileHandler` detects the index and performs an O(1) lookup in a static `Map`

The hot path becomes: `map.get(path)` → `Body.ofClasspath(path, size, mime)` — zero URLConnection,
zero introspection.

### Expected measurable gain

| Metric | Before | After | Gain |
|---|---|---|---|
| p50 classpath static file latency | ~15 µs | ~2 µs | **-85 %** |
| Allocations per request | 3-4 objects (URLConnection, streams…) | 1 (Body) | **-75 %** |
| Boot | 0 ms | +5-20 ms (index loading) | negligible |

---

### Plugin structure

```
chappe-static-index-maven-plugin/
├── pom.xml
└── src/main/java/fr/vidocq/chappe/maven/
    ├── IndexMojo.java                 # @Mojo("index") — scans and generates
    └── StaticIndexWriter.java         # properties writing
```

### `IndexMojo.java` — skeleton

```java
package io.vidocq.chappe.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

@Mojo(name = "index", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public final class IndexMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "static")
    private String rootPrefix;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        Path root = Paths.get(outputDirectory, rootPrefix);
        if (!Files.isDirectory(root)) {
            getLog().info("No static root at " + root + " — skipping");
            return;
        }

        Path indexFile = Paths.get(outputDirectory, "META-INF", "chappe-static-index.properties");
        try {
            Files.createDirectories(indexFile.getParent());
            Properties props = new Properties();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    try {
                        long size = Files.size(p);
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        String mime = detectMime(rel);
                        String etag = sha256Hex(p).substring(0, 16);
                        props.setProperty(rel, size + "|" + mtime + "|" + mime + "|" + etag);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to index " + p, e);
                    }
                });
            }
            try (var out = Files.newBufferedWriter(indexFile)) {
                props.store(out, "Chappe static resources index — generated at build time");
            }
            getLog().info("Indexed " + props.size() + " static resources → " + indexFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate chappe-static-index", e);
        }
    }

    private String detectMime(String path) { /* reuse MimeTypes.detect */ return "application/octet-stream"; }
    private String sha256Hex(Path p) throws IOException { /* MessageDigest SHA-256 */ return ""; }
}
```

### Usage in a downstream project

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.vidocq.chappe</groupId>
      <artifactId>chappe-static-index-maven-plugin</artifactId>
      <version>${chappe.version}</version>
      <executions>
        <execution>
          <goals><goal>index</goal></goals>
        </execution>
      </executions>
      <configuration>
        <rootPrefix>static</rootPrefix>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Runtime reading in `StaticFileHandler`

```java
// at handler boot, once:
private static final Map<String, IndexedResource> INDEX = loadIndex();

private static Map<String, IndexedResource> loadIndex() {
    try (var in = StaticFileHandler.class.getResourceAsStream("/META-INF/chappe-static-index.properties")) {
        if (in == null) return Map.of();
        Properties p = new Properties();
        p.load(in);
        Map<String, IndexedResource> m = new HashMap<>(p.size() * 2);
        for (String key : p.stringPropertyNames()) {
            String[] parts = p.getProperty(key).split("\\|");
            m.put(key, new IndexedResource(
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1]),
                parts[2],
                parts[3]
            ));
        }
        return Map.copyOf(m);
    } catch (IOException e) {
        return Map.of();
    }
}

record IndexedResource(long size, long lastModified, String mime, String etag) {}

// hot path:
public Response handle(Request req) {
    String path = req.path();
    IndexedResource idx = INDEX.get(path);
    if (idx != null) {
        // zero URLConnection, zero introspection
        return Response.ok()
            .header("Content-Type", idx.mime())
            .header("ETag", '"' + idx.etag() + '"')
            .body(Body.ofClasspath("/static/" + path, idx.size()));
    }
    // fallback: old path (filesystem, dev mode)
    return fallbackLookup(req);
}
```

### Dev mode (without plugin)

If `META-INF/chappe-static-index.properties` is missing → `INDEX.isEmpty()` → it falls back to
the old `loader.getResource()` behavior. **Zero regression**, **zero mandatory config**.

### Delivery steps

1. Create the `chappe-static-index-maven-plugin` module (`maven-plugin` packaging)
2. Implement `IndexMojo` + unit tests (fixture directory → verify generated index)
3. Wire index loading into `StaticFileHandler` (transparent fallback)
4. Add an example in `chappe-examples` (`static-indexed-example`)
5. JMH bench: before/after on classpath file → validate the -85%
6. Document in `README.md` ("Production builds" section)

### Points of attention

- **ETag stability**: use SHA-256, not `lastModified`, for CI/CD reproducibility
- **Dev hot reload**: disable the index if system property `chappe.dev=true` is set (or if absence is detected)
- **Multiple classpaths**: if multiple JARs contain `META-INF/chappe-static-index.properties`,
  merge them via `getResources()` at boot (order: first-wins or last-wins, to be decided)
- **Index size**: 10k files ≈ ~1 MB properties — acceptable. Beyond that, consider a compact
  binary format (varint + deduplicated string table)

---

## Delivered implementation (2026-04-23)

The optimizations from the ROI table were implemented on branch `working/compiletime`:

### 1. HPACK static table — `O(61)` → `O(1)`
`chappe-http/.../h2/HpackStaticTable.java`: `findExact` and `findByName` use two
prebuilt immutable maps (`Map.copyOf`) in a static block. Linear scan eliminated from the HTTP/2 hot path.

### 2. MimeTypes.detect() — zero-alloc
`chappe-api/.../MimeTypes.java`: no more `substring` or `toLowerCase()`. Case-insensitive
comparison via `String.regionMatches(true, …)` over an array ordered by web frequency
(html → css → js → png → jpg → svg → json → …). O(n) lookup but with a fast hit on the common case
and **zero allocation**.

### 3. `chappe-static-index-maven-plugin` Maven plugin
- New `chappe-static-index-maven-plugin` module (`maven-plugin` packaging, Java 21 target for
  compatibility with `maven-plugin-plugin:descriptor`).
- `IndexMojo` (`process-resources` phase) scans `${project.build.outputDirectory}/${rootPrefix}`,
  computes `size|mtime|mime|etag` (16-hex truncated SHA-256), and writes
  `META-INF/chappe-static-index.properties` with full classpath keys (`static/index.html`).
- 3 green unit tests (nominal indexing, missing-root skip, `skip` flag).
- `StaticFileHandler` (chappe-api): new fast path in `ClasspathSource.resolve()` that consults
  the index before `URLConnection.openConnection()`. Index loaded once per ClassLoader via
  `ClassLoader.getResources()` (multi-JAR merge, first wins). **No index = transparent
  fallback** to the legacy path ⇒ zero regression.
- Example `chappe-examples/StaticIndexedExample` + resources under `static/` + plugin enabled in the
  POM; the generated package index contains `static/index.html`, `static/app.css`,
  `static/app.js`.

### 4. Router — static-route fast path
`chappe-api/.../DefaultRouterBuilder.java`: at `build()`, routes without `{param}` or `/*` are
indexed in `Map<String, EnumMap<HttpMethod, Route>>`. Dispatch performs an O(1) lookup
(`staticIndex.get(path)`) before the linear scan over dynamic routes only. Semantics are
fully preserved: 405 Method Not Allowed, Auto-HEAD, mounts, global/local filters. The
`withPathParams` wrappers and `List<Filter>` construction are no longer allocated for
static routes (`params = Collections.emptyMap()`).

### 5. Mount registry — not required
`DefaultRouterBuilder.build()` already used `List.copyOf(mounts)` (immutable snapshot at build time). A
linear scan over the typical 1–3 mounts remains the best option. **No action required.**

### Validation

- `mvn -pl '!chappe-conformance' test` → BUILD SUCCESS, **65/65 tests green**
  (RouterTest 7, ExtensionSpiTest 25, HttpGetTest 7, Http2Test 7, TlsTest 3, etc.).
- `chappe-static-index-maven-plugin` → **3/3 tests green**.
- One pre-existing test fails in `chappe-conformance` (`Http11HeadersTest#obsFoldRejected`:
  expected 400, got 301) — reproduced on HEAD without the optimizations, tied to an
  earlier change in `HttpRequestImpl.buildUri()`. **Out of scope for this series.**

### Remaining

- Comparative before/after JMH bench on `StaticFileHandler` classpath to validate the announced gain
  (-85% latency, -75% allocations).
- `chappe-static-index-maven-plugin`: Maven 4 vs 3 packaging — today it depends on
  `maven-plugin-api:3.9.9`. To be retested when Maven 4 GA is released.
