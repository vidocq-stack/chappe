<p align="center">
  <img src="chappe-logo.png" alt="Chappe" width="360"/>
</p>

<h1 align="center">Chappe</h1>

<p align="center">
  <strong>Serveur HTTP haute performance en Java 25 pur — zéro dépendance, virtual threads, CDI 4.1</strong>
</p>

<p align="center">
  <a href="https://www.java.com/"><img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25"/></a>
  <a href="https://maven.apache.org/"><img src="https://img.shields.io/badge/Maven-4.0-blue?logo=apachemaven" alt="Maven 4"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-green.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/HTTP-1.1%20%7C%202%20%7C%203-red" alt="HTTP 1.1/2/3"/>
  <img src="https://img.shields.io/badge/dependencies-0-brightgreen" alt="Zero deps"/>
</p>

---

> **Claude Chappe** (1763–1805) a inventé le télégraphe sémaphorique — un réseau de tours
> optiques qui couvrait toute la France et transmettait des messages à des centaines de
> kilomètres en quelques minutes. C'était littéralement le serveur HTTP le plus rapide
> de son époque. Ce projet en est l'héritier numérique.

## Présentation

**Chappe** est un serveur HTTP moderne écrit en **Java 25 pur**, sans aucune dépendance
externe hors JDK. Il est conçu pour servir de fondation aux futurs projets JAX-RS et
Servlet de l'écosystème **Vidocq**.

Sa devise : *aller vite, sans bagage*.

### Caractéristiques

- **HTTP/1.1** — RFC 9110 / 9112, implémentation complète
- **HTTP/2** — RFC 9113, multiplexage, HPACK, server push, flow control
- **HTTP/3** — RFC 9114 (cible future, QUIC via JDK 26+)
- **Virtual Threads** (Project Loom) — un thread virtuel par connexion, scalabilité massive
- **Structured Concurrency** (JEP 505) — cycle de vie des requêtes propre et interruptible
- **Scoped Values** (JEP 506) — propagation du contexte requête sans `ThreadLocal`
- **Zero-copy I/O** via `java.nio` et `Foreign Function & Memory API`
- **Java Modules** (JPMS) — chaque module a son `module-info.java`
- **Zéro dépendance** — tout en JDK, hors JUnit pour les tests

### Objectifs de performance

| Métrique | Cible |
|---|---|
| Throughput HTTP/1.1 (keep-alive) | **> 100 000 req/sec** |
| Throughput HTTP/2 (multiplexé)   | **> 150 000 req/sec** |
| Latence p99 (static)              | **< 1 ms** |
| Allocation hot path               | **zero-alloc** (buffers réutilisés) |

## Architecture

Chappe s'intègre avec **Vauban** (`fr.vidocq.vauban`), le conteneur **CDI 4.1** maison
de l'écosystème Vidocq. Les handlers, routers et filtres sont des beans CDI, et le
serveur démarre sur un événement `@Observes @Initialized(ApplicationScoped.class)`.

```
┌─────────────────────────────────────────────────────────┐
│                   chappe-examples                        │
├─────────────────────────────────────────────────────────┤
│    chappe-tests        chappe-bench       chappe-conf.  │
├─────────────────────────────────────────────────────────┤
│                     chappe-http                          │
│              (HTTP/1.1, HTTP/2, HPACK)                   │
├─────────────────────────────────────────────────────────┤
│                     chappe-core                          │
│     (moteur serveur, virtual threads, lifecycle)         │
├─────────────────────────────────────────────────────────┤
│                     chappe-api                           │
│    (Server, Router, Handler, Request, Response)          │
├─────────────────────────────────────────────────────────┤
│                Vauban (CDI 4.1 container)                │
├─────────────────────────────────────────────────────────┤
│              Java 25 (Loom, FFM, JPMS)                   │
└─────────────────────────────────────────────────────────┘
```

## Modules

| Module | Rôle |
|---|---|
| [`chappe-api`](chappe-api/)             | API publique : `Server`, `Router`, `Handler`, `Request`, `Response` |
| [`chappe-core`](chappe-core/)           | Moteur serveur, virtual threads, lifecycle |
| [`chappe-http`](chappe-http/)           | Implémentation des protocoles HTTP/1.1 et HTTP/2 |
| [`chappe-tests`](chappe-tests/)         | Tests d'intégration (sockets réels, zéro mock HTTP) |
| [`chappe-bench`](chappe-bench/)         | Benchmarks JMH et validation de performance |
| [`chappe-conformance`](chappe-conformance/) | Suite de conformité aux RFC (9110/9112/9113) |
| [`chappe-examples`](chappe-examples/)   | Exemples d'utilisation |

## Prérequis

- **Java 25** (avec `--enable-preview`)
- **Maven 4** (`4.0.0-rc-5` ou supérieur — le POM utilise le modelVersion `4.1.0`)

Via [SDKMAN!](https://sdkman.io/) :

```bash
sdk use java 25-open
sdk use maven 4.0.0-rc-5
```

## Build

```bash
mvn clean install
```

## Utilisation (aperçu)

```java
import fr.vidocq.chappe.api.Server;

void main() {
    Server.builder()
        .port(8080)
        .route("/hello", (req, res) -> res.text("Hello from Chappe!"))
        .build()
        .start();
}
```

## Validation

Le serveur est validé sur **trois axes** :

1. **Conformité protocole** — suite exhaustive contre les RFC : headers, chunked
   encoding, status codes, framing HTTP/2, HPACK, flow control, stream priorities.
2. **Performance** — benchmarks JMH reproductibles : throughput, latence (p50/p99/p999),
   pression GC, scalabilité en connexions concurrentes.
3. **Robustesse** — soak tests, requêtes malformées, slow clients (slowloris),
   connexions abandonnées, backpressure, limites mémoire.

## Écosystème Vidocq

Chappe fait partie de la famille **Vidocq**, un ensemble de briques Java modernes sans
dépendance :

- **[Vauban](https://github.com/VidocqMP/vauban)** — conteneur CDI 4.1
- **Chappe** — serveur HTTP (ce dépôt)
- … et plus à venir

## Licence

[Apache License 2.0](LICENSE) — © Yann Blazart
