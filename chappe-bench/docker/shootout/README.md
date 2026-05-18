# Chappe Shootout — Harness OOP multi-runtime

Compare Chappe (JVM + native) contre les serveurs HTTP de référence du marché en
**out-of-process** : un container par serveur, mesures via `wrk2` (HdrHistogram,
rate constant). Complément du `ServerComparison` in-process — l'OOP est l'unique
moyen de comparer équitablement contre Nginx, Go et le binaire natif Chappe.

## Cibles

| Service       | Lib / Runtime           | Image                                            |
|---------------|-------------------------|--------------------------------------------------|
| chappe-jvm    | Chappe sur Temurin 25   | `chappe-shootout-jvm:local`                      |
| chappe-native | Chappe via GraalVM CE 25| `chappe-shootout-native:local` (distroless base) |
| jetty         | Jetty 12.0.21           | `chappe-shootout-jvm:local`                      |
| helidon       | Helidon SE 4.2.2        | `chappe-shootout-jvm:local`                      |
| jdk           | `com.sun.net.httpserver`| `chappe-shootout-jvm:local`                      |
| netty         | Netty 4.2.6.Final       | `chappe-shootout-jvm:local`                      |
| vertx         | Vert.x 4.5.16           | `chappe-shootout-jvm:local`                      |
| nginx         | Nginx 1.27-alpine       | `chappe-shootout-nginx:local`                    |
| go            | Go 1.24 `net/http`      | `chappe-shootout-go:local` (distroless static)   |

Tous exposent `GET / → "ok"` (2 bytes, `text/plain`) sur le port interne 8080.

## Architecture

```
   ┌─────────────────────── docker compose ─────────────────────────┐
   │                                                                │
   │  network: shootout (bridge)                                    │
   │                                                                │
   │   ┌─────────┐ ┌────────┐ ┌─────────┐ ┌──────────────┐          │
   │   │ chappe  │ │  jetty │ │ helidon │ │ chappe-native│  …       │
   │   │  :8080  │ │  :8080 │ │  :8080  │ │   :8080      │          │
   │   └────┬────┘ └───┬────┘ └────┬────┘ └──────┬───────┘          │
   │        │          │           │             │                  │
   │        └──────────┴─────┬─────┴─────────────┘                  │
   │                         │                                      │
   │                  ┌──────┴───────┐                               │
   │                  │     wrk2     │  cpuset 4-7                  │
   │                  │  (client)    │                              │
   │                  └──────────────┘                               │
   │                                                                │
   │  servers cpuset: 0-3                                           │
   └────────────────────────────────────────────────────────────────┘
```

## Reproductibilité

```bash
# Depuis la racine du repo chappe :
./chappe-bench/docker/run-remote.sh shootout            # tout (avec native)
./chappe-bench/docker/run-remote.sh shootout-jvm-only   # sans chappe-native

# Override CPU sets si moins de 8 cores :
SERVER_CPUSET=0-1 CLIENT_CPUSET=2-3 \
    ./chappe-bench/docker/run-remote.sh shootout

# Override rates wrk2 :
SHOOTOUT_RATES="10000 50000" SHOOTOUT_DURATION=10s \
    ./chappe-bench/docker/run-remote.sh shootout
```

## Sortie

- Markdown récapitulatif : `chappe-bench/target/shootout/shootout-results.md`
- Runs wrk2 bruts : `chappe-bench/target/shootout/<service>-rate<N>.txt`

Le récap contient : taille d'image (Mo), RSS idle, throughput soutenu max (à
p99 < 10 ms), latence p50 / p99 / p999.

## Debug

```bash
# Démarrer juste un service pour inspection :
docker compose -f chappe-bench/docker/shootout/docker-compose.yml \
    --profile servers up chappe-jvm

# Logs d'un container :
docker compose -f chappe-bench/docker/shootout/docker-compose.yml logs chappe-native

# Cleanup forcé :
docker compose -f chappe-bench/docker/shootout/docker-compose.yml down -v --remove-orphans
```

## Hypothèses & limites

- Le client `williamyeh/wrk2` est dans le même réseau bridge → latence intra-host
  Docker, équitable entre tous les serveurs.
- `wrk2 -R<rate>` envoie à débit constant ; si le serveur ne tient pas, la
  latence explose et le rate est rejeté (filtré p99 < 10 ms).
- Le profil `compatibility` du native-image est utilisé pour la portabilité de
  l'image (évite l'optimisation `-march=native` qui dépend de la VM hôte).
- Grizzly retiré du périmètre (échoue avec keep-alive, déjà noté dans
  `BENCHMARKS.md`).
