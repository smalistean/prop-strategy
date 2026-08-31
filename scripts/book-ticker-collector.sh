#!/bin/bash
# Long-running Binance futures best bid/ask recorder.
#
# Read-only market data: this connects to the public combined stream and holds no credentials
# beyond the database ones, so it can never place an order.
#
# It exists because the public archive cannot answer whether a resting order would have filled.
# Binance stopped publishing bookTicker after 2024-03-30, and bookDepth reports nothing closer
# than +/-0.20% of mid against a spread whose median is 0.044 bp. Anything after March 2024 has
# to be recorded forward, which means uptime is the product - launchd restarts this on exit.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${PROP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"
LOG="$REPO/logs/book-ticker-collector.log"
CP_FILE="$REPO/target/classpath.txt"

mkdir -p "$REPO/logs"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG"; }

if [ ! -f "$REPO/.env" ]; then
    say "COLLECTOR FAILED: missing .env"
    exit 1
fi
set -a
# shellcheck disable=SC1090
source "$REPO/.env"
set +a

if [ ! -x "$JAVA" ]; then
    say "COLLECTOR FAILED: Java 25 not found at $JAVA"
    exit 1
fi
if [ ! -d "$REPO/target/classes" ]; then
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" mvn -q compile) \
        >>"$LOG" 2>&1 || { say "COLLECTOR FAILED: compile failed"; exit 1; }
fi
if [ ! -s "$CP_FILE" ] || [ "$REPO/pom.xml" -nt "$CP_FILE" ]; then
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" \
        mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime) \
        >>"$LOG" 2>&1 || { say "COLLECTOR FAILED: classpath build failed"; exit 1; }
fi

say "=== book-ticker collector start ==="
exec "$JAVA" -cp "$REPO/target/classes:$(cat "$CP_FILE")" \
    -DbookTickerSymbols="${BOOK_TICKER_SYMBOLS:-BTCUSDC,ETHUSDC,BTCUSDT,ETHUSDT}" \
    com.smalistean.propstrategy.marketdownloader.BookTickerCollectorApplication \
    >>"$LOG" 2>&1
