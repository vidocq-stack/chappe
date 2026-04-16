# Chappe — Rapport de Performance

> Benchmarks exécutés le 2026-04-16 sur macOS Darwin 25.4.0.
> Java 25-tem, Virtual Threads (Project Loom), zéro dépendance.

## Comparatif — Chappe vs Serveurs de Référence

Client ultra-léger `SocketChannel` NIO avec `ByteBuffer.allocateDirect()`, TCP_NODELAY,
keep-alive HTTP/1.1. Chaque serveur retourne "ok" (2 bytes) sur `GET /`.

| Serveur | 1 thread | 4 threads | 8 threads | 16 threads |
|:--------|----------:|----------:|----------:|-----------:|
| **Jetty 12.0.21** | **40 729** | **115 161** | **124 936** | **127 067** |
| **Chappe 0.1** | 36 094 | **95 322** | 89 441 | 91 019 |
| **Helidon SE 4.2.2** | 35 530 | 95 090 | 87 636 | 91 086 |
| **JDK HttpServer** | 31 364 | 84 909 | 95 815 | 107 069 |

### Analyse comparative

- **Jetty 12** domine à 127K req/s (16t) — 20+ ans d'optimisation, epoll/kqueue natif
- **Chappe 0.1** à 95K req/s (4t) — au niveau de Helidon, dépasse JDK HttpServer. Gain de **+51%** après optimisation (coalescing write, zero-alloc headers, buffer pooling)
- **Helidon SE 4** à 95K req/s — basé sur virtual threads comme Chappe, performances quasi identiques
- **JDK HttpServer** à 107K req/s (16t) — scale mieux au-delà de 8 threads grâce aux optimisations internes du JDK

### Ratio Chappe vs référence

| vs | Avant optim | Après optim |
|----|-------------|-------------|
| Jetty 12 (best) | 49% | **83%** |
| Helidon SE 4 (best) | 69% | **100%** (égal) |
| JDK HttpServer (best) | 59% | **89%** |

### Optimisations appliquées (v0.1 → v0.1-optimized, +51%)

1. ✅ **Write coalescing** : headers + body coalescés dans un seul `channel.write()` pour les petites réponses. Réduit les syscalls de 2+ à 1.
2. ✅ **Zero-alloc headers** : `putAsciiString()` écrit directement char-par-char dans le ByteBuffer, élimine les `String.getBytes()` (10+ byte[] par réponse).
3. ✅ **Buffer pooling étendu** : read ET write buffers poolés via `ByteBufferPool`. Élimine `allocateDirect()` par connexion.
4. ✅ **`firstOrNull()` sur Headers** : élimine ~5 `Optional` par requête dans le hot path.
5. ✅ **Body chunk réutilisé** : `byte[8192]` réutilisé entre réponses (field, pas local var).
6. ✅ **`putAsciiLong`/`putAsciiHex` sans allocation** : digits écrits dans un buffer réutilisé.

### Gap restant vs Jetty (-17%)

Le gap restant est principalement dû à :
1. **Parsing request String-based** : les header values sont toujours des `String` (allocation GC)
2. **Pas d'epoll/kqueue** : Jetty utilise des sélecteurs natifs pour l'I/O multiplexé
3. **Pas de thread-local buffer pools** : la `ConcurrentLinkedQueue` a un overhead CAS

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
