#!/bin/bash
# Hourly report-only XVF narrow signal. This script can never place orders: dry-run is pinned true.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${PROP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"
LOG="$REPO/logs/xvf-narrow-dry-run.log"
CP_FILE="$REPO/target/classpath.txt"

mkdir -p "$REPO/logs"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG"; }

for file in .env .env.binance .env.bybit .env.hyperliquid; do
    if [ ! -f "$REPO/$file" ]; then
        say "DRY RUN FAILED: missing $file"
        exit 1
    fi
    set -a
    # shellcheck disable=SC1090
    source "$REPO/$file"
    set +a
done

if [ ! -x "$JAVA" ]; then
    say "DRY RUN FAILED: Java 25 not found at $JAVA"
    exit 1
fi
if [ ! -d "$REPO/target/classes" ]; then
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" mvn -q compile) \
        >>"$LOG" 2>&1 || { say "DRY RUN FAILED: compile failed"; exit 1; }
fi
if [ ! -s "$CP_FILE" ] || [ "$REPO/pom.xml" -nt "$CP_FILE" ]; then
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" \
        mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime) \
        >>"$LOG" 2>&1 || { say "DRY RUN FAILED: classpath build failed"; exit 1; }
fi

say "=== narrow signal dry run start ==="
"$JAVA" \
    -DxvfCapital=4500 \
    -DxvfSignalPolicy=narrow-v1 \
    -DxvfDryRun=true \
    -cp "$REPO/target/classes:$(cat "$CP_FILE")" \
    com.smalistean.propstrategy.xvf.execution.XvfExecutionApplication \
    2>&1 | tee -a "$LOG"
STATUS=${PIPESTATUS[0]}

if [ "$STATUS" -eq 0 ]; then
    say "=== narrow signal dry run done ==="
else
    say "DRY RUN FAILED (exit $STATUS)"
fi
exit "$STATUS"
