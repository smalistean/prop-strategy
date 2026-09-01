#!/bin/bash
# Phase 2 candidate ranking — see PHASE2_PREREGISTRATION.md (design frozen 2026-09-01 17:47 UTC).
# Runs each pre-declared primary config over both cohorts against its own matched random control.
# Usage: bash scripts/phase2-run.sh   (writes phase2_out/*.txt, ~17 min)

set -u
cd "$(dirname "$0")/.."
set -a; source .env; set +a
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
OUT=phase2_out; mkdir -p "$OUT"

CANDIDATES=(
  donchian-breakout
  volatility-compression-breakout
  rsi-atr-mean-reversion
  structural-channel
  three-level-range
  intraday-flat-mean-reversion
  liquidity-sweep-reversal-v1
  passive-maker-mean-reversion
  volume-profile-breakout
  multi-timeframe-flat-long
  order-flow-exhaustion
)

run() { # name cohort start end
  local name=$1 cohort=$2 start=$3 end=$4
  local f="$OUT/${name}__${cohort}.txt"
  [ -s "$f" ] && { echo "skip $name $cohort (exists)"; return; }
  echo "=== $name / cohort $cohort ==="
  mvn -q exec:java \
    -Dexec.mainClass=com.smalistean.propstrategy.statistics.ChallengeHarnessApplication \
    -DstrategyConfig="config/backtests/${name}.properties" \
    -DharnessStart="$start" -DharnessEnd="$end" \
    -DriskFraction=0.0025 > "$f" 2>&1
}

for c in "${CANDIDATES[@]}"; do
  run "$c" A 2021-08-01T00:00:00Z 2024-02-01T00:00:00Z
  run "$c" B 2024-02-01T00:00:00Z 2026-08-01T00:00:00Z
done
echo "PHASE2_SWEEP_COMPLETE"
