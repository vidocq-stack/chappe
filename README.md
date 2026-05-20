<p align="center">
  <img src="chappe-logo.png" alt="Chappe" width="360"/>
</p>

<h1 align="center">Chappe</h1>

<p align="center">
  <strong>Serveur HTTP haute performance en Java 25 pur — zéro dépendance, virtual threads</strong>
</p>

<p align="center">
  <a href="https://www.java.com/"><img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/></a>
  <a href="https://maven.apache.org/"><img src="https://img.shields.io/badge/Maven-4.0-blue?logo=apachemaven" alt="Maven 4"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-green.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/HTTP-1.1%20%7C%202-red" alt="HTTP 1.1/2"/>
  <img src="https://img.shields.io/badge/dependencies-0-brightgreen" alt="Zero deps"/>
</p>

---

> **Claude Chappe** (1763–1805) a inventé le télégraphe sémaphorique — un réseau de tours
> optiques qui couvrait toute la France. Ce projet en est l'héritier numérique.

## Présentation

**Chappe** est un serveur HTTP écrit en **Java 25 pur**, sans aucune dépendance externe.
Il est conçu comme couche transport de l'écosystème **Vidocq** — les extensions Servlet et
JAX-RS se montent par-dessus via un SPI dédié, sans couplage.

## Quick Start

```java
import io.vidocq.chappe.api.*;
import java.nio.charset.StandardCharsets;

void main() {
    var router = Router.builder()
        .get("/", _ -> Response.ok("Hello, Chappe!"))
        .get("/users/{id}", req -> {
            var id = req.pathParams().get("id");
            return Response.ok("User " + id);
        })
        .post("/users", req -> {
            var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Response.builder()
                .status(StatusCode.CREATED)
                .header("Content-Type", "application/json")
                .body("{\"received\": " + body + "}")
                .build();
        })
        .put("/users/{id}", req -> {
            var body = new String(req.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Response.ok("Updated " + req.pathParams().get("id") + ": " + body);
        })
        .delete("/users/{id}", req -> Response.of(StatusCode.NO_CONTENT))
        .build();

    try (var server = Server.builder()
            .port(8080)
            .handler(router)
            .build()) {
        server.start();
        Thread.currentThread().join();
    }
}
```

## Fonctionnalités

### Protocoles
- **HTTP/1.1** — RFC 9110/9112 (keep-alive, chunked, pipelining)
- **HTTP/2** — RFC 9113 (multiplexage, HPACK, flow control, CONTINUATION)
- **HTTPS** — TLS via SSLContext/SSLEngine, ALPN (h2 + http/1.1)

### Routage
```java
Router.builder()
    .get("/api/users", handler)          // GET
    .post("/api/users", handler)         // POST
    .put("/api/users/{id}", handler)     // PUT avec path param
    .delete("/api/users/{id}", handler)  // DELETE
    .patch("/api/users/{id}", handler)   // PATCH
    .head("/api/health", handler)        // HEAD (auto pour GET aussi)
    .options("/api/users", handler)      // OPTIONS
    .get("/static/*", handler)           // wildcard
    .group("/v1", api -> api             // groupe avec préfixe
        .filter(authFilter)              // filtre par scope
        .get("/data", handler)
    )
    .mount("/admin", adminApp)           // mount d'une sous-app (path stripping)
    .notFound(custom404)                 // 404 personnalisé
    .build()
```

- **405 automatique** : si le path matche mais pas la méthode → `405 Method Not Allowed` avec header `Allow`
- **HEAD auto** : un GET route répond aussi au HEAD (body supprimé automatiquement)
- **Trailing slash** : `/users` et `/users/` matchent la même route
- **Percent-decoding** : `/users/John%20Doe` → pathParam = `"John Doe"`

