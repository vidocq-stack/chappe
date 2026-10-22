#!/usr/bin/env bash
# Orchestre le shootout multi-runtime :
#   1. Build les images via docker compose (profils servers + native + client)
#   2. Démarre les serveurs OOP (chappe-jvm/native/jetty/helidon/jdk/netty/vertx/nginx/go)
#   3. Lance wrk (fork wrk2 de Gil Tene, -R rate constant) contre chaque service
#   4. Récolte taille image, RSS idle, throughput soutenu, latence HdrHistogram
#   5. Produit chappe-bench/target/shootout/shootout-results.md
#
# Compatible bash 3.2+. Le binaire client s'appelle `wrk` dans l'image cylab/wrk2
# (qui est bien wrk2 de Gil Tene, renommé à la compile).
#
# Modes :
#   - bridge (défaut)       : cpuset 0-3 / 4-7, network bridge, tous sur :8080 internes
#   - unleashed (UNLEASHED=1): pas de cpuset, network_mode: host, ports 18081-18089
#
# Variables :
#   CHAPPE_DOCKER_CONTEXT     contexte Docker (default macuntutailscale, "" = local)
#   SHOOTOUT_SKIP_NATIVE      = 1 → saute chappe-native (build natif lent/cassé)
#   SHOOTOUT_UNLEASHED        = 1 → mode unleashed (host network, pas de cpuset)
#   SHOOTOUT_RATES            rates wrk testés (default selon mode)
#   SHOOTOUT_DURATION         durée par rate (default 30s)
#   SHOOTOUT_WARMUP           durée warmup (default 5s)
#   SHOOTOUT_CONNECTIONS      connections wrk (default 100)
#   SHOOTOUT_THREADS          threads wrk (default 4)
#   SHOOTOUT_KEEP_UP          = 1 → ne fait pas `compose down` à la fin (debug)

set -euo pipefail

CONTEXT="${CHAPPE_DOCKER_CONTEXT:-macuntutailscale}"
SKIP_NATIVE="${SHOOTOUT_SKIP_NATIVE:-0}"
UNLEASHED="${SHOOTOUT_UNLEASHED:-0}"
DURATION="${SHOOTOUT_DURATION:-30s}"
WARMUP="${SHOOTOUT_WARMUP:-10s}"
# Warmup PROGRESSIF : toujours à un rate "raisonnable" (default 100k) pour laisser
# le JIT et le scheduler Loom s'aligner avant la mesure à plein régime. Cf. note
# BENCHMARKS.md §"warmup progressif vs cold-start".
WARMUP_RATE="${SHOOTOUT_WARMUP_RATE:-100000}"
CONNECTIONS="${SHOOTOUT_CONNECTIONS:-100}"
THREADS="${SHOOTOUT_THREADS:-4}"
KEEP_UP="${SHOOTOUT_KEEP_UP:-0}"

# Rates par défaut élargis en mode unleashed (machine entière)
if [ "$UNLEASHED" = "1" ]; then
    RATES="${SHOOTOUT_RATES:-100000 200000 300000 500000}"
else
    RATES="${SHOOTOUT_RATES:-50000 100000 200000}"
fi

# Chemins ─ ce script vit dans chappe-bench/docker/shootout/client/
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SHOOTOUT_DIR="$(dirname "$SCRIPT_DIR")"
BENCH_DIR="$(cd "$SHOOTOUT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$BENCH_DIR/.." && pwd)"
RESULTS_DIR="$BENCH_DIR/target/shootout"
RESULTS_FILE="$RESULTS_DIR/shootout-results.md"
SUMMARY_TSV="$RESULTS_DIR/_summary.tsv"

