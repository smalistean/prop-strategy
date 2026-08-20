#!/bin/bash
# Recomputes the live XVF position snapshot (unrealized PnL, fees, funding per pair) and writes it to
# XVF_LIVE_BOOK.md. Meant to be run periodically by hand (or from your own cron) - it does not place
# or touch any order, only reads current positions and fee/funding history from the three venues.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$REPO/logs/xvf-stats.log"
mkdir -p "$REPO/logs"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG"; }

for f in .env .env.binance .env.bybit .env.hyperliquid; do
    if [ ! -f "$REPO/$f" ]; then
        say "!! missing $f - cannot authenticate to the venues"
        exit 1
    fi
    set -a
    # shellcheck disable=SC1090
    source "$REPO/$f"
    set +a
done

say "=== stats run start ==="
if python3 "$REPO/scripts/xvf-position-snapshot.py" >>"$LOG" 2>&1; then
    say "wrote $REPO/XVF_LIVE_BOOK.md"
else
    say "!! snapshot failed, see $LOG"
    exit 1
fi
say "=== stats run done ==="
