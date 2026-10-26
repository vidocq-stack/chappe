# BENCH.md — chappe

Performance measurements history. Convention: see `vidocq-workspace/AGENTS.md` (workspace root).

Every published figure (README, commit, post) must point to an entry here. Older shootout and in-process results are in `BENCHMARKS.md`.

---

## BENCH-20261026-01 — Shootout noise band, ten identical runs of 0.4.0-RC1

- **Date**: 2026-10-26 (12:21 to 15:52 UTC)
- **Commit**: 2116835 (main, after 0.4.0-RC1)
- **JVM**: Temurin 25.0.4.1+1-LTS (chappe-native: GraalVM CE 25-ol9)
- **Hardware**: Intel Core i7-8700B (6 cores, 12 threads) / 32 GB RAM, host `macuntu`, Docker 29.9.2 over the `macuntutailscale` context
- **OS**: Linux 5.15.0-191-generic
- **Host state**: bench window open (Vidocq/ci `bench-window`): CPU governor `performance`, turbo off (`no_turbo=1`), the CI runners and their Docker-in-Docker containers stopped. No measurement was flagged `disturbed` (foreign CPU stayed under 25 % of one CPU).
- **CPU sets**: servers `0,6,1,7,2,8,3,9`, wrk2 client `4,10,5,11` (disjoint physical cores, CHAPPE-041)
- **Exact command** (repeated ten times, results of each run copied apart):
  ```bash
  ./chappe-bench/docker/run-remote.sh shootout
  ```
  wrk2 `cylab/wrk2@sha256:9c3fe08c62b8890de2be07014a96d2128f88fee210f02cc9411bef60e125662f`, 4 threads, 100 connections, 30 s per rate after 10 s of warmup, rates 50000 100000 200000, fresh container per rate. Targets: Jetty 12.0.21, Helidon SE 4.2.2, Netty 4.2.6.Final, Vert.x 4.5.16, JDK `com.sun.net.httpserver`, nginx 1.27-alpine, Go net/http 1.24, each answering `GET /` with 2 bytes.
- **Purpose**: nothing changes between the ten runs, so every spread is measurement noise. It sets the threshold of a regression alert (workspace ADR 0001, P0).

### p99 latency at 50 000 req/s (ms)

| Server | median | min | max | spread | runs < 10 ms | the ten p99 values, run 1 to 10 |
|---|--:|--:|--:|--:|--:|---|
| jetty | 2.08 | 2.03 | 2.09 | 3 % | 10/10 | 2.03 2.06 2.08 2.09 2.09 2.07 2.07 2.09 2.05 2.08 |
| netty | 2.08 | 2.04 | 2.11 | 3 % | 10/10 | 2.09 2.04 2.10 2.10 2.04 2.11 2.08 2.04 2.04 2.09 |
| jdk | 2.09 | 1.99 | 2.14 | 7 % | 10/10 | 2.09 2.09 2.07 1.99 2.12 2.10 2.08 2.10 2.14 2.07 |
| nginx | 2.10 | 2.06 | 2.12 | 3 % | 10/10 | 2.06 2.12 2.09 2.08 2.08 2.12 2.10 2.10 2.10 2.11 |
| helidon | 2.16 | 2.12 | 2.21 | 4 % | 10/10 | 2.17 2.21 2.15 2.19 2.16 2.16 2.16 2.17 2.12 2.12 |
| chappe-jvm | 2.24 | 2.15 | 2.29 | 6 % | 10/10 | 2.25 2.25 2.27 2.19 2.19 2.29 2.23 2.23 2.24 2.15 |
| go | 2.25 | 2.22 | 2.30 | 4 % | 10/10 | 2.24 2.26 2.24 2.30 2.27 2.23 2.27 2.28 2.25 2.22 |
| chappe-native | 3.19 | 3.10 | 3.37 | 8 % | 10/10 | 3.25 3.29 3.12 3.17 3.37 3.18 3.14 3.20 3.22 3.10 |
| vertx | 4.57 | 3.23 | 6.58 | 73 % | 10/10 | 3.87 4.11 6.58 4.69 5.06 3.23 4.44 4.82 5.03 3.38 |

