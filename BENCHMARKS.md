# Chappe — Rapport de Performance

> Benchmarks exécutés le 2026-04-16 sur macOS Darwin 25.4.0.
> Java 25-tem, Virtual Threads (Project Loom), zéro dépendance.

## Comparatif — Chappe vs Serveurs de Référence

Client ultra-léger `SocketChannel` NIO avec `ByteBuffer.allocateDirect()`, TCP_NODELAY,
keep-alive HTTP/1.1. Chaque serveur retourne "ok" (2 bytes) sur `GET /`.

| Serveur | 1 thread | 4 threads | 8 threads | 16 threads |
|:--------|----------:|----------:|----------:|-----------:|
| **Jetty 12.0.21** | **40 307** | **114 537** | **124 424** | **128 402** |
| **JDK HttpServer** | 31 666 | 81 346 | 94 763 | 106 942 |
| **Helidon SE 4.2.2** | 35 523 | 88 233 | 87 474 | 91 398 |
| **Chappe 0.1** | 25 086 | 62 132 | 60 134 | 62 942 |

### Analyse comparative

- **Jetty 12** domine avec 128K req/s à 16 threads — serveur mature (20+ ans), NIO optimisé avec buffer pooling avancé et epoll/kqueue natif
- **JDK HttpServer** surprend à 107K req/s — bénéficie des virtual threads et d'une implémentation très optimisée dans le JDK
- **Helidon SE 4** atteint 91K req/s — basé sur virtual threads comme Chappe, mais avec plus de maturité d'optimisation
- **Chappe 0.1** atteint 63K req/s — première version, architecture correcte mais marge d'optimisation significative

### Ratio Chappe vs référence

| vs | Ratio |
|----|-------|
| Jetty 12 (16t) | 49% |
| JDK HttpServer (16t) | 59% |
| Helidon SE 4 (16t) | 69% |

### Pourquoi Chappe est plus lent

1. **Parsing String-based** : Chappe parse les headers en `String` (allocation), les serveurs matures utilisent des parseurs zero-copy sur ByteBuffer
2. **Pas de response caching** : chaque réponse reconstruit les headers (Date, Content-Length, Connection), les autres pré-encodent
3. **ByteBuffer pool simple** : un seul niveau de pooling, pas de hiérarchie thread-local → contention
4. **Pas de write coalescing avancé** : un flush par réponse, pas de batching syscall
5. **Plateau à 8 threads** : le throughput ne scale plus au-delà de 8 threads → contention dans le parser ou le writer

---

## Benchmarks Chappe détaillés

### Throughput — Raw Socket (zéro overhead client)

Client `java.net.Socket` TCP avec keep-alive, réponse "ok" (2 bytes).

| Threads | req/s | Total (5s) | Erreurs |
|---------|-------|------------|---------|
| 1 | 25 086 | ~125K | 0 |
| 4 | 62 132 | ~310K | 0 |
| 8 | 60 134 | ~300K | 0 |
| 16 | 62 942 | ~315K | 0 |

### Throughput — HttpClient (overhead réaliste)

Client `java.net.http.HttpClient` — inclut l'overhead du framework async.

| Protocole | req/s |
|-----------|-------|
| HTTP/1.1 | 14 981 |
| HTTP/2 (h2c) | 14 839 |

### Latence — Raw Socket

50 000 échantillons, connexion keep-alive TCP avec `TCP_NODELAY`.

| Percentile | Latence |
|------------|---------|
| min | 19.8 µs |
| **p50** | **31.2 µs** |
| p90 | 36.1 µs |
| **p99** | **50.0 µs** |
| **p999** | **59.8 µs** |
| max | 1 617 µs |

**→ p99 = 50 µs — 20× sous l'objectif de 1 ms ✅**

### Large Response — 1 Mo Body

| Métrique | Résultat |
|----------|----------|
| Throughput | 2 128 req/s |
| Débit | 1.98 GB/s |

---

## Méthodologie

- **Client** : `SocketChannel` NIO avec `ByteBuffer.allocateDirect()`, TCP_NODELAY, keep-alive
- **Handler** : retourne "ok" (2 bytes text/plain) — overhead serveur minimal
- **Warmup** : 3 secondes avant chaque mesure
- **Mesure** : 5 secondes par configuration
- **JVM** : Java 25-tem avec `--enable-preview`
- **OS** : macOS Darwin 25.4.0
- **Threads** : virtual threads pour les clients (1, 4, 8, 16 connexions parallèles)

### Versions testées

| Serveur | Version | Architecture |
|---------|---------|-------------|
| Chappe | 0.1.0-SNAPSHOT | Virtual threads, blocking I/O, JPMS |
| Helidon SE | 4.2.2 | Virtual threads (Loom), NIO |
| Jetty | 12.0.21 | Thread pool, NIO (epoll/kqueue) |
| JDK HttpServer | JDK 25 | Virtual threads, blocking I/O |
| Grizzly | 4.0.2 | NIO (non testé — incompatibilité keep-alive) |

### Pistes d'optimisation pour atteindre 100K req/s

1. **Zero-copy header parsing** — parser directement sur ByteBuffer sans conversion String
2. **Response pré-encoding** — cacher les bytes de la status line + headers communs
3. **Thread-local buffer pools** — éliminer la contention sur ConcurrentLinkedQueue
4. **Write batching** — coalescer les writes quand le handler est rapide
5. **epoll/kqueue** — event loop pour les connexions idle (économise des virtual threads)