### Request & Response
```java
// Lire le body d'une requête POST/PUT
.post("/api/data", req -> {
    byte[] raw = req.body().asInputStream().readAllBytes();
    String text = new String(raw, StandardCharsets.UTF_8);
    // req.method(), req.path(), req.headers(), req.queryParams()
    // req.pathParams(), req.header("Content-Type"), req.body()
    return Response.ok(text);
})

// Construire une réponse avec le builder
.get("/api/user", _ -> Response.builder()
    .status(StatusCode.OK)
    .header("Content-Type", "application/json")
    .header("X-Custom", "value")
    .body("{\"name\": \"Chappe\"}")
    .build())

// Réponses courantes
Response.ok("text")                    // 200 + text/plain
Response.ok(Body.ofFile(path))         // 200 + zero-copy file
Response.of(StatusCode.NOT_FOUND)      // 404 sans body
Response.of(StatusCode.NO_CONTENT)     // 204
Response.of(StatusCode.CREATED, body)  // 201 + body

// Body streaming (pour Servlet OutputStream compat)
Body.ofOutputStream(out -> {
    out.write("chunk 1".getBytes());
    out.write("chunk 2".getBytes());
})
```

### Fichiers statiques
```java
// Simple
StaticFileHandler.of(Path.of("./public"))

// Avancé : fallback chain filesystem → classpath
StaticFileHandler.builder()
    .addPath(Path.of("./public"))              // filesystem (zero-copy sendfile)
    .addClasspath("static")                     // classpath (jar, module)
    .addClasspath("META-INF/resources")         // Servlet web fragments, webjars
    .cacheInMemory(true)                        // cache ETag pour ressources classpath
    .cacheControl("max-age=3600, public")
    .build()
```

Features : MIME auto-detection (26+ types), `Last-Modified`/`If-Modified-Since` → 304,
`ETag`/`If-None-Match` → 304 (cache), path traversal protection, directory index.

### TLS / HTTPS
```java
Server.builder()
    .port(443)
    .tls(sslContext)                    // SSLContext JDK standard
    .alpnProtocols("h2", "http/1.1")   // ALPN pour HTTP/2
    .handler(router)
    .build()
```

### Middlewares (Filtres)
```java
Filter logging = next -> request -> {
    System.out.println(request.method() + " " + request.path());
    return next.handle(request);
};

Filter auth = next -> request -> {
    if (request.header("Authorization").isEmpty())
        return Response.of(StatusCode.UNAUTHORIZED);
    return next.handle(request);
};

// Composition
Handler secured = logging.andThen(auth).apply(myHandler);
```

### ScopedValue (contexte de requête)
```java
// Accessible depuis n'importe quel code dans le call chain
Request req = RequestContext.currentRequest();
```

### Mount — sous-applications et extensions

`mount()` délègue un préfixe de chemin entier à un sous-handler, tous verbes HTTP confondus.
Le préfixe est **strippé automatiquement** du path avant d'appeler le handler :

```java
// Le handler "admin" reçoit path="/dashboard", pas "/admin/dashboard"
Router.builder()
    .mount("/admin", req -> Response.ok("path=" + req.path()))  
    .build()

// GET /admin/dashboard → handler voit path="/dashboard", contextPath="/admin"
```

#### Path stripping et contextPath

Quand un handler est monté, la requête est wrappée :
- `request.path()` → chemin **relatif** au mount (`/dashboard`)
- `request.contextPath()` → le préfixe du mount (`/admin`)
- `request.pathInfo()` → idem que `path()` pour les mounts

```java
.mount("/api", req -> {
    // GET /api/v1/users arrive ici avec :
    //   req.path()        → "/v1/users"
    //   req.contextPath() → "/api"
    //   req.pathInfo()    → "/v1/users"
    return Response.ok(req.path());
})
```

#### Coexistence de plusieurs mounts

Plusieurs sous-apps coexistent sur des préfixes disjoints. Les routes exactes
ont **priorité sur les mounts** :

