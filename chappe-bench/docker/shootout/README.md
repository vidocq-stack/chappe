# Chappe Shootout — Multi-runtime OOP Harness

Compare Chappe (JVM + native) against the market's reference HTTP servers in
**out-of-process** mode: one container per server, measurements via `wrk2`
(HdrHistogram, constant rate). This complements the in-process
`ServerComparison` — OOP is the only way to compare fairly against Nginx, Go,
and the native Chappe binary.

## Targets

| Service       | Lib / Runtime           | Image                                            |
|---------------|-------------------------|--------------------------------------------------|
| chappe-jvm    | Chappe on Temurin 25    | `chappe-shootout-jvm:local`                      |
| chappe-native | Chappe via GraalVM CE 25| `chappe-shootout-native:local` (distroless base) |
| jetty         | Jetty 12.0.21           | `chappe-shootout-jvm:local`                      |
| helidon       | Helidon SE 4.2.2        | `chappe-shootout-jvm:local`                      |
| jdk           | `com.sun.net.httpserver`| `chappe-shootout-jvm:local`                      |
| netty         | Netty 4.2.6.Final       | `chappe-shootout-jvm:local`                      |
| vertx         | Vert.x 4.5.16           | `chappe-shootout-jvm:local`                      |
| nginx         | Nginx 1.27-alpine       | `chappe-shootout-nginx:local`                    |
| go            | Go 1.24 `net/http`      | `chappe-shootout-go:local` (distroless static)   |

All expose `GET / → "ok"` (2 bytes, `text/plain`) on internal port 8080.

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

## Reproducibility

```bash
# From the root of the chappe repo:
./chappe-bench/docker/run-remote.sh shootout            # everything (with native)
./chappe-bench/docker/run-remote.sh shootout-jvm-only   # without chappe-native

# Override CPU sets if fewer than 8 cores:
SERVER_CPUSET=0-1 CLIENT_CPUSET=2-3 \
    ./chappe-bench/docker/run-remote.sh shootout

# Override wrk2 rates:
SHOOTOUT_RATES="10000 50000" SHOOTOUT_DURATION=10s \
    ./chappe-bench/docker/run-remote.sh shootout
```

## Output

- Summary Markdown: `chappe-bench/target/shootout/shootout-results.md`
- Raw wrk2 runs: `chappe-bench/target/shootout/<service>-rate<N>.txt`

The summary contains: image size (MB), idle RSS, max sustained throughput (at
p99 < 10 ms), and p50 / p99 / p999 latency.

## Debug

```bash
# Start only one service for inspection:
docker compose -f chappe-bench/docker/shootout/docker-compose.yml \
    --profile servers up chappe-jvm

# Logs from a container:
docker compose -f chappe-bench/docker/shootout/docker-compose.yml logs chappe-native

# Forced cleanup:
docker compose -f chappe-bench/docker/shootout/docker-compose.yml down -v --remove-orphans
```

## Assumptions & limits

- The `williamyeh/wrk2` client is on the same bridge network → intra-host Docker
  latency, fair across all servers.
- `wrk2 -R<rate>` sends at a constant rate; if the server cannot keep up,
  latency explodes and the rate is rejected (filtered with p99 < 10 ms).
- The native-image `compatibility` profile is used for image portability
  (avoids the `-march=native` optimization that depends on the host VM).
- Grizzly was removed from the scope (fails with keep-alive, already noted in
  `BENCHMARKS.md`).
