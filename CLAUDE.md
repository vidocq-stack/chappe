# Chappe - Claude Code Guidelines

> Claude Chappe (1763–1805) a inventé le télégraphe sémaphorique — un réseau de tours optiques
> qui couvrait toute la France et transmettait des messages à des centaines de km en quelques minutes.
> C'était littéralement le serveur HTTP le plus rapide de son époque.

## Projet

Serveur HTTP haute performance en Java 25 pur (zéro dépendance hors JDK), conçu pour servir
de fondation aux futurs projets JAX-RS et Servlet de l'écosystème Vidocq.

### Protocoles implémentés
- **HTTP/1.1** — RFC 9110/9112 (keep-alive, chunked, pipelining, conformité testée)
- **HTTP/2** — RFC 9113 (multiplexage, HPACK Huffman, flow control, CONTINUATION)
- **HTTPS** — TLS via SSLContext/SSLEngine, ALPN h2 + http/1.1
- **HTTP/3** — RFC 9114 (objectif futur, QUIC via JDK 26+)

### Architecture
- Virtual Threads (Project Loom) — un thread virtuel par connexion
- Scoped Values (JEP 506) — `RequestContext.CURRENT` propagé avant chaque handler
- Zero-copy I/O — `FileChannel.transferTo()` pour fichiers statiques
- Java Modules (JPMS) — chaque module a un `module-info.java`
- ServiceLoader SPI — `ServerProvider` pour découvrir l'implémentation

### Intégration Vauban (prévue, pas encore active)
Chappe fonctionne **de manière autonome sans Vauban**. L'intégration CDI se fera
via les extensions `vidocq-servlet` et `vidocq-jaxrs` qui utiliseront Vauban pour
le lifecycle des composants. Chappe lui-même utilise `ServiceLoader` (pas CDI).
- `vauban-api`/`vauban-core` sont déclarés dans le parent POM mais non utilisés par les modules
- Source Vauban : `../vauban/` (projet frère dans le monorepo)

### Modules
| Module | Description |
|---|---|
| `chappe-api` | API publique : `Server`, `Router`, `Handler`, `Request`, `Response`, `Filter`, `StaticFileHandler`, `MimeTypes`, `AcceptEncoding`, `RequestContext` |
| `chappe-http` | Protocoles HTTP/1.1 et HTTP/2, SslHandler TLS, ByteBufferPool |
| `chappe-core` | Moteur serveur, virtual threads, protocol detection, lifecycle |
| `chappe-cli` | Launcher CLI standalone `chappe serve` (mini-YAML, fat jar, jlink) — voir `chappe-cli/README.md` |
| `chappe-tests` | Tests d'intégration |
| `chappe-bench` | Benchmarks : comparatif Jetty/Helidon/JDK, throughput/latence |
| `chappe-conformance` | Suite de conformité HTTP (45 tests RFC 9110/9112/9113) |
| `chappe-examples` | Exemples d'utilisation |
| `chappe-static-index-maven-plugin` | Plugin Maven : index O(1) + sidecars `.gz` au build (`<compress>gzip</compress>`) |

### Extension SPI
Chappe fournit les hooks pour les extensions Servlet/JAX-RS/WebSocket :
- **`Router.mount(prefix, handler)`** — enregistrement par path prefix avec stripping automatique
- **`Request.contextPath()`/`pathInfo()`** — path relatif au mount point
- **`Request.attribute(key, value)`** — attributs mutables per-request (Servlet compat)
- **`Request.remoteAddress()`/`isSecure()`/`scheme()`** — metadata connexion
- **`RequestContext.CURRENT`** — ScopedValue propagé avant chaque handler
- **`Body.ofOutputStream()`** — streaming body (Servlet OutputStream compat)
- **`Body.ofFile()`** — zero-copy via `FileChannel.transferTo()` (sendfile)
- **`StaticFileHandler.builder()`** — fichiers statiques avec fallback chain (filesystem → classpath), cache mémoire, ETag, Cache-Control, `notFoundFile`/`spaFallback`, `preferPrecompressed` (sidecars `.br`/`.gz`)
- **`Filter.addHeader()`/`addHeaderIf()`/`addHeaderIfEnv()`/`gzip()`** — middleware déclaratif headers + compression
- **`AcceptEncoding.parse()`/`accepts()`** — négociation `Accept-Encoding` (RFC 9110 §12.5.3)
- **`MimeTypes.detect()`** — détection MIME par extension (26+ types)

### Validation du Serveur
Le serveur doit être validé sur trois axes :
- **Conformité protocole** — vérifier le respect des RFC via une suite de tests exhaustive (headers, chunked encoding, status codes, HTTP/2 framing, HPACK, flow control, stream priorities)
- **Performance** — benchmarks JMH reproductibles : throughput (req/sec), latence (p50/p99/p999), allocation mémoire (GC pressure), scalabilité (connexions concurrentes)
- **Robustesse** — tests de charge prolongée (soak tests), requêtes malformées, slow clients (slowloris), connexions abandonnées, backpressure, limites mémoire

## 1. Plan Mode Default