mkdir -p "$RESULTS_DIR"
# Raw runs of an earlier invocation (other rates, other services) would be read as
# part of this one.
rm -f "$RESULTS_DIR"/*-rate*.txt "$RESULTS_DIR"/fingerprint.json
: > "$SUMMARY_TSV"

# ─── Compose file choisi selon le mode ────────────────────────────────────────
if [ "$UNLEASHED" = "1" ]; then
    COMPOSE_FILE="$SHOOTOUT_DIR/docker-compose.unleashed.yml"
    CONTAINER_PREFIX="chappe-shootout-u-"
    MODE_LABEL="UNLEASHED (host network, no CPU pinning)"
else
    COMPOSE_FILE="$SHOOTOUT_DIR/docker-compose.yml"
    CONTAINER_PREFIX="chappe-shootout-"
    MODE_LABEL="BRIDGE (servers and client pinned to disjoint physical cores)"
fi

# ─── DOCKER wrappers ──────────────────────────────────────────────────────────
DC_FLAGS=()
if [ -n "$CONTEXT" ]; then
    DC_FLAGS=(--context "$CONTEXT")
fi

dk()       { docker "${DC_FLAGS[@]}" "$@"; }
compose()  { docker "${DC_FLAGS[@]}" compose -f "$COMPOSE_FILE" "$@"; }

# ─── Default CPU sets from the host topology (CHAPPE-041) ─────────────────────
# One line per physical core, listing its hardware threads ("0,6" or "0-1"). The
# client gets about a third of the cores, the servers the rest; a core and its
# siblings always go to the same side, so the load generator never shares a
# physical core with the server it measures.
derive_cpusets() {
    local cores count client_count
    cores=$(dk run --rm alpine sh -c \
        'cat /sys/devices/system/cpu/cpu[0-9]*/topology/thread_siblings_list' \
        | sort -t, -k1,1n -u)
    count=$(echo "$cores" | grep -c .)
    if [ "$count" -lt 2 ]; then
        echo "  [error] $count physical core(s): set SERVER_CPUSET and CLIENT_CPUSET" >&2
        exit 1
    fi
    client_count=$(( (count + 2) / 3 ))
    SERVER_CPUSET=$(echo "$cores" | head -n $((count - client_count)) | paste -sd, -)
    CLIENT_CPUSET=$(echo "$cores" | tail -n "$client_count" | paste -sd, -)
}

if [ "$UNLEASHED" != "1" ]; then
    if [ -z "${SERVER_CPUSET:-}" ] || [ -z "${CLIENT_CPUSET:-}" ]; then
        derive_cpusets
    fi
    export SERVER_CPUSET CLIENT_CPUSET
fi

echo "▶ Mode            : $MODE_LABEL"
echo "▶ Docker context  : ${CONTEXT:-local}"
echo "▶ Compose file    : $(basename "$COMPOSE_FILE")"
if [ "$UNLEASHED" != "1" ]; then
    echo "▶ CPU sets        : servers $SERVER_CPUSET / client $CLIENT_CPUSET"
fi

# Cleanup automatique en cas d'erreur ou de fin normale (sauf KEEP_UP=1)
COMPOSE_PROFILES="servers,native,client"
[ "$SKIP_NATIVE" = "1" ] && COMPOSE_PROFILES="servers,client"

cleanup() {
    local exit_code=$?
    if [ "$KEEP_UP" != "1" ]; then
        echo
        echo "▶ Cleanup containers (down -v --remove-orphans)"
        COMPOSE_PROFILES="$COMPOSE_PROFILES" compose down -v --remove-orphans 2>/dev/null || true
    fi
    exit $exit_code
}
trap cleanup EXIT INT TERM

# Liste services activés
JVM_SERVICES="chappe-jvm jetty helidon jdk netty vertx"
if [ "$SKIP_NATIVE" = "1" ]; then
    NATIVE_SERVICES="nginx go"
else
    NATIVE_SERVICES="chappe-native nginx go"
fi
ALL_SERVICES="$JVM_SERVICES $NATIVE_SERVICES"

# ─── Mapping service → URL & container name ───────────────────────────────────
port_for() {
    # Mode unleashed : chaque service écoute sur un port unique sur l'host
    case "$1" in
        chappe-jvm)    echo 18081 ;;
        jetty)         echo 18082 ;;
        helidon)       echo 18083 ;;
        jdk)           echo 18084 ;;
        netty)         echo 18085 ;;
        vertx)         echo 18086 ;;
        chappe-native) echo 18087 ;;
        nginx)         echo 18088 ;;
        go)            echo 18089 ;;
    esac
}

url_for() {
    local svc="$1"
    if [ "$UNLEASHED" = "1" ]; then
        echo "http://localhost:$(port_for "$svc")/"
    else
        echo "http://${svc}:8080/"
    fi
}

container_for() {
    echo "${CONTAINER_PREFIX}$1"
}

