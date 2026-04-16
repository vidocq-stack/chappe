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
import fr.vidocq.chappe.api.*;

void main() {
    var router = Router.builder()
        .get("/", _ -> Response.ok("Hello, Chappe!"))
        .get("/users/{id}", req -> {
            var id = req.pathParams().get("id");
            return Response.ok("User " + id);
        })
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
    .get("/api/users", handler)          // route exacte
    .get("/api/users/{id}", handler)     // paramètres capturés
    .get("/static/*", handler)           // wildcard
    .group("/v1", api -> api             // groupe avec préfixe
        .filter(authFilter)              // filtre par scope
        .get("/data", handler)
    )
    .mount("/admin", adminApp)           // mount d'une sous-app (path stripping)
    .notFound(custom404)                 // 404 personnalisé
    .build()
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

### Extension SPI (pour Servlet / JAX-RS)
```java
// Deux extensions coexistent sur des préfixes différents
Router.builder()
    .mount("/app", servletHandler)     // vidocq-servlet
    .mount("/api", jaxrsHandler)       // vidocq-jaxrs
    .mount("/static", StaticFileHandler.of(webRoot))
    .build()
```

L'API `Request` fournit tout ce qu'il faut aux extensions :
- `contextPath()` / `pathInfo()` — path relatif au mount
- `attribute(key, value)` — attributs mutables per-request
- `remoteAddress()` / `localAddress()` / `isSecure()` / `scheme()`
- `Body.ofOutputStream()` — streaming body (Servlet OutputStream compat)
- `Body.ofFile()` — zero-copy via `FileChannel.transferTo()`

## Performance

Benchmarks sur macOS, Java 25, NIO client ultra-léger ([détails](BENCHMARKS.md)) :

| Serveur | 1 thread | 4 threads | 16 threads |
|:--------|----------:|----------:|-----------:|
| **Jetty 12** | 41 040 | **115 100** | **127 600** |
| **Chappe 0.1** | 36 324 | **96 328** | 91 019 |
| **Helidon SE 4** | 34 861 | 94 620 | 90 798 |
| **JDK HttpServer** | 31 883 | 85 410 | 105 533 |

**Latence p99 = 50 µs** (20× sous l'objectif de 1ms).

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                 chappe-examples                       │
├─────────────────────────────────────────────────────┤
│  chappe-tests (62)   chappe-bench   chappe-conf (45) │
├─────────────────────────────────────────────────────┤
│                    chappe-core                        │
│   (moteur serveur, virtual threads, TLS, pooling)    │
├─────────────────────────────────────────────────────┤
│                    chappe-http                        │
│     (HTTP/1.1, HTTP/2, HPACK, SslHandler)            │
├─────────────────────────────────────────────────────┤
│                    chappe-api                         │
│  Server, Router, Handler, Request, Response, Body    │
│  StaticFileHandler, MimeTypes, RequestContext         │
├─────────────────────────────────────────────────────┤
│             Java 25 (Loom, ScopedValue, JPMS)        │
└─────────────────────────────────────────────────────┘
```

## Modules

| Module | Description |
|---|---|
| `chappe-api` | API publique : routing, handling, static files, extension SPI |
| `chappe-http` | Protocoles HTTP/1.1 et HTTP/2, TLS, buffer pool |
| `chappe-core` | Moteur serveur, virtual threads, protocol detection |
| `chappe-tests` | 62 tests d'intégration |
| `chappe-conformance` | 45 tests de conformité RFC |
| `chappe-bench` | Benchmarks comparatifs |

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
mvn test                    # 107 tests
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