- Entrer en plan mode pour toute tâche non-triviale (3+ étapes ou décisions d'architecture)
- Si quelque chose tourne mal, STOP et re-planifier immédiatement
- Utiliser le plan mode pour les étapes de vérification, pas seulement la construction
- Écrire des specs détaillées en amont pour réduire l'ambiguïté

## 2. Stratégie Subagents

- Utiliser les subagents fréquemment pour garder la fenêtre de contexte principale propre
- Déléguer la recherche, l'exploration et l'analyse parallèle aux subagents
- Pour les problèmes complexes, utiliser plus de compute via subagents
- Assigner une tâche par subagent pour une exécution focalisée

## 3. Boucle d'Amélioration Continue

- Après toute correction de l'utilisateur, mettre à jour `tasks/lessons.md`
- Écrire des règles pour éviter de répéter la même erreur
- Itérer sans pitié sur ces leçons
- Revoir les leçons au début de chaque session

## 4. Vérification Avant Terminaison

- Ne jamais marquer une tâche complète sans preuve de fonctionnement
- Comparer le comportement entre main et les changements quand pertinent
- Se demander : "Un staff engineer approuverait-il ceci ?"
- Exécuter les tests, vérifier les logs, démontrer la correction

## 5. Exiger l'Élégance (Équilibrée)

- Pour les changements non-triviaux, demander : "Y a-t-il une solution plus élégante ?"
- Si un fix semble hacky, demander : "Connaissant tout ce que je sais, implémenter la solution élégante."
- Sauter ceci pour les fixes simples — ne pas sur-engineer
- Challenger son propre travail avant de le présenter

## 6. Correction de Bugs Autonome

- Quand on reçoit un rapport de bug : juste le corriger
- Utiliser logs, erreurs et tests qui échouent pour diagnostiquer
- Nécessiter zéro context switching de l'utilisateur
- Corriger les tests CI qui échouent automatiquement

## Gestion des Tâches

1. **Planifier** – Écrire le plan dans `tasks/todo.md` avec des items cochables
2. **Vérifier le plan** – Confirmer le plan avant implémentation
3. **Suivre la progression** – Marquer les items complétés au fur et à mesure
4. **Expliquer les changements** – Fournir un résumé haut niveau à chaque étape
5. **Documenter les résultats** – Ajouter une section review à `tasks/todo.md`
6. **Capturer les leçons** – Mettre à jour `tasks/lessons.md` après corrections

## Environnement

- Utiliser **sdkman** pour gérer les versions Java et Maven
- Requis : **Java 25** (`sdk use java 25-open` ou équivalent)
- Requis : **Maven 4** (`sdk use maven 4.0.0-rc-5`)
- Si `mvn` échoue avec "modelVersion 4.1.0 not supported", Maven `current` a été reset à 3.x — switcher à 4.x

## Context Mode

- Utiliser `ctx_batch_execute` pour les commandes produisant beaucoup d'output (builds, tests, logs)
- Utiliser `ctx_search` pour les recherches de suivi après un batch_execute
- Utiliser `ctx_execute` / `ctx_execute_file` pour l'analyse de données, parsing de logs, transformations
- **Ne jamais** utiliser Bash pour des commandes produisant >20 lignes d'output — passer par context-mode
- **Ne jamais** utiliser ctx_execute/ctx_execute_file pour créer ou modifier des fichiers — utiliser Write/Edit
- Read est réservé aux fichiers qu'on va éditer ensuite — pour l'analyse, utiliser ctx_execute_file

## Conventions de Code

### Nommage
- Packages : `io.vidocq.chappe.*`
- GroupId Maven : `io.vidocq.chappe`
- Modules JPMS : `io.vidocq.chappe.*`

### Standards
- Zéro dépendance hors JDK — c'est la règle absolue du projet
- Tout le code utilise les virtual threads — jamais de pool de threads classique
- Préférer les records aux classes pour les objets immutables
- Utiliser les sealed interfaces pour les hiérarchies de types fermées
- Pattern matching exhaustif avec switch expressions
- Utiliser `java.lang.foreign` pour les opérations mémoire critiques

### Tests
- JUnit 6 (jupiter)
- Tests d'intégration avec des vrais sockets (pas de mocking HTTP)
- Benchmarks avec JMH dans un module séparé si nécessaire

### Performance (résultats mesurés, voir BENCHMARKS.md)
- **101K ops/s** throughput concurrent JMH 8t (run 2026-05-20) ; **96K req/s** in-process closed-loop NIO (run 2026-04-16, validé)
- **Latence p99 = 57 µs** raw socket (run JMH 2026-05-20, 317k samples) — 17× sous l'objectif de 1ms
- Optimisations : write coalescing, zero-alloc headers, thread-local buffer pool, fast path 200 OK
- Zero-allocation sur le hot path (réutiliser les buffers)

## Principes Fondamentaux

### Simplicité d'abord
Chaque changement doit être aussi simple que possible et minimiser l'impact sur le code.

### Pas de paresse
Trouver les causes racines. Éviter les fixes temporaires. Maintenir des standards d'ingénierie senior.

### Zéro dépendance
Si une fonctionnalité nécessite une dépendance externe, elle n'a pas sa place dans Chappe.
La seule exception est le framework de test (JUnit).
