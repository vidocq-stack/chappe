#!/usr/bin/env bash
# Génère un rapport Error Prone dans target/ à la racine du projet.
#
# Produit :
#   .errorprone/errorprone-full.log        — sortie Maven complète
#   .errorprone/errorprone-findings.txt    — findings bruts (fichier:ligne + [Check] + message)
#   .errorprone/errorprone-report.md       — synthèse Markdown (par check, par module)
#
# Usage : ./scripts/errorprone-report.sh [args additionnels mvn]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${ROOT}/.errorprone"
mkdir -p "${OUT}"

FULL="${OUT}/errorprone-full.log"
FINDINGS="${OUT}/errorprone-findings.txt"
REPORT="${OUT}/errorprone-report.md"

echo "▸ Lancement build avec Error Prone (logs → ${FULL})"
cd "${ROOT}"

# -fae : continue après une erreur de module pour avoir le rapport complet
# clean : indispensable, Error Prone ne re-tourne pas sur du déjà compilé
set +e
mvn -ntp -Perrorprone -DskipTests -fae clean compile "$@" > "${FULL}" 2>&1
MVN_EXIT=$?
set -e

echo "▸ Extraction des findings"
# Format Error Prone via Maven :
#   [WARNING] [CheckName] message court
#       (see https://errorprone.info/bugpattern/CheckName)
#       at relative/path/File.java[LINE,COL]
# State machine : on capture le check name puis sa première ligne "at ...[L,C]".
awk '
    function emit() {
        if (pending) {
            printf "%s\t%s\t%s\t%s\n", sev, check, loc, msg
            pending = 0; sev = ""; check = ""; loc = "?"; msg = ""
        }
    }
    /^\[(WARNING|ERROR)\] \[[A-Z][a-zA-Z]+\]/ {
        emit()
        sev = ($1 == "[WARNING]") ? "WARN" : "ERROR"
        rest = $0
        sub(/^\[(WARNING|ERROR)\] /, "", rest)
        match(rest, /^\[[A-Z][a-zA-Z]+\]/)
        check = substr(rest, 2, RLENGTH-2)
        msg = substr(rest, RLENGTH+2)
        loc = "?"; pending = 1
        next
    }
    pending && /at [^ ]+\.java\[[0-9]+,[0-9]+\]/ {
        match($0, /at [^ ]+\.java\[[0-9]+,[0-9]+\]/)
        loc = substr($0, RSTART+3, RLENGTH-3)
        emit()
        next
    }
    END { emit() }
' "${FULL}" > "${FINDINGS}" || true

TOTAL=$(wc -l < "${FINDINGS}" | tr -d ' ')

echo "▸ Génération du rapport Markdown"
{
    echo "# Error Prone Report"
    echo
    echo "_Généré le $(date '+%Y-%m-%d %H:%M:%S')_"
    echo
    echo "**Build status** : exit ${MVN_EXIT}"
    echo "**Total findings** : ${TOTAL}"
    echo
    echo "## Findings par check"
    echo
    echo "| Check | Count |"
    echo "|---|---:|"
    awk -F'\t' '{print $2}' "${FINDINGS}" \
        | sort | uniq -c | sort -rn \
        | awk '{check=$2; count=$1; print "| "check" | "count" |"}' || true
    echo
    echo "## Findings par module"
    echo
    echo "| Module | Count |"
    echo "|---|---:|"
    awk -F'\t' '{sub(/\/.*/, "", $3); print $3}' "${FINDINGS}" \
        | grep -E '^[a-z]' | sort | uniq -c | sort -rn \
        | awk '{mod=$2; count=$1; print "| "mod" | "count" |"}' || true
    echo
    echo "## Détail des findings"
    echo
    echo "| Sev | Check | Location | Message |"
    echo "|---|---|---|---|"
    awk -F'\t' '{
        gsub(/\|/, "\\|", $4)
        printf "| %s | %s | `%s` | %s |\n", $1, $2, $3, $4
    }' "${FINDINGS}" || true
} > "${REPORT}"

echo
echo "✓ Rapport généré :"
echo "  • ${FULL}"
echo "  • ${FINDINGS}    (${TOTAL} findings)"
echo "  • ${REPORT}"

exit ${MVN_EXIT}
