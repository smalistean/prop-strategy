#!/bin/bash
# Runs the read-only Curve composition monitor and stores the reading in PostgreSQL.
# Design, discovery rule and thresholds: CURVE_MONITOR_PREREGISTRATION.md (incl. amendment A2).
# Actions per alert level: STABLECOIN_DEPEG_DOSSIER.md.
# Touches no keys and places no orders; makes eth_call and one Curve-API GET only.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$REPO/logs/curve-monitor.log"
mkdir -p "$REPO/logs"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG"; }

if [ -f "$REPO/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$REPO/.env"
    set +a
fi

say "=== curve monitor start ==="
python3 "$REPO/scripts/curve-composition-monitor.py" "$@" >>"$LOG" 2>&1
rc=$?
if [ "$rc" -eq 0 ]; then
    say "wrote $REPO/CURVE_COMPOSITION_MONITOR.md"
elif [ "$rc" -eq 2 ]; then
    # The report was written, headlined DATA FAILURE: too few reads to call the level (see the lines above).
    say "!! DATA FAILURE (exit 2): report written but NOT a valid reading; rerun when the network is up"
    exit 2
else
    say "!! monitor failed (exit $rc), see $LOG"
    exit 1
fi
say "=== curve monitor done ==="
