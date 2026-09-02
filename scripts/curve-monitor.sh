#!/bin/bash
# Runs the read-only Curve composition monitor (see CURVE_MONITOR_PREREGISTRATION.md).
# Touches no keys and places no orders; makes eth_call requests only.
set -u -o pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$REPO/scripts/curve-composition-monitor.py" "$@"