```java
Router.builder()
    .get("/health", _ -> Response.ok("UP"))     // priorité : route exacte
    .mount("/app", servletHandler)               // vidocq-servlet
    .mount("/api", jaxrsHandler)                 // vidocq-jaxrs
    .mount("/static", StaticFileHandler.of(webRoot))
    .notFound(_ -> Response.of(StatusCode.NOT_FOUND))
    .build()

// GET /health        → route exacte "UP" (pas de mount)
// GET /app/index.jsp → servletHandler avec path="/index.jsp"
// GET /api/v1/users  → jaxrsHandler avec path="/v1/users"
// GET /static/app.js → StaticFileHandler
// GET /other         → 404
```

#### Ordre de résolution

1. **Routes exactes** (get, post, put, delete...) — premier match par path + method
2. **405** — si le path matche une route mais pas la méthode
3. **Mounts** — premier mount dont le préfixe matche (ordre d'enregistrement)
4. **notFound** — fallback 404

#### Filtres hérités

Les filtres déclarés avant un `mount()` s'appliquent au handler monté :

```java
Router.builder()
    .filter(loggingFilter)                   // s'applique à tout
    .mount("/api", jaxrsHandler)             // loggingFilter actif
    .group("/admin", admin -> admin
        .filter(authFilter)                  // authFilter seulement pour /admin
        .mount("/panel", adminPanelHandler)  // loggingFilter + authFilter actifs
    )
    .build()
```

#### Request enrichi pour les extensions

Le handler monté a accès à toute la metadata de connexion :

| Méthode | Usage |
|---------|-------|
| `request.contextPath()` | Préfixe du mount (`"/api"`) |
| `request.pathInfo()` | Chemin relatif (`"/v1/users"`) |
| `request.attribute(key, value)` | Attributs mutables per-request (Servlet compat) |
| `request.remoteAddress()` | Adresse IP du client |
| `request.localAddress()` | Adresse du serveur |
| `request.isSecure()` | `true` si TLS |
| `request.scheme()` | `"http"` ou `"https"` |
| `Body.ofOutputStream(writer)` | Streaming body (Servlet OutputStream compat) |
| `Body.ofFile(path)` | Zero-copy via `FileChannel.transferTo()` |

## Performance

Benchmarks sur macOS, Java 25, NIO client ultra-léger ([détails](BENCHMARKS.md)) :

| Serveur | 1 thread | 4 threads | 16 threads |
|:--------|----------:|----------:|-----------:|
| **Jetty 12** | 41 040 | **115 100** | **127 600** |
| **Chappe 0.1** | 36 324 | **96 328** | 91 019 |
| **Helidon SE 4** | 34 861 | 94 620 | 90 798 |
| **JDK HttpServer** | 31 883 | 85 410 | 105 533 |

**Latence p99 = 57 µs** sur raw socket keep-alive (run JMH 2026-05-20, 317k samples). Validé sans régression après cleanup ErrorProne + Spotless — détails dans [BENCHMARKS.md](BENCHMARKS.md#2026-05-20--validation-jmh-post-cleanup-errorprone--spotless--systemlogger).

## Architecture

```
┌─────────────────────────────────────────────────────┐
│       chappe-cli  ·  chappe-examples                 │
├─────────────────────────────────────────────────────┤
│  chappe-tests (114)  chappe-bench  chappe-conf (45)  │
├─────────────────────────────────────────────────────┤
│                    chappe-core                        │
│   (moteur serveur, virtual threads, TLS, pooling)    │
├─────────────────────────────────────────────────────┤
│                    chappe-http                        │
│     (HTTP/1.1, HTTP/2, HPACK, SslHandler)            │
├─────────────────────────────────────────────────────┤
│                    chappe-api                         │
│  Server, Router, Handler, Request, Response, Body    │
│  Filter, StaticFileHandler, AcceptEncoding, MimeTypes │
│  RequestContext                                       │
├─────────────────────────────────────────────────────┤
│             Java 25 (Loom, ScopedValue, JPMS)        │
└─────────────────────────────────────────────────────┘
```

## Modules

| Module | Description |
|---|---|
| `chappe-api` | API publique : routing, handling, static files, filtres, négociation `Accept-Encoding`, extension SPI |
| `chappe-http` | Protocoles HTTP/1.1 et HTTP/2, TLS, buffer pool |
| `chappe-core` | Moteur serveur, virtual threads, protocol detection |
| `chappe-cli` | Launcher CLI standalone `chappe serve` (mini-YAML, fat jar, jlink) |
| `chappe-tests` | 114 tests d'intégration |
| `chappe-conformance` | 45 tests de conformité RFC |
| `chappe-bench` | Benchmarks comparatifs |
| `chappe-static-index-maven-plugin` | Plugin Maven : index O(1) + sidecars `.gz` au build |

## Prérequis

- **Java 25** avec `--enable-preview` (ScopedValue, Structured Concurrency)
- **Maven 4** (4.0.0-rc-5+, POM modelVersion 4.1.0)

```bash
sdk use java 25-tem
sdk use maven 4.0.0-rc-5
```

## Build & Test

```bash
mvn clean install           # build complet
mvn test                    # 195 tests
```

## CLI standalone

Pour servir un site statique sans launcher Java applicatif (cas d'usage Docker) :

```bash
mvn -ntp -pl chappe-cli -am package    # produit chappe-cli-*-shaded.jar
java --enable-preview \
     -jar chappe-cli/target/chappe-cli-*-shaded.jar \
     serve --root /var/www/site --port 8080 --gzip
```

Ou via fichier YAML :

```yaml
# /etc/chappe/config.yml
server: { port: 8080, bind: 0.0.0.0 }
static:
  root: /var/www/site
  fallback: /404.html
  cache-control: "max-age=3600, public"
  gzip: true
headers:
  always: { X-Content-Type-Options: "nosniff" }
  staging: { X-Robots-Tag: "noindex, nofollow" }   # actif si STAGING=true
```

Voir `chappe-cli/README.md` pour les recettes Docker (fat jar + jlink).

### Compression

`Filter.gzip()` négocie `Accept-Encoding`, compresse à la volée (skip si < 1 Ko, MIME
non text-like, ou `Cache-Control: no-transform`). `StaticFileHandler.preferPrecompressed(true)`
sert en priorité les sidecars `.br` / `.gz` (zero-copy). Les `.gz` peuvent être
générés au build avec :

```xml
<plugin>
  <groupId>io.vidocq.chappe</groupId>
  <artifactId>chappe-static-index-maven-plugin</artifactId>
  <configuration><compress>gzip</compress></configuration>
</plugin>
```

### Filtres déclaratifs

```java
Router.builder()
    .filter(Filter.addHeader("X-Content-Type-Options", "nosniff"))
    .filter(Filter.addHeaderIfEnv("STAGING", "true",
            "X-Robots-Tag", "noindex, nofollow"))
    .filter(Filter.gzip())
    .build();
```

## Conformité RFC

| Spec | Couverture |
|------|-----------|
| RFC 9110 (HTTP Semantics) | Date, HEAD/204/304, 405+Allow, Host, 100-continue |
| RFC 9112 (HTTP/1.1) | Request parsing, chunked, keep-alive, pipelining |
| RFC 9113 (HTTP/2) | Framing, HPACK, flow control, stream lifecycle, GOAWAY, SETTINGS |
| RFC 7541 (HPACK) | Static table, dynamic table, Huffman encode/decode |

## Écosystème Vidocq

Chappe fait partie de la famille **Vidocq** :

- **[Vauban](https://github.com/VidocqMP/vauban)** — conteneur CDI 4.1 (intégration prévue via extensions)
- **Chappe** — serveur HTTP (ce dépôt)
- **vidocq-servlet** — extension Servlet 6.1 sur Chappe (à venir)
- **vidocq-jaxrs** — extension JAX-RS 4.0 natif sur Chappe (à venir)

> **Note :** Chappe fonctionne de manière autonome sans Vauban. L'intégration CDI
> se fera via les extensions vidocq-servlet et vidocq-jaxrs qui utiliseront Vauban
> pour le lifecycle des composants.

## Licence

[Apache License 2.0](LICENSE) — © Yann Blazart