image_for() {
    case "$1" in
        chappe-jvm|jetty|helidon|jdk|netty|vertx) echo "chappe-shootout-jvm:local" ;;
        chappe-native)                            echo "chappe-shootout-native:local" ;;
        nginx)                                    echo "chappe-shootout-nginx:local" ;;
        go)                                       echo "chappe-shootout-go:local" ;;
    esac
}

# ─── Phase 1 : build (mode bridge uniquement) + start ─────────────────────────
if [ "$UNLEASHED" = "1" ]; then
    echo
    echo "▶ Mode unleashed : on suppose les images déjà buildées via le compose bridge."
    echo "  (lancer d'abord ./run-shootout.sh sans UNLEASHED pour les construire)"
else
    echo
    echo "▶ Build des images shootout (profils $COMPOSE_PROFILES)"
    COMPOSE_PROFILES="$COMPOSE_PROFILES" compose build
fi

echo
echo "▶ Démarrage des serveurs : $ALL_SERVICES"
# shellcheck disable=SC2086
COMPOSE_PROFILES="$COMPOSE_PROFILES" compose up -d $ALL_SERVICES wrk2

# ─── Environment fingerprint ──────────────────────────────────────────────────
# Written next to the raw wrk2 runs, so that a result never travels without the
# machine, the settings and the versions that produced it.
pom_version() {
    awk -v a="<artifactId>$1</artifactId>" \
        'index($0, a) { found = 1; next }
         found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' \
        "$BENCH_DIR/pom.xml"
}

dockerfile_arg() {
    sed -n "s/^ARG $2=//p" "$SHOOTOUT_DIR/$1" | head -1
}

write_fingerprint() {
    local host jvm commit dirty cpusets
    host=$(dk run --rm alpine sh -c '
        f() { cat "$1" 2>/dev/null || echo unknown; }
        echo "cpu_model=$(grep -m1 "model name" /proc/cpuinfo | cut -d: -f2- | sed "s/^ *//")"
        echo "logical_cpus=$(grep -c ^processor /proc/cpuinfo)"
        echo "kernel=$(uname -r)"
        echo "governor=$(f /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
        echo "no_turbo=$(f /sys/devices/system/cpu/intel_pstate/no_turbo)"
        echo "memory_kb=$(awk "/MemTotal/ {print \$2}" /proc/meminfo)"')
    get() { echo "$host" | sed -n "s/^$1=//p"; }
    jvm=$(dk run --rm --entrypoint java chappe-shootout-jvm:local -version 2>&1 | sed -n '2p')
    commit=$(git -C "$REPO_ROOT" rev-parse HEAD)
    dirty=false
    [ -n "$(git -C "$REPO_ROOT" status --porcelain)" ] && dirty=true
    if [ "$UNLEASHED" = "1" ]; then
        cpusets='null'
    else
        cpusets="{ \"server\": \"$SERVER_CPUSET\", \"client\": \"$CLIENT_CPUSET\" }"
    fi
    cat > "$RESULTS_DIR/fingerprint.json" <<EOF
{
  "schema": 1,
  "harness": "chappe-shootout",
  "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "repository": "chappe",
  "commit": "$commit",
  "dirty": $dirty,
  "mode": "$([ "$UNLEASHED" = "1" ] && echo unleashed || echo bridge)",
  "docker_context": "${CONTEXT:-local}",
  "params": {
    "rates": [$(echo $RATES | sed 's/ /, /g')],
    "duration": "$DURATION",
    "warmup": "$WARMUP",
    "warmup_rate": $WARMUP_RATE,
    "connections": $CONNECTIONS,
    "threads": $THREADS,
    "slo_p99_ms": 10
  },
  "cpusets": $cpusets,
  "host": {
    "cpu_model": "$(get cpu_model)",
    "logical_cpus": $(get logical_cpus),
    "kernel": "$(get kernel)",
    "governor": "$(get governor)",
    "no_turbo": "$(get no_turbo)",
    "memory_kb": $(get memory_kb),
    "docker": "$(dk version --format '{{.Server.Version}}')"
  },
  "jvm": "$jvm",
  "targets": {
    "chappe-jvm": "$commit",
    "chappe-native": "GraalVM CE $(dockerfile_arg Dockerfile.chappe-native GRAALVM_TAG)",
    "helidon": "Helidon SE $(pom_version helidon-webserver)",
    "jetty": "Jetty $(pom_version jetty-server)",
    "netty": "Netty $(pom_version netty-codec-http)",
    "vertx": "Vert.x $(pom_version vertx-core)",
    "jdk": "com.sun.net.httpserver",
    "nginx": "nginx $(sed -n 's/^FROM nginx://p' "$SHOOTOUT_DIR/Dockerfile.nginx")",
    "go": "Go net/http $(dockerfile_arg Dockerfile.go GO_TAG)"
  },
  "client": "$(dk image inspect cylab/wrk2:latest --format "{{index .RepoDigests 0}}" 2>/dev/null || echo cylab/wrk2:latest)"
}
EOF
    echo "▶ Fingerprint     : $RESULTS_DIR/fingerprint.json"
}

write_fingerprint

# ─── Phase 2 : attendre que chaque serveur réponde 200 ───────────────────────
wait_ready() {
    local service="$1"
    local max_wait="${2:-120}"
    local url
    url="$(url_for "$service")"
    local elapsed=0
    while [ "$elapsed" -lt "$max_wait" ]; do
        if compose exec -T wrk2 wrk -d1s -t1 -c1 -R10 "$url" 2>/dev/null \
           | grep -q "Requests/sec"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "  [error] $service did not become ready within ${max_wait}s ($url)" >&2
    return 1
}

# Convertit "1.23ms" / "456.78us" / "1.23s" en millisecondes flottantes
ms_of() {
    local v="$1"
    case "$v" in
        *us) awk -v x="${v%us}" 'BEGIN{printf "%.4f", x/1000.0}' ;;
        *ms) awk -v x="${v%ms}" 'BEGIN{printf "%.4f", x}' ;;
        *s)  awk -v x="${v%s}"  'BEGIN{printf "%.4f", x*1000.0}' ;;
        *)   echo "$v" ;;
    esac
}

