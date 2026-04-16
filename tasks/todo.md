# Chappe — Plan de Développement

## Phase 1 : Fondations ✅
- [x] Structure Maven multi-module avec JPMS
- [x] API publique (chappe-api) : Server, Handler, Request, Response, Router
- [x] Parsing HTTP/1.1 (RFC 9112)
- [x] Serveur TCP basique avec virtual threads
- [x] Keep-alive et pipelining HTTP/1.1
- [x] Tests d'intégration avec java.net.http.HttpClient

## Phase 2 : HTTP/2 ✅
- [x] Framing HTTP/2 (RFC 9113)
- [x] HPACK (decode complet + encode Huffman + table dynamique)
- [x] Multiplexage de streams (virtual thread par stream)
- [x] Flow control (connection + per-stream, WINDOW_UPDATE, AtomicInteger)
- [x] h2c cleartext + ALPN h2 via TLS
- [ ] Server push (PUSH_PROMISE) — déféré

## Phase 3 : Validation ✅
- [x] Conformité HTTP/1.1 (RFC 9110/9112) : 32 tests
- [x] Conformité HTTP/2 (RFC 9113) : 8 tests
- [x] Benchmarks JMH + comparatif Jetty/Helidon/JDK
- [x] Tests de robustesse : malformées, slowloris, abandon, oversized

## Phase 4 : Production-ready ✅
- [x] TLS/SSL (SSLContext, SSLEngine, ALPN)
- [x] Buffer pooling (ThreadLocal, zero-contention)
- [x] Graceful shutdown configurable
- [x] Timeouts (SO_TIMEOUT)

## Audit RFC ✅
### Bugs critiques corrigés (8) :
- [x] Chunked TE réponses, flow control H2, timeouts, Content-Length validation
- [x] Chunk size overflow, SslHandler thread safety, max concurrent streams, FrameWriter IOException

### Conformité RFC (9) :
- [x] Date, HEAD/204/304 suppression body, 405+Allow, Host validation
- [x] 100-continue, H2 INITIAL_WINDOW_SIZE, pseudo-headers, headers interdits

### Optimisations perf (63K → 96K req/s, +53%) :
- [x] Write coalescing (1 syscall), zero-alloc headers (putAsciiString)
- [x] Thread-local buffer pool, fast path 200 OK, header value interning
- [x] HPACK Huffman encoding + table dynamique

## Phase 5 : Préparation extensions Vidocq ✅
### SPI d'extension :
- [x] `Router.mount(prefix, handler)` — enregistrement par path prefix avec stripping
- [x] `Request` enrichi — contextPath, pathInfo, attributes, remoteAddress, isSecure, scheme
- [x] `RequestContext` ScopedValue — contexte propagé sans parameter passing
- [x] `Body.ofOutputStream()` — streaming body (Servlet OutputStream compat)
- [x] `Body.ofFile()` + `FileBody` — zero-copy via FileChannel.transferTo()

### Fichiers statiques :
- [x] `StaticFileHandler` builder avec fallback chain
- [x] Sources filesystem (zero-copy) et classpath (jar, META-INF/resources)
- [x] Cache en mémoire avec ETag pour ressources classpath
- [x] Cache-Control configurable
- [x] `MimeTypes` — détection par extension (26+ types)
- [x] Last-Modified / If-Modified-Since → 304
- [x] If-None-Match / ETag → 304 (cache)
- [x] Path traversal protection
- [x] Directory index (index.html)

## Phase 6 : Intégration Vidocq (à venir)
- [ ] `vidocq-servlet` — extension CDI montant Servlet 6.1 sur Chappe
- [ ] `vidocq-jaxrs` — extension CDI montant JAX-RS 4.0 sur Chappe (natif, pas via Servlet)
- [ ] Coexistence Servlet + JAX-RS sur prefixes différents
- [ ] Plugin Maven pour le lancement embedded
- [ ] Documentation et exemples
