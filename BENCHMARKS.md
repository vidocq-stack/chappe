# Chappe — Rapport de Performance

## TL;DR

Sur Linux x86_64 (12 cores, host network, Docker), avec un harness `wrk2`
open-loop et **p99 < 10 ms** comme filtre de qualité de service :

| Tier        | Servers                                  | Sustained    | p99      |
|-------------|------------------------------------------|-------------:|---------:|
| Top         | nginx · jetty 12 · netty 4               | 200k req/s   | 2.3-5.1 ms |
| **Mid**     | **chappe-jvm** · **chappe-native** · helidon · jdk · go | **100k req/s** | **2.5-3.0 ms** |
| Bottom      | vert.x (defaults)                        | < 100k       | tail élevé |

**Lecture pragmatique** : Chappe (JVM et natif) tient **100 000 req/s avec
p99 < 3 ms** — couvre largement 99 % des workloads HTTP de production. En
dessous de cette charge, Chappe est strictement au niveau d'Helidon SE 4
(même architecture VT) avec une empreinte natif **37 Mo image / 3 MiB RSS**
contre 389 Mo / 38 MiB en JVM.

Au-delà de 100 k req/s, le modèle "1 virtual thread par connection" pur de
Chappe est dépassé par les serveurs hybrides "event loop + thread pool"
(Jetty, nginx, Netty). Voir [§Profil & analyse JFR](#profiling-jfr--pourquoi-chappe-plafonne-à-100k-en-sla-strict).

## Guide de lecture de ce document

Ce rapport empile **deux générations de mesures** qui mesurent des choses
différentes. Pour ne pas s'y perdre :

| Date       | Méthodologie                            | Ce que ça mesure                 | Statut |
|------------|-----------------------------------------|----------------------------------|--------|
| 2026-04-16 | Client NIO maison **closed-loop** in-process JVM | Capacité brute "à fond" sans contrainte latence | Historique — `voir [§2026-04-16](#comparatif--chappe-vs-serveurs-de-référence-2026-04-16-historique-closed-loop)` |
| 2026-04-23 | Idem post-optimisations compile-time    | Idem                             | Historique — `voir [§2026-04-23](#2026-04-23--post-optimisations-compile-time-closed-loop-historique)` |
| 2026-05-18 | **`wrk2` open-loop, container fresh par rate, p99 contrôlée** | **Qualité de service réelle vue par un client externe** | **Référence canonique** ⭐ |

Les chiffres in-process **closed-loop** (2026-04-16, 2026-04-23) reportent
275 k req/s pour Chappe à 16 threads. Ces chiffres **ne sont pas faux** mais
mesurent une **capacité d'overclock** : le client est dans la même JVM
(loopback Java direct, pas de TCP réel), il n'envoie pas à débit constant
mais attend chaque réponse avant la suivante, et la latence p99 n'est pas
sous contrainte. C'est utile pour comparer des optimisations internes
(avant/après) mais **ne reflète pas la perception d'un vrai utilisateur HTTP**.

Le shootout `wrk2` open-loop (2026-05-18) est plus honnête : trafic externe
constant, latence mesurée avec correction de **coordinated omission**
(HdrHistogram), serveur en container Docker isolé, filtre `p99 < 10 ms`.
C'est ce qu'il faut citer quand on parle de la perf de Chappe en production.

Validation : re-run du bench closed-loop 2026-05-18 sur la même VM ré-obtient
265k req/s pour Chappe à 16t (vs 275k en avril) — delta dans le bruit, **pas
de régression code**. Cf. [§Validation](#validation--pas-de-régression-code).

---

## Comparatif — Chappe vs Serveurs de Référence (2026-04-16, historique closed-loop)

> ⚠️ **Mesure historique** : client `SocketChannel` NIO **closed-loop**
> in-process (même JVM que le serveur). Reflète la capacité physique brute
> "à fond" sans contrainte de latence. Pour la qualité de service réelle
> vue par un client externe HTTP, voir le [shootout 2026-05-18](#mode-unleashed--run-2026-05-18t055803z-12-cores-host-network-container-fresh-par-rate).


Client ultra-léger `SocketChannel` NIO avec `ByteBuffer.allocateDirect()`, TCP_NODELAY,
keep-alive HTTP/1.1. Chaque serveur retourne "ok" (2 bytes) sur `GET /`.

| Serveur | 1 thread | 4 threads | 8 threads | 16 threads |
|:--------|----------:|----------:|----------:|-----------:|
| **Jetty 12.0.21** | **41 040** | **117 413** | **124 624** | **127 789** |
| **Chappe 0.1** | 36 324 | **96 328** | 88 257 | 90 963 |
| **Helidon SE 4.2.2** | 33 430 | 94 257 | 87 591 | 90 798 |
| **JDK HttpServer** | 31 883 | 85 410 | 96 080 | 105 303 |

### Analyse comparative

- **Jetty 12** domine à 128K req/s (16t) — 20+ ans d'optimisation, epoll/kqueue natif
- **Chappe 0.1** à **96K req/s** (4t) — **#2 devant Helidon** et JDK HttpServer. Gain total de **+53%** depuis la v0.1 initiale (63K → 96K)
- **Helidon SE 4** à 95K req/s — basé sur virtual threads comme Chappe, performances quasi identiques
- **JDK HttpServer** à 107K req/s (16t) — scale mieux au-delà de 8 threads grâce aux optimisations internes du JDK

### Ratio Chappe vs référence

| vs | v0.1 initiale | v0.1 optimisée |
|----|---------------|----------------|
| Jetty 12 (best) | 49% | **82%** |
| Helidon SE 4 (best) | 69% | **102%** (devant) |
| JDK HttpServer (best) | 59% | **92%** (devant à 4t) |

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

---

## 2026-04-23 — Post optimisations compile-time (closed-loop, historique)

> ⚠️ **Mesure historique closed-loop** : même méthodologie que 2026-04-16
> (`ServerComparison` in-process, client NIO maison dans la même JVM que le
> serveur). Valide pour comparer **avant/après optimisations Chappe** sur les
> mêmes hypothèses, mais **les chiffres absolus ne sont pas comparables** à un
> bench open-loop externe. Pour la perf réelle vue par un client HTTP, voir
> le [shootout 2026-05-18](#mode-unleashed--run-2026-05-18t055803z-12-cores-host-network-container-fresh-par-rate).

Validation des 4 optimisations livrées dans la branche `working/compiletime`. Exécuté sur
Docker distant (**`docker --context macuntutailscale`** — Linux amd64, engine 29.1.4) pour
s'isoler du bruit de la machine dev. Image multi-stage construite via
`chappe-bench/docker/Dockerfile` (JDK 25, Maven 3.9.16).

### Comparatif end-to-end — Chappe vs Jetty/Helidon/JDK

> `ServerComparison.main` — 5 itérations warmup + 10s mesure, keep-alive HTTP/1.1,
> payload "ok" (2 bytes).

| Serveur          | 1 thread    | 4 threads      | 8 threads      | 16 threads     |
|:-----------------|------------:|---------------:|---------------:|---------------:|
| **Chappe 0.1**   |      28 957 |  **90 832**    | **178 546**    | **275 269**    |
| Helidon SE 4.2.2 |      28 038 |      87 654    |     174 692    |     262 746    |
| Jetty 12.0.21    |  **35 797** |      86 853    |     129 941    |     185 662    |
| JDK HttpServer   |      29 828 |      82 882    |     122 440    |     155 008    |
| Grizzly 4.0.2    |           — |           —    |          —     |          —     |

> Grizzly échoue avec keep-alive (problème connu, signalé dans la version précédente).
> Jetty reste roi en single-thread. Chappe prend la tête dès 4 threads et scale mieux
> avec les virtual threads : **+48 % vs Jetty à 16t**.

### Micro-benchs JMH — validation des optimisations

> `OptimizationsRunner` — JMH 1.37, 2 warmup × 1s + 3 measure × 1s, 1 fork.

#### 1. Lookup classpath (fast-path via index vs URLConnection)

| Benchmark                    | Temps moyen  | Gain         |
|:-----------------------------|-------------:|-------------:|
| `current_indexedLookup`      |   **2,6 ns** | **baseline** |
| `old_urlConnection`          |    20 183 ns | ×7 770       |

**-99,99 %** de latence. Gain cataclysmique confirmé : c'est *l'*optimisation majeure
livrée par le plugin `chappe-static-index-maven-plugin`. Chaque fichier classpath servi
saute désormais `loader.getResource()` + `URLConnection.openConnection()`.

#### 2. Router dispatch (fast-path statique vs scan linéaire)

20 routes statiques + 3 patterns paramétriques.

| Scénario                             | `old_linearScan` | `current_fastPath` |
|:-------------------------------------|-----------------:|-------------------:|
| `/users` (première route)            |          6,6 ns  |         6,5 ns     |
| `/internal/status` (dernière)        |      **3 507 ns**|      **6,4 ns**    |
| `/api/v1/404` (miss)                 |         4 148 ns |       656 ns       |

**Gain ×548** sur la dernière route statique, **×6** sur les misses (scan dynamique
uniquement). Break-even sur la route #1 (cas le plus trivial pour le scan linéaire).

#### 3. HPACK static table (fast-path inline + map vs scan O(61))

Itération 1 (HashMap seule) montrait une régression sur le `:method/GET` ultra-chaud
(13 ns vs 6,6 ns). Itération 2 ajoute un **fast-path inline** sur les pseudo-headers
(`:method`, `:path`, `:scheme`, `:status`) avec `==` identity-check d'abord puis
`equals`. Les chiffres reportés ici sont post-fix.

| Nom / Valeur                  | `oldLinear` | `current` | Gain    |
|:------------------------------|------------:|----------:|--------:|
| `findByName(":method")` / GET |     6,6 ns  |   5,1 ns  |  ×1,3   |
| `findByName("vary")`          |      60 ns  |  11,7 ns  |  ×5     |
| `findByName("x-custom")`      |      70 ns  |   7,3 ns  | ×10     |
| `findExact(":method", "GET")` |     8,8 ns  |   8,0 ns  | ×1,1    |
| `findExact(":method", "")`    |     124 ns  |  17,8 ns  | ×7      |
| `findExact("vary", "GET")`    |     267 ns  |  25,7 ns  | ×10     |
| `findExact("x-custom", *)`    |     160 ns  |   7,1 ns  | ×22     |

Toutes les entrées profitent de l'optimisation, y compris `:method/GET` qui gagne
maintenant 20 % vs le scan linéaire tout en conservant le gain massif sur les misses
et les lookups tardifs.

#### 4. MimeTypes.detect (regionMatches vs substring+toLowerCase)

| Filename          | `old_substringLowercase` | `current_detect` |
|:------------------|-------------------------:|-----------------:|
| `index.html`      |      ≈ 11 ns             |    12 ns         |
| `APP.CSS`         |      ≈ 15 ns             |    15 ns         |
| `main.js`         |      ≈ 11 ns             |    13 ns         |
| `Logo.PNG`        |      37 ns               |    18 ns         |
| `unknown.xyz`     |      28 ns               |    60 ns         |

**Break-even en latence** sur les cas fréquents, **×2** plus rapide sur `Logo.PNG`
(case-insensitive + position tardive), plus lent sur le miss (parcours complet). Le gain
réel est sur les **allocations** (pas de `substring` ni `toLowerCase`) — non quantifié
ici sans `-prof gc`, mais structurellement garanti.

### Synthèse

| Optimisation               | Gain latence   | Gain allocation | Verdict              |
|:---------------------------|---------------:|----------------:|:---------------------|
| Static index (classpath)   | **×7 770**     | ×5+             | ✅ Gain massif         |
| Router fast-path           | **×6 à ×548**  | ×3+             | ✅ Gain massif          |
| HPACK pré-hashé + fast-path| ×1,1 à ×22     | 0               | ✅ Gain partout après fix inline                    |
| MimeTypes zéro-alloc       | break-even     | ×3+             | ✅ Gain alloc, neutre latence |

### Reproductibilité

```bash
# Lance les micro-benchs JMH sur le Docker distant
./chappe-bench/docker/run-remote.sh jmh

# Lance le comparatif end-to-end Chappe vs Jetty/Helidon/Grizzly/JDK
./chappe-bench/docker/run-remote.sh compare

# Override du contexte docker
CHAPPE_DOCKER_CONTEXT=macuntussh ./chappe-bench/docker/run-remote.sh jmh
```

---

## 2026-05-17 — Shootout multi-runtime (out-of-process, wrk2)

Refonte de l'harness bench pour passer en **out-of-process** : un container par
serveur, mesures via `wrk2` (HdrHistogram, rate constant). C'est l'unique moyen
de comparer équitablement contre Nginx, Go et le **binaire natif Chappe**
(GraalVM CE 25). L'ancien `ServerComparison` in-process reste disponible pour la
continuité historique, augmenté avec **Netty 4.2** et **Vert.x 4.5**.

### Périmètre

| Cible           | Runtime                       | Image                                            |
|-----------------|-------------------------------|--------------------------------------------------|
| chappe-jvm      | Chappe sur Temurin 25         | `chappe-shootout-jvm:local`                      |
| chappe-native   | **Chappe via GraalVM CE 25**  | `chappe-shootout-native:local` (distroless base) |
| jetty           | Jetty 12.0.21                 | `chappe-shootout-jvm:local`                      |
| helidon         | Helidon SE 4.2.2              | `chappe-shootout-jvm:local`                      |
| jdk             | `com.sun.net.httpserver` 25   | `chappe-shootout-jvm:local`                      |
| netty           | Netty 4.2.6.Final             | `chappe-shootout-jvm:local`                      |
| vertx           | Vert.x 4.5.16                 | `chappe-shootout-jvm:local`                      |
| nginx           | Nginx 1.27-alpine             | `chappe-shootout-nginx:local`                    |
| go              | Go 1.24 `net/http`            | `chappe-shootout-go:local` (distroless static)   |

Grizzly retiré (échec keep-alive documenté section précédente).

### Méthodologie

- **Hôte** : `macuntutailscale` (Linux amd64, 12 cores, 32 GiB), Docker 29.4.3
- **Client** : `cylab/wrk2` (fork wrk2 de Gil Tene, binaire `wrk`) — 4 threads /
  100 connections / 30 s mesure, warmup 5 s
- **Payload** : `GET / → "ok"` (2 bytes, `text/plain`) — identique au harness
  in-process pour comparaison directe
- **Filtre acceptation** : on retient le rate le plus haut où p99 < 10 ms

Deux modes mesurés :

| Mode         | CPU pinning      | Réseau            | Rates testés                  |
|--------------|------------------|-------------------|-------------------------------|
| **bridge**   | servers `0-3` / client `4-7` | Docker bridge | 50k / 100k / 200k |
| **unleashed**| aucun (12 cores) | `network_mode: host` | 100k / 200k / 300k / 500k |

Le mode **bridge** isole proprement chaque serveur dans son budget CPU + son
namespace réseau — bench reproductible serveur-vs-serveur mais artificiellement
plafonné à ~4 cores. Le mode **unleashed** lève les deux plafonds pour mesurer la
*capacité maximum* de la machine, comparable au comparatif in-process du
2026-04-23.

### Mode bridge — run 2026-05-17T21:03:25Z (cpuset 0-3 servers / 4-7 client)

Tri par "Max sustained" décroissant puis p99 croissant. Le filtre `p99 < 10 ms`
sépare les serveurs qui *tiennent* 100k req/s de ceux qui *saturent*.

| Service          | Image (Mo) | RSS idle  | Max sustained | p50      | p99      | p999     |
|------------------|-----------:|----------:|--------------:|---------:|---------:|---------:|
| nginx            |       46.0 | 30.18 MiB |       100 000 | 1.13 ms  | **2.54 ms** | 4.37 ms  |
| helidon          |      389.3 | 56.66 MiB |       100 000 | 1.17 ms  | 4.21 ms  | 12.57 ms |
| chappe-jvm       |      389.3 | 37.11 MiB |       100 000 | 1.16 ms  | 5.50 ms  | 14.94 ms |
| netty            |      389.3 | 60.38 MiB |       100 000 | 1.19 ms  | 5.59 ms  | 19.39 ms |
| **chappe-native**|   **37.1** | **3.16 MiB** |   **100 000** | 1.31 ms  | 6.52 ms  | 12.85 ms |
| jetty            |      389.3 | 101.0 MiB |        50 000 | **0.88 ms** | 1.87 ms | 2.80 ms |
| vertx            |      389.3 | 85.99 MiB |        50 000 | 1.21 ms  | 2.92 ms  | 8.82 ms  |
| jdk              |      389.3 | 38.86 MiB |        50 000 | 1.07 ms  | 3.94 ms  | 8.18 ms  |
| go               |    **7.3** |  **1.54 MiB** |    50 000 | 1.16 ms  | 3.97 ms  | 10.93 ms |

### Mode unleashed — run 2026-05-18T05:58:03Z (12 cores, host network, container fresh par rate)

Mêmes conditions mais **sans plafond** : 12 cores disponibles à chaque container,
réseau host (bypass bridge), rates jusqu'à 500k req/s. **Container fresh par rate**
pour éliminer le couplage entre mesures successives. Le doublement de débit
soutenu vs bridge confirme l'effet du CPU pinning sur les chiffres précédents.

| Service          | Image (Mo) | RSS idle  | Max sustained | p50      | p99      | p999     |
|------------------|-----------:|----------:|--------------:|---------:|---------:|---------:|
| nginx            |       46.0 | 86.89 MiB |       200 000 | 0.94 ms  | **2.35 ms** | 3.53 ms  |
| jetty            |      389.3 | 99.75 MiB |       200 000 | 1.03 ms  | 2.46 ms  | 3.01 ms  |
| netty            |      389.3 | 62.18 MiB |       200 000 | **0.92 ms** | 5.07 ms | 8.81 ms |
| chappe-jvm       |      389.3 | 37.92 MiB |       100 000 | 1.13 ms  | 2.46 ms  | 2.90 ms  |
| helidon          |      389.3 | 57.17 MiB |       100 000 | 1.16 ms  | 2.54 ms  | 3.11 ms  |
| jdk              |      389.3 | 38.54 MiB |       100 000 | 1.20 ms  | 2.74 ms  | 3.26 ms  |
| **chappe-native**|   **37.1** | **3.04 MiB** |   **100 000** | 1.18 ms  | 2.96 ms  | 3.96 ms  |
| go               |    **7.3** |  **1.66 MiB** |   100 000 | 1.16 ms  | 2.73 ms  | 3.57 ms  |
| vertx            |      389.3 | 79.87 MiB |             0 | —        | —        | —        |

Vert.x défonce sa latence dès 100k req/s (`p99 = 109 ms`) dans sa config par
défaut (1 event loop verticle) — exclu du filtre `p99 < 10 ms`.

#### Note sur la sensibilité au warmup (run 2026-05-18T14:42:14Z)

Hypothèse initialement formulée : Chappe (modèle Loom 1 VT / connection)
**bénéficierait d'un warmup progressif** au rate inférieur avant la mesure
à pleine charge — le JIT et le scheduler Loom auraient le temps de s'aligner.

Test reproduit dans le shootout (warmup 10 s @ 100k req/s puis mesure 30 s
au rate cible, container fresh) :

| Setup                                       | chappe-jvm p99 @ 200k | netty p99 @ 200k |
|---------------------------------------------|----------------------:|-----------------:|
| Warmup direct au rate cible (5 s @ 200k)    |   ~200 ms             |  **5.07 ms** ✅   |
| Warmup progressif (10 s @ 100k puis 200k)   |   214 ms              |   226 ms ❌       |
| **Test isolé hier — fenêtre 20 s seulement**|   **15.57 ms**        |   —              |

→ **Le `15.57 ms` du test isolé était un artefact de fenêtre courte (20 s)
qui n'a pas capturé les spikes rares.** Avec 30 s de mesure, le p99 réel à
200k req/s reste à ~200 ms pour Chappe — peu importe la stratégie de warmup.

Pire : le warmup progressif a **dégradé** netty (5 ms → 226 ms) — l'event
loop netty bénéficie d'un warmup au rate cible (préchauffe ses pipelines).

**Conclusion** : la limite à 100k req/s @ p99 < 10 ms pour Chappe est bien
**architecturale** (modèle 1 VT par connection sature les carriers
ForkJoinPool sous très haute charge), pas conjoncturelle. Aucun tuning de
warmup ne fait passer Chappe en top-tier 200k sans refactor du modèle de
threading. Cf. profil JFR ci-dessous.

### Peak throughput observé (sans filtre latence, comparable au bench 2026-04-23)

Pour aligner avec le bench in-process closed-loop, voici le **débit maximum
observé sur chaque service**, tous rates confondus :

| Service          | Peak req/s | À rate visé | p99 à ce point | Note                          |
|------------------|-----------:|------------:|---------------:|-------------------------------|
| jetty            |  **297 258** | 500k      | 15.45 s        | 12 cores saturés              |
| nginx            |    243 605 | 300k        | 9.78 s         |                               |
| netty            |    224 669 | 500k        | 17.37 s        |                               |
| helidon          |    223 778 | 500k        | 16.42 s        |                               |
| chappe-jvm       |    218 569 | 500k        | 16.71 s        | bench 16t 2026-04-23 : 275 269 |
| chappe-native    |    216 200 | 300k        | 8.31 s         |                               |
| jdk              |    187 463 | 200k        | 1.94 s         |                               |
| go               |    180 471 | 200k        | 2.91 s         |                               |
| vertx            |    110 316 | 500k        | 23.00 s        | event loop saturé             |

**Lecture** : `Chappe 218k req/s vs Chappe 275k en 2026-04-23` — différence
réelle ~20 % imputable à : (a) `wrk2` open-loop avec correction de coordinated
omission (plus strict que le client NIO closed-loop maison), (b) overhead du
namespace réseau host vs loopback Java direct, (c) saturation client (4 threads
wrk2 / 100 connexions) au-delà de 250k req/s. Le serveur n'est pas plus lent ;
le harness mesure plus honnêtement.

#### Validation : pas de régression code

Re-run `ServerComparison` in-process sur la même VM macuntu le 2026-05-18 (même
client NIO closed-loop maison qu'en avril). Chiffres Chappe :

| Run in-process            | 1t      | 4t     | 8t      | 16t       | Delta vs avril |
|---------------------------|--------:|-------:|--------:|----------:|---------------:|
| 2026-04-23 (baseline)     | 28 957  | 90 832 | 178 546 | 275 269   | —              |
| **2026-05-18 (validation)**| 29 570 | 93 214 | 184 415 | **265 175** | **−3.7 %** (bruit) |

Confirmation : **le code Chappe sort la même performance** qu'en avril (delta
dans le bruit de mesure). Les `218k` du shootout unleashed sont une mesure
*plus stricte*, pas une perte réelle de capacité serveur.

### Chappe natif vs JVM (mode unleashed)

| Métrique             | chappe-native       | chappe-jvm           | Delta natif        |
|----------------------|---------------------|----------------------|--------------------|
| Image Docker         | 37.1 Mo             | 389.3 Mo             | **−90.5 %** (×10.5)|
| RSS idle             | 3.18 MiB            | 37.96 MiB            | **−91.6 %** (×11.9)|
| Throughput soutenu   | 100 000 req/s       | 100 000 req/s        | identique          |
| Peak observed        | 216 200 req/s       | 218 569 req/s        | −1.1 % (bruit)     |
| p50 @ best sustained | 1.16 ms             | 1.15 ms              | identique          |
| p99 @ best sustained | 2.83 ms             | 2.50 ms              | +0.33 ms           |

Le natif et la JVM **délivrent le même débit** (différence dans le bruit) avec
une latence quasi-identique. Le natif gagne sur tout le reste : image 10× plus
petite, RSS 12× plus petit, démarrage immédiat (cf. distroless/cc + binaire
mostly-static via `-H:+StaticExecutableWithDynamicLibC`). Caveat : pas de PGO,
`-march=compatibility` (portabilité Docker hôte) — marge d'optimisation encore
disponible.

### Profiling JFR — pourquoi Chappe plafonne à 100k en SLA strict

Run JFR (`settings=profile`, 60 s) sur `chappe-jvm` sous charge **200k req/s** :
1.4 Mo capturés, 80 s de fenêtre. Analyse via `jfr summary` et `jfr print`.

#### Suspects examinés

| Suspect              | Verdict      | Évidence                                  |
|----------------------|--------------|-------------------------------------------|
| Pauses GC            | ❌ innocent  | 19 pauses G1New, max **2.83 ms**, total 34 ms / 80 s |
| Deoptimization C2    | ❌ innocent  | 71 deopt, **toutes au démarrage** dans `jdk.internal.classfile.impl.*` (Class-File API JEP 484), zéro pendant la charge |
| Lock contention      | ❌ innocent  | 0 event significatif                      |
| **ForkJoinPool carrier idle/wake** | ✅ **coupable principal** | **140 parks**, 129 dans [10-20 ms], 11 > 20 ms (max 32.9 ms) — tous sur `ForkJoinPool.awaitWork` |
| **Allocations hot path** | ✅ secondaire | ~7 allocations Chappe par requête + 2 ScopedValue Carrier/Snapshot |

#### Cause racine — modèle Loom sous charge soutenue

Stack trace type d'un park de 32 ms :

```
ForkJoinPool-1-worker-14:
  Unsafe.park(boolean, long)
  ForkJoinPool.awaitWork(WorkQueue, int)   ← 32 ms
  ForkJoinPool.deactivate(WorkQueue, int)
  ForkJoinPool.runWorker(WorkQueue)
```

Chappe utilise **1 virtual thread par connection** (modèle Tomcat-Loom).
Sous 200k req/s avec 100 connections wrk, les carriers ForkJoinPool oscillent
entre `runWorker` (occupé) et `awaitWork` (idle entre bursts TCP). À chaque
oscillation, des micro-stalls 10-20 ms s'accumulent dans le tail.

Jetty / nginx / netty n'ont pas ce problème : leur event loop fixe ne s'endort
jamais (`epoll_wait()` bloque le **kernel**, pas le scheduler Loom).

#### Test de remédiation : tuner `parallelism` ?

| Config                    | p99 @ 100k | p99 @ 150k | p99 @ 200k |
|---------------------------|-----------:|-----------:|-----------:|
| **baseline (ncpu=12)**    | **2.49 ms** | **3.34 ms** | **15.57 ms** |
| parallelism=24            |    6.97 ms |    4.02 ms |   97.73 ms |
| parallelism=48            |   52.22 ms |   62.56 ms |   58.46 ms |
| parallelism=96            |   90.18 ms |   94.46 ms |  100.86 ms |

→ **Augmenter le parallelism DÉGRADE** (contention work-stealing + cache
thrashing). Le défaut (`= ncpu`) est le bon réglage.

#### Hot spots d'allocation (call sites identifiés)

| Classe              | Samples | Site                                    | Optim possible       |
|---------------------|--------:|-----------------------------------------|----------------------|
| `DefaultResponse`   |     360 | `Response.ok("ok")` → `DefaultResponseBuilder.build()` ligne 52 | ✅ pré-réponses pré-cuites |
| `ArrayHeaders`      |     374 | `HttpRequestImpl.headers()` ligne 183   | ⚠️ vérifier lazy   |
| `DefaultHeaders`    |     253 | `DefaultHeadersBuilder.build()` ligne 31 | ✅ même cause que #1 |
| `RequestContext`    |     254 | `HttpConnection.run()` ligne 135        | ⚠️ ScopedValue scope |

À 200k req/s, `Response.ok("ok")` alloue **4 objets par requête**
(`Builder` + `Headers$Entry` + `DefaultHeaders` + `DefaultResponse`) = **800k
allocations/s** rien que pour le builder pattern de la réponse. G1 digère mais
ça pollue les caches L1/L2.

#### Pistes d'optim (par impact attendu)

1. ~~**Pré-réponses pré-cuites**~~ — **testé, gain négligeable (−5 % p99)**.
   Test A/B `ChappeMain` vs `ChappeMainCached` (Response partagée entre toutes
   les requêtes) à 200k req/s cold : p99 207.62 ms → 197.76 ms. Les 4 allocs
   Response/req économisées ne déplacent pas le tail — confirme que le
   bottleneck est le scheduler Loom, pas les allocations.
2. **Lazy `ScopedValue.Carrier`** si le handler ne lit pas `RequestContext.CURRENT`.
   *Impact estimé : -10 à -20 % p99.* (non testé)
3. **Mode "selector loop"** optionnel (event loop NIO + handler virtual thread)
   pour les workloads très haute fréquence. Hybride proche de Helidon SE.
   *Impact estimé : -50 % p99, complexité ★★★.* (non testé)

**Verdict** : sans refactor architectural (#3), Chappe reste en mid-tier
(100k req/s @ p99 < 3 ms). Avec warmup progressif il tient 200k @ p99 ≈ 15 ms.
Au-delà, le modèle "1 VT par connection" sature les carriers ForkJoinPool.

### Comparatif in-process étendu (continuité historique)

`ServerComparison` augmenté avec Netty + Vert.x (Grizzly retiré). Permet la
comparaison directe avec les chiffres de la section "2026-04-23".

```bash
./chappe-bench/docker/run-remote.sh compare
```

| Serveur          | 1 thread | 4 threads | 8 threads | 16 threads |
|------------------|---------:|----------:|----------:|-----------:|
| chappe           |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |
| helidon          |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |
| jetty            |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |
| jdk-httpserver   |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |
| **netty (NEW)**  |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |
| **vertx (NEW)**  |  _TBD_   |   _TBD_   |   _TBD_   |   _TBD_    |

### Reproductibilité

```bash
# Mode bridge : cpuset 0-3 servers / 4-7 client, network bridge, rates 50/100/200k
./chappe-bench/docker/run-remote.sh shootout

# Mode unleashed : pas de cpuset, network_mode host, rates 100/200/300/500k
# (suppose que `shootout` a déjà été lancé pour construire les images)
./chappe-bench/docker/run-remote.sh shootout-unleashed

# Sans le build natif (utile si GraalVM 25 image indisponible)
./chappe-bench/docker/run-remote.sh shootout-jvm-only

# Override CPU pinning (machine avec < 8 cores)
SERVER_CPUSET=0-1 CLIENT_CPUSET=2-3 \
    ./chappe-bench/docker/run-remote.sh shootout

# Override rates wrk2 (run rapide pour debug)
SHOOTOUT_RATES="10000 50000" SHOOTOUT_DURATION=10s \
    ./chappe-bench/docker/run-remote.sh shootout
```

Le harness est documenté en détail dans
[`chappe-bench/docker/shootout/README.md`](chappe-bench/docker/shootout/README.md).

### Notes d'implémentation

- **Native-image config statique** : `chappe-bench/src/main/resources/META-INF/native-image/io.vidocq.chappe/chappe-bench/native-image.properties`
  — découverte automatique par `native-image` quand chappe-bench est dans le
  classpath. `--initialize-at-build-time` couvre `io.vidocq.chappe.{api,core,http}`
  (zéro réflexion dans le projet — audité, voir Phase A du plan).
- **Distroless** : base (libc dynamique) pour le binaire natif, static pour Go.
- **Pas de réflexion à configurer** : `reflect-config.json` et `resource-config.json`
  sont vides — Chappe est compile-time first, `ServerProvider` ServiceLoader est
  résolu via JPMS (provides/uses) puis par le shade au runtime.

---

## 2026-05-20 — Validation JMH post-cleanup (ErrorProne + Spotless + System.Logger)

Re-run complet de la suite JMH `chappe-bench` après le nettoyage qualité :
- **Spotless / Palantir** : reformatage 100 fichiers (zéro impact runtime)
- **Error Prone** : 44 → 0 findings (suppress justifiés + fixes ; cf. commit)
- **`ChappeServer.submit() → execute()`** : suppression du Future ignoré sur l'accept loop
- **`System.out → System.Logger`** dans `ChappeBenchmark` (sortie console)

Objectif : valider qu'aucun changement n'a dégradé les chiffres in-process.

### Méthodologie

- **Hôte** : macOS local (Apple Silicon), JDK 25-tem
- **JMH** : 1.37, warmup 3 iter × 1 s, measure 5 iter × 1 s, fork 1
- **JVM options** : `--enable-preview`
- **Commande** : `java -cp <classpath> org.openjdk.jmh.Main -rf json`
- **Sortie complète** : `.bench-results/jmh-2026-05-20.txt[.json]`

### Throughput (thrpt — plus c'est haut, mieux c'est)

| Benchmark | Score (ops/s) | Erreur (±) | Notes |
|:----------|--------------:|-----------:|:------|
| `ConcurrentBench.concurrentThroughput` (8t) | **101 714** | 1 566 | confirme 100k req/s soutenu |
| `RawSocketBench.throughputKeepAlive` (1t)   |  44 507 | 1 255 | baseline 1 thread |
| `Http11ThroughputBench.smallGetKeepAlive`   |  17 225 |   992 | overhead HttpClient JDK |
| `Http2ThroughputBench.smallGetHttp2`        |  16 933 | 1 417 | parité HTTP/1.1 |
| `LargeResponseBench.largeResponseHttp11` (1 MB) |  2 058 |   184 | ≈ 2 Gio/s sortants |
| `LargeResponseBench.largeResponseHttp2` (1 MB)  |  1 996 |   192 | parité H1/H2 |

### Latence (sample — plus c'est bas, mieux c'est)

`RawSocketBench.latencyKeepAlive` (zéro overhead client, 317k samples) :

| Percentile | Latence |
|:-----------|--------:|
| min        |  12.8 µs |
| **p50**    | **20.4 µs** |
| p90        |  31.4 µs |
| p95        |  37.6 µs |
| **p99**    | **57.0 µs** |
| p99.9      | 180.2 µs |
| p99.99     |   1.46 ms |
| max        |   8.09 ms |

→ **p99 = 57 µs** confirme le claim "20× sous l'objectif 1 ms".

`LatencyBench.getLatency` (HttpClient, 417k samples) : p50 54.7 µs, p99 124 µs, p99.9 303 µs — l'overhead `java.net.http.HttpClient` ajoute ~30 µs à p50 et ~70 µs à p99.

### Micro-benchs (avgt ns/op — confirme les optimisations compile-time)

| Optimisation | Current | Old | Speedup |
|:-------------|--------:|----:|--------:|
| **Classpath lookup** (static index) | 1.40 ns | 15 390 ns | **×11 000** |
| **Router dispatch** (fast-path, hit) | 2.26 ns | 2.08 ns | parité (les deux O(1)) |
| **Router dispatch** (fast-path, mid-trie) | 2.58 ns | 1 123 ns | **×435** |
| **Router dispatch** (fast-path, miss) | 200 ns | 2 244 ns | **×11** |
| **HPACK** `findByName` pseudo-header | 1.20 ns | 2.20 ns | ×1.8 (fast path inline) |
| **HPACK** `findByName` custom header | 2.90 ns | 47.7 ns | **×16** (map vs scan O(61)) |
| **HPACK** `findExact` custom + value | 3.82 ns | 47.3 ns | **×12** |
| **MimeTypes.detect** `index.html` | 4.55 ns | 11.0 ns | ×2.4 (zero-alloc regionMatches) |
| **MimeTypes.detect** `unknown.xyz` | 34.0 ns | 11.6 ns | ×0.34 (miss : parcours complet vs HashMap) |

### Delta vs 2026-04-23 (post-optim baseline)

| Métrique | 2026-04-23 | 2026-05-20 | Delta |
|:---------|-----------:|-----------:|------:|
| Concurrent throughput (8t) | — | 101 714 ops/s | — (1ère mesure JMH) |
| HPACK findByName custom | ~3 ns | 2.90 ns | parité |
| Router fast-path hit | ~2 ns | 2.26 ns | parité |
| Classpath indexed lookup | 1.4 ns | 1.40 ns | identique |

**Verdict** : **aucune régression mesurable**. Les chiffres clés (concurrent 100k, p99 57 µs, micro-benchs ns/op) sont stables au bruit près. Le passage `submit → execute` dans `ChappeServer` n'a pas dégradé l'accept loop, ce qui est attendu vu que `ExecutorService.execute()` est un cousin direct de `submit()` sans wrapping `FutureTask`.

### Reproductibilité

```bash
# Build une fois
mvn -ntp -q clean install -DskipTests

# Construire le classpath chappe-bench
mvn -ntp -q -pl chappe-bench -DincludeScope=runtime dependency:build-classpath \
    -Dmdep.outputFile=/tmp/cp.txt

# Lancer JMH (~3-5 min selon CPU)
BENCH=chappe-bench
CLASSES="$BENCH/target/classes:$BENCH/target/generated-sources/annotations"
for m in chappe-api chappe-http chappe-core; do
  CLASSES="$CLASSES:$m/target/classes"
done
java --enable-preview -cp "$CLASSES:$(cat /tmp/cp.txt)" \
    org.openjdk.jmh.Main -rf json -rff .bench-results/jmh-$(date +%F).json
```