measure_one() {
    local service="$1"
    local image url container
    image="$(image_for "$service")"
    url="$(url_for "$service")"
    container="$(container_for "$service")"

    echo
    echo "═════════════════════════════════════════════════════"
    echo "▶ $service ($image) → $url"
    echo "═════════════════════════════════════════════════════"

    local size_bytes img_mb rss="?"
    size_bytes=$(dk image inspect "$image" --format='{{.Size}}' 2>/dev/null || echo 0)
    img_mb=$(awk -v s="$size_bytes" 'BEGIN{printf "%.1f", s/1024/1024}')

    # 2 seuils : p99 strict (< 10ms) et tolérant (< 20ms)
    local best10_rate=0  best10_p50="-" best10_p99="-" best10_p999="-" best10_actual="-"
    local best20_rate=0  best20_p50="-" best20_p99="-" best20_p999="-" best20_actual="-"

    for rate in $RATES; do
        # Container FRESH par rate (élimine le couplage entre rates)
        compose restart "$service" >/dev/null 2>&1 || compose up -d "$service" >/dev/null 2>&1

        if ! wait_ready "$service" 120; then
            echo "  [warn] $service not ready at rate $rate, skip"
            continue
        fi

        # RSS au démarrage (mesuré une seule fois, sur le 1er rate)
        if [ "$rss" = "?" ]; then
            local stats_out
            stats_out=$(dk stats --no-stream --format='{{.MemUsage}}' "$container" 2>/dev/null || echo "?")
            rss=$(echo "$stats_out" | awk '{print $1}')
        fi

        # Warmup progressif : capé à WARMUP_RATE (100k par défaut) pour laisser
        # le JIT et le scheduler Loom s'aligner avant la mesure à plein régime.
        local w_rate="$WARMUP_RATE"
        [ "$rate" -lt "$w_rate" ] && w_rate="$rate"
        echo "  warmup $WARMUP @ rate $w_rate (progressif → cible $rate)"
        compose exec -T wrk2 wrk -d"$WARMUP" -t"$THREADS" -c"$CONNECTIONS" -R"$w_rate" \
            "$url" >/dev/null 2>&1 || true

        echo "  measure $DURATION @ rate $rate"
        local out
        if ! out=$(compose exec -T wrk2 wrk -d"$DURATION" -t"$THREADS" \
                   -c"$CONNECTIONS" -R"$rate" --latency \
                   "$url" 2>&1); then
            echo "    [warn] wrk failed at rate $rate"
            continue
        fi

        local req_per_sec p50 p99 p999
        req_per_sec=$(echo "$out" | awk '/Requests\/sec:/ {print $2}')
        p50=$(echo "$out"  | awk '/^ *50\.000%/  {print $2; exit}')
        p99=$(echo "$out"  | awk '/^ *99\.000%/  {print $2; exit}')
        p999=$(echo "$out" | awk '/^ *99\.900%/  {print $2; exit}')

        echo "    → req/s ${req_per_sec:-?}   p50 ${p50:-?}   p99 ${p99:-?}   p999 ${p999:-?}"
        echo "$out" > "$RESULTS_DIR/${service}-rate${rate}.txt"

        if [ -n "$p99" ]; then
            local p99_ms
            p99_ms=$(ms_of "$p99")
            # Seuil strict 10ms
            if awk -v p="$p99_ms" 'BEGIN{exit !(p<10)}' && [ "$rate" -gt "$best10_rate" ]; then
                best10_rate="$rate"; best10_p50="$p50"; best10_p99="$p99"
                best10_p999="$p999"; best10_actual="$req_per_sec"
            fi
            # Seuil tolérant 20ms
            if awk -v p="$p99_ms" 'BEGIN{exit !(p<20)}' && [ "$rate" -gt "$best20_rate" ]; then
                best20_rate="$rate"; best20_p50="$p50"; best20_p99="$p99"
                best20_p999="$p999"; best20_actual="$req_per_sec"
            fi
        fi
    done

    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
        "$service" "$img_mb" "$rss" \
        "$best10_rate" "$best10_actual" "$best10_p50" "$best10_p99" "$best10_p999" \
        "$best20_rate" "$best20_actual" "$best20_p50" "$best20_p99" "$best20_p999" \
        >> "$SUMMARY_TSV"
}

