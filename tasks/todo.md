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

## Phase 6.5 : WebSocket (RFC 6455) ✅
- [x] API publique : `WebSocket`, `WebSocketHandler`, `CloseCodes`, `WebSocketUpgrade`, `Router.Builder.webSocket(pattern, handler)`
- [x] Handshake serveur : validation `Upgrade`/`Connection`/`Sec-WebSocket-Version: 13`/`Sec-WebSocket-Key`, calcul `Sec-WebSocket-Accept` (SHA-1 + base64 + GUID RFC), 101 Switching Protocols, support sous-protocole
- [x] Framing RFC 6455 §5 : opcodes (TEXT/BINARY/PING/PONG/CLOSE/CONTINUATION), FIN, RSV, masking client (XOR unmask), payload 7/16/64 bits, validation UTF-8 sur TEXT, control frames ≤ 125o, rejet RSV non nul / opcode inconnu / mask absent / continuation orpheline / payload > 64 MiB
- [x] Lifecycle : auto-PONG sur PING (avant `onPing` observation), close handshake bidirectionnel (echo du code reçu), `onError` sur exception, fermeture TCP en `finally`
- [x] Thread-safety : `ReentrantLock` sérialise les writes (RFC §5.4), callbacks séquentiels sur le virtual thread de la connexion
- [x] Tests : 5 tests d'intégration (`WebSocketEchoTest` via `java.net.http.WebSocket`) + 8 tests conformance brute (`WebSocketRfc6455Test` via socket : Sec-WebSocket-Accept canonique RFC §1.3, missing version → 426, unmasked → close 1002, UTF-8 invalide → close 1007, echo Close client avec même code)
- [ ] permessage-deflate (RFC 7692) — déféré, à brancher comme extension négociée
- [ ] WebSocket sur HTTP/2 (RFC 8441) — déféré

## Phase 7 : `chappe-grpc` (transport gRPC natif, zéro-dep)
> Demandé par humboldt (MicroProfile Telemetry 2.1) — voir `humboldt/PLAN.md` §3.5 pour la motivation détaillée. gRPC sur le fil = HTTP/2 (déjà ✅) + trailers (déjà ✅) + framing length-prefixed 5 octets + status codes via trailers. **Aucune dépendance** grpc-java / Netty / perfmark / protobuf-java — la sérialisation reste à la charge de l'appelant.
- [ ] `chappe-grpc` nouveau module Maven dans le reactor (JPMS `io.vidocq.chappe.grpc`)
- [ ] Framing length-prefixed : 1 octet `compressed?` + 4 octets big-endian length + payload
- [ ] Trailers HTTP/2 sortants : `grpc-status` / `grpc-message` (codes RPC standards)
- [ ] `GrpcRouter` + `GrpcRequest`/`GrpcResponse`/`GrpcContext` (API serveur, calque `Router.mount()`)
- [ ] `GrpcClient` (API client, calque `HttpClient` JDK, virtual-thread per call)
- [ ] Unary RPC d'abord ; server-streaming / client-streaming / bidi dans une seconde itération
- [ ] Compression `gzip` (réutilise `AcceptEncoding` chappe)
- [ ] Tests d'intégration cross-impl : appel d'un endpoint `grpc.health.v1.Health/Check` exposé par chappe-grpc, validé via `grpcurl` (ou client Go)
- [ ] Conformité protocole gRPC : spec wire format (https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)
- [ ] Doc dans `docs/` (référence et exemple OTLP-Collector)
