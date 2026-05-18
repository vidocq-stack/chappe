#!/usr/bin/env bash
# Build l'image de bench puis lance les benchmarks sur un Docker distant
# (défaut : contexte "macuntutailscale") pour s'isoler du bruit de la machine dev.
#
# Cibles :
#   ./run-remote.sh                    # micro-benchs JMH (OptimizationsRunner)
#   ./run-remote.sh jmh                # idem
#   ./run-remote.sh compare            # ServerComparison in-process (6 serveurs JVM)
#   ./run-remote.sh custom FQN         # n'importe quelle main class
#   ./run-remote.sh shootout           # shootout OOP multi-runtime via docker compose (wrk2)
#   ./run-remote.sh shootout-jvm-only  # idem mais sans chappe-native (build natif sauté)

set -euo pipefail

CONTEXT="${CHAPPE_DOCKER_CONTEXT:-macuntutailscale}"
IMAGE="${CHAPPE_BENCH_IMAGE:-chappe-bench:local}"
TARGET="${1:-jmh}"

# Racine du repo (parent du dossier chappe-bench/docker)
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "▶ Docker context : $CONTEXT"
docker --context "$CONTEXT" version --format '  engine {{.Server.Version}} / {{.Server.Os}}/{{.Server.Arch}}'

# ─── Cibles "shootout" : déléguent au harness compose ─────────────────────────
case "$TARGET" in
    shootout)
        exec env CHAPPE_DOCKER_CONTEXT="$CONTEXT" \
            "$REPO_ROOT/chappe-bench/docker/shootout/client/run-shootout.sh"
        ;;
    shootout-jvm-only)
        exec env CHAPPE_DOCKER_CONTEXT="$CONTEXT" SHOOTOUT_SKIP_NATIVE=1 \
            "$REPO_ROOT/chappe-bench/docker/shootout/client/run-shootout.sh"
        ;;
    shootout-unleashed)
        # Suppose que les images ont déjà été buildées par un `shootout` préalable.
        # Mode : pas de cpuset, network host, ports 18081-18089, rates jusqu'à 500k.
        exec env CHAPPE_DOCKER_CONTEXT="$CONTEXT" SHOOTOUT_UNLEASHED=1 \
            "$REPO_ROOT/chappe-bench/docker/shootout/client/run-shootout.sh"
        ;;
esac

# ─── Cibles in-process historiques : image single-shot ────────────────────────
echo "▶ Build de l'image $IMAGE sur le contexte distant"
docker --context "$CONTEXT" build \
    --file "$REPO_ROOT/chappe-bench/docker/Dockerfile" \
    --tag "$IMAGE" \
    "$REPO_ROOT"

case "$TARGET" in
    jmh)
        MAIN_CLASS="io.vidocq.chappe.bench.OptimizationsRunner"
        ;;
    compare)
        MAIN_CLASS="io.vidocq.chappe.bench.ServerComparison"
        ;;
    custom)
        MAIN_CLASS="${2:?Usage: run-remote.sh custom <fully.qualified.MainClass>}"
        ;;
    *)
        echo "Unknown target: $TARGET" >&2
        exit 1
        ;;
esac

echo
echo "▶ Lancement : $MAIN_CLASS"
echo "────────────────────────────────────────────────────────────────"

docker --context "$CONTEXT" run --rm \
    --name "chappe-bench-$$" \
    "$IMAGE" "$MAIN_CLASS"
