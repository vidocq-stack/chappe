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
   │                  │     wrk2     │  client cores                │
   │                  │  (client)    │                              │
   │                  └──────────────┘                               │
   │                                                                │
   │  server cores (disjoint from the client's)                     │
   └────────────────────────────────────────────────────────────────┘
```

## Reproducibility

```bash
# From the root of the chappe repo:
./chappe-bench/docker/run-remote.sh shootout            # everything (with native)
./chappe-bench/docker/run-remote.sh shootout-jvm-only   # without chappe-native

# CPU sets: by default derived from the host topology (CHAPPE-041), whole
# physical cores with their hyper-threads, a third for the client. Override both:
SERVER_CPUSET=0,6,1,7,2,8,3,9 CLIENT_CPUSET=4,10,5,11 \
    ./chappe-bench/docker/run-remote.sh shootout

# Override wrk2 rates:
SHOOTOUT_RATES="10000 50000" SHOOTOUT_DURATION=10s \
    ./chappe-bench/docker/run-remote.sh shootout
```

## Output

- Summary Markdown: `chappe-bench/target/shootout/shootout-results.md`
- Raw wrk2 runs: `chappe-bench/target/shootout/<service>-rate<N>.txt`
- Environment fingerprint: `chappe-bench/target/shootout/fingerprint.json` (machine, CPU
  governor and turbo, CPU sets, JVM, versions of every target, commit). `Vidocq/ci`
  `bench-report/import-wrk2-shootout.py` turns this directory into a `results.json`.

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