for svc in $ALL_SERVICES; do
    measure_one "$svc"
done

# ─── Phase 4 : génération du markdown ─────────────────────────────────────────
{
    echo "# Shootout multi-runtime — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "- Mode            : $MODE_LABEL"
    echo "- Contexte Docker : \`${CONTEXT:-local}\`"
    echo "- wrk (fork wrk2 Gil Tene) : $THREADS threads / $CONNECTIONS connections / $DURATION (warmup $WARMUP)"
    echo "- Rates testés    : $RATES req/s (sustained, p99 < 10ms requis)"
    echo
    echo "Container neuf restart entre chaque rate (élimine couplage inter-rates)."
    echo
    echo "### Sustained @ p99 < 10 ms (SLA strict)"
    echo
    echo "| Service          | Image (Mo) | RSS idle  | Max sustained | p50      | p99      | p999     |"
    echo "|------------------|-----------:|----------:|--------------:|---------:|---------:|---------:|"
    while IFS=$'\t' read -r service img rss br10 actual10 p50_10 p99_10 p999_10 br20 actual20 p50_20 p99_20 p999_20; do
        printf "| %-16s | %10s | %9s | %13s | %8s | %8s | %8s |\n" \
            "$service" "${img:-?}" "${rss:-?}" "${br10:-?}" "${p50_10:-?}" "${p99_10:-?}" "${p999_10:-?}"
    done < "$SUMMARY_TSV"
    echo
    echo "### Sustained @ p99 < 20 ms (SLA tolérant)"
    echo
    echo "| Service          | Max sustained | p50      | p99      | p999     |"
    echo "|------------------|--------------:|---------:|---------:|---------:|"
    while IFS=$'\t' read -r service img rss br10 actual10 p50_10 p99_10 p999_10 br20 actual20 p50_20 p99_20 p999_20; do
        printf "| %-16s | %13s | %8s | %8s | %8s |\n" \
            "$service" "${br20:-?}" "${p50_20:-?}" "${p99_20:-?}" "${p999_20:-?}"
    done < "$SUMMARY_TSV"
    echo
    echo "Runs bruts wrk : \`chappe-bench/target/shootout/<service>-rate<N>.txt\`"
} > "$RESULTS_FILE"

echo
echo "═════════════════════════════════════════════════════"
echo "▶ Résultats : $RESULTS_FILE"
echo "═════════════════════════════════════════════════════"
cat "$RESULTS_FILE"