### p99 latency at 100 000 req/s (ms)

| Server | median | min | max | spread | runs < 10 ms | the ten p99 values, run 1 to 10 |
|---|--:|--:|--:|--:|--:|---|
| nginx | 2.48 | 2.38 | 2.62 | 10 % | 10/10 | 2.42 2.45 2.56 2.51 2.43 2.61 2.40 2.38 2.62 2.57 |
| netty | 2.63 | 2.49 | 2.74 | 10 % | 10/10 | 2.70 2.74 2.49 2.65 2.72 2.61 2.57 2.74 2.57 2.50 |
| jetty | 2.65 | 2.55 | 2.80 | 9 % | 10/10 | 2.76 2.64 2.80 2.65 2.55 2.65 2.56 2.56 2.67 2.61 |
| jdk | 3.04 | 2.74 | 5.76 | 100 % | 10/10 | 2.91 4.55 3.01 3.04 5.76 2.89 3.03 3.62 3.18 2.74 |
| helidon | 3.33 | 2.89 | 4.79 | 57 % | 10/10 | 3.24 3.32 3.66 2.89 3.43 3.65 3.35 3.26 4.79 3.04 |
| go | 4.06 | 3.28 | 9.85 | 162 % | 10/10 | 4.17 9.85 3.96 6.78 4.91 3.35 3.28 3.79 5.19 3.52 |
| chappe-jvm | 5.39 | 4.15 | 8.56 | 82 % | 10/10 | 8.56 5.45 5.33 7.78 5.14 4.85 7.79 4.73 4.15 6.46 |
| chappe-native | 7.60 | 6.45 | 14.43 | 105 % | 6/10 | 14.43 6.84 12.63 6.68 6.45 11.12 6.52 8.30 6.89 12.68 |
| vertx | 9720 | 9150 | 11210 | 21 % | 0/10 | 9150 11210 10530 10400 9270 9890 9310 9340 9550 10160 |

Vert.x does not sustain 100 000 req/s: its p99 is about 9.7 s and it reaches about 67 700 req/s.

### Throughput reached when 200 000 req/s are asked

None of the servers sustains this rate (p99 above 4 s everywhere).

| Server | achieved req/s, median | min | max |
|---|--:|--:|--:|
| jetty | 171559 | 167509 | 174450 |
| nginx | 167498 | 165347 | 169824 |
| netty | 163586 | 159545 | 166286 |
| helidon | 162524 | 156673 | 165435 |
| jdk | 161260 | 155944 | 164757 |
| chappe-jvm | 156442 | 149379 | 163579 |
| go | 145841 | 141949 | 148137 |
| chappe-native | 139856 | 134364 | 143335 |
| vertx | 67222 | 64415 | 71168 |

- **Comparison vs previous run**: first run of this protocol. The 2026-10-26 run 1 is also the first with CPU sets derived from the host topology and the interference watch.
- **Notes**:
  - At 50 000 req/s the p99 spread is 3 to 8 % for every server except Vert.x (73 %). A regression alert on this figure can use a threshold of about 15 %.
  - At 100 000 req/s, close to the saturation knee, the spread reaches 160 % (Go, 3.3 to 9.9 ms). A single run proves nothing at this rate: use the median of at least five runs.
  - chappe-native holds p99 under 10 ms in 6 runs out of 10 at 100 000 req/s, so its "max sustained at strict SLA" flips between 50 000 and 100 000 from one run to the next.
  - At 100 000 req/s the median p99 of chappe-jvm (5.39 ms) and chappe-native (7.60 ms) is above Jetty, Netty and nginx (2.48 to 2.65 ms). The gap is larger than the noise.
  - The rate grid (50, 100, 200 thousand) is too coarse to find the maximum sustained rate: the knee is between 100 000 and 200 000 req/s. The next run needs steps of 20 000 in that range.
  - This is a 2-byte `GET /` over keep-alive connections on a loopback bridge. It says nothing about real handlers, TLS, request bodies, HTTP/2, slow clients or long runs.
