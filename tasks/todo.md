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

## Phase 6.6 : HTTP/2 Trailers (RFC 9113 §8.1) ✅
- [x] API publique : `Response.trailers()` + `Builder.trailer(name, value)` / `trailers(headers)` ; `Request.trailers()`
- [x] Envoi serveur : HEADERS frame additionnelle après les DATA frames avec END_STREAM=1, encodage HPACK `encodeTrailers` (pas de pseudo-header)
- [x] Réception serveur : détection second HEADERS frame sur stream existant via `Http2Stream.markTrailers()`, rejet de pseudo-headers en trailers, exposition via `request.trailers()`
- [x] Refactor `HpackDecoder.decode(ByteBuffer, HeaderSink)` pour découpler la cible d'écriture
- [x] Tests `Http2TrailersTest` : 2 scénarios round-trip via client H2 raw (envoi + réception)

## Phase 6.7 : gRPC transport (RFC HTTP/2 + framing core) ✅
> gRPC sur le fil = HTTP/2 + trailers + framing length-prefixed 5 octets. **Aucune dépendance** grpc-java / Netty / perfmark / protobuf-java. La sérialisation (protobuf, JSON, …) reste à la charge de l'application ou d'une extension dédiée (`champollion` pour protobuf, en parallèle).
- [x] API publique `chappe-api` : `GrpcHandler`, `GrpcCall` (SPI synchrone bloquante byte-level), `GrpcStatus` (17 codes RFC), `GrpcDispatch` (marker Response), `Router.Builder.grpc(pattern, handler)`
- [x] Framing core `chappe-http/grpc/` : `GrpcFrameReader` (préfixe 5 octets, rejet `compressed=1`, garde `maxMessageSize=4 MiB`), `GrpcFrameWriter` (encode préfixe + payload), `GrpcFrameException`
- [x] `GrpcCallImpl` : émission auto-headers (`:status 200`, `content-type: application/grpc`, `grpc-accept-encoding: identity`), trailers avec percent-encoding RFC 3986 sur `grpc-message`, trailers-only fusionné en un seul HEADERS frame, gestion erreur handler → `INTERNAL`
- [x] Dispatch HTTP/2 : `Http2Connection.dispatchStream` détecte `instanceof GrpcDispatch` et bascule sans `sendResponse`, helpers publics `frameWriter()`/`hpackEncoder()`/`sendDataChunked()` exposés
- [x] Refus défensif HTTP/1.1 : `DefaultRouterBuilder.grpc()` retourne `505` si version ≠ HTTP/2, `415` si content-type ≠ `application/grpc*` ; `HttpConnection` blinde un cas résiduel
- [x] Cancellation : `Http2Stream.cancel()` câblé dans `handleRstStream`, exposé via `GrpcCall.isCancelled()`
- [x] Tests `Http2GrpcTransportTest` : 7 scénarios (unary, server-stream, client-stream, bidi 3/3, handler-error, trailers-only, reject HTTP/1.1)
- [x] Compression `grpc-encoding: gzip` : décompression auto en réception (négociation via `grpc-encoding` request header), opt-in compression sortante via `GrpcCall.useResponseEncoding("gzip")`, annonce `grpc-accept-encoding: identity, gzip` dans tous les responses, UNIMPLEMENTED (12) trailers-only si codec request inconnu ; tests : 4 e2e (decode gzip in, encode gzip out, codec inconnu = UNIMPLEMENTED, accept-encoding annoncé par défaut)
- [x] `grpc-timeout` deadline propagation : parser RFC (H/M/S/m/u/n, saturation overflow), watchdog virtual thread, cancel stream + trailers DEADLINE_EXCEEDED (4) si handler dépasse, exposé via `GrpcCall.deadline()` (Optional<Duration>) ; tests : `GrpcTimeoutParserTest` (13) + `deadlineExceededViaGrpcTimeout` + `deadlineRespectedReturnsOk`
- [x] gRPC-Web V1 (HTTP/2 only) : `GrpcWebDispatch` marker + `Router.grpcWeb(pattern, handler)`, modes BINARY (`application/grpc-web`) et TEXT (`application/grpc-web-text` avec Base64 chunk-par-chunk), trailers sérialisés inline comme frame DATA 0x80, réutilise parser timeout + négociation grpc-encoding, dispatch H2 dans `Http2Connection.dispatchStream` ; tests : 6 scénarios (binary unary, binary server-streaming, text unary, handler-error, content-type inconnu = 415, HTTP/1.1 = 505). HTTP/1.1 mode déferré (les navigateurs modernes parlent H2 nativement)
- [x] Client gRPC-Web v1 (zéro-dep via `HttpClient` JDK) : `GrpcWebClient` + `GrpcWebResponse` dans `chappe-api.client`, modes BINARY/TEXT, unary + server-streaming (collecté), parser des frames body 5-octets + trailer 0x80, expose `grpc-status`/`grpc-message`/trailers ; tests self-loop client↔serveur (4) + serveur H1 (1 nouveau). Client gRPC natif (sur trailers H2) déféré (nécessite client HTTP/2 custom)
- [x] gRPC-Web HTTP/1.1 mode (était déféré) : `Router.grpcWeb` accepte H1 via `Body.ofOutputStream` callback + `GrpcWebBufferedCall` package-private (buffered read, streaming write chunked-transfer). En H2 le marker `GrpcWebDispatch` reste pour le streaming via Http2Connection. Tests : H1 unary echo via HttpClient JDK
- [x] Conformité protocole : `grpcurl` cross-impl — `GrpcurlConformanceTest` (2 scénarios : unary echo + erreur handler → INTERNAL ; skip propre si binaire absent)
