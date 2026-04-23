#!/usr/bin/env bash
# Build l'image de bench puis lance les benchmarks sur un Docker distant
# (défaut : contexte "macuntutailscale") pour s'isoler du bruit de la machine dev.
#
# Usage :
#   ./run-remote.sh                    # micro-benchs JMH (OptimizationsRunner)
#   ./run-remote.sh compare            # ServerComparison (Chappe vs Jetty/Helidon/Grizzly)
#   ./run-remote.sh custom FQN         # n'importe quelle main class

set -euo pipefail

CONTEXT="${CHAPPE_DOCKER_CONTEXT:-macuntutailscale}"
IMAGE="${CHAPPE_BENCH_IMAGE:-chappe-bench:local}"
TARGET="${1:-jmh}"

# Racine du repo (parent du dossier chappe-bench/docker)
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "▶ Docker context : $CONTEXT"
docker --context "$CONTEXT" version --format '  engine {{.Server.Version}} / {{.Server.Os}}/{{.Server.Arch}}'

echo "▶ Build de l'image $IMAGE sur le contexte distant"
docker --context "$CONTEXT" build \
    --file "$REPO_ROOT/chappe-bench/docker/Dockerfile" \
    --tag "$IMAGE" \
    "$REPO_ROOT"

case "$TARGET" in
    jmh)
        MAIN_CLASS="fr.vidocq.chappe.bench.OptimizationsRunner"
        ;;
    compare)
        MAIN_CLASS="fr.vidocq.chappe.bench.ServerComparison"
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
