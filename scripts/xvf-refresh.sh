#!/bin/bash
# Keeps the XVF funding panel current. Intended to run daily from launchd.
#
# XvfSignalApplication refuses to emit a book unless every venue has at least 100 symbols in the
# trailing window. That guard exists because Binance funding arrives from a MONTHLY archive: between
# month-end and publication only the sixteen old-universe symbols stay current, the cross-venue
# pairing collapses, and the signal returns an empty book with no error. This script is what keeps
# the guard satisfied.
#
# Order matters. Funding first, because the signal needs it; candles second, because they only feed
# basis measurement and can lag a day without breaking anything.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pinned, NOT ${JAVA_HOME:-...} - see the same note in deribit-snapshot.sh. An interactive shell has
# sdkman's current JDK (11) in JAVA_HOME, which cannot load class file version 69.
JAVA="${PROP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"
LOG="$REPO/logs/xvf-refresh.log"
CP_FILE="$REPO/target/classpath.txt"

mkdir -p "$REPO/logs"
export DB_USER="${DB_USER:-prop_strategy_app}"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

if [ ! -x "$JAVA" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) NO JVM at $JAVA - install openjdk@25 or set PROP_JAVA" >>"$LOG"
    exit 1
fi

# `mvn clean` removes both of these and would otherwise make every scheduled run fail silently.
if [ ! -d "$REPO/target/classes" ]; then
    (cd "$REPO" && mvn -q compile) >>"$LOG" 2>&1
fi
# Also regenerated when pom.xml is newer: a cached classpath silently survives a dependency change,
# and the run then dies on NoClassDefFoundError for a jar that is correctly declared.
if [ ! -s "$CP_FILE" ] || [ "$REPO/pom.xml" -nt "$CP_FILE" ]; then
    (cd "$REPO" && mvn -q dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime) >>"$LOG" 2>&1
fi
CP="$REPO/target/classes:$(cat "$CP_FILE")"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >>"$LOG"; }

say "=== refresh start ==="

# Only the last 10 days. Every importer walks backwards from now and stops at this floor, so a daily
# run costs one or two pages per symbol instead of re-walking years - the dYdX full history took 6.5
# hours, which is not a daily job.
FROM="$(date -u -v-10d +%Y-%m-%dT00:00:00Z 2>/dev/null || date -u -d '10 days ago' +%Y-%m-%dT00:00:00Z)"

# binance, bybit and hyperliquid are the three venues XvfConfig.VENUES actually trades - they run
# first so the book that matters is refreshable as early as possible. dydx/okx/bitget/gate only feed
# the broader signal-scope research (XVF_V1_SCOPE.md), not anything this account holds, so they run
# last and a slow one of them never delays the venues that do.
say "funding: binance,bybit from $FROM"
"$JAVA" -Dvenues=binance,bybit -DvenueFundingFrom="$FROM" \
    -cp "$CP" com.smalistean.propstrategy.marketdownloader.VenueFundingImportApplication \
    >>"$LOG" 2>&1 || say "!! venue funding import failed (binance,bybit)"

say "funding: hyperliquid"
"$JAVA" -cp "$CP" com.smalistean.propstrategy.marketdownloader.HyperliquidFundingImportApplication \
    >>"$LOG" 2>&1 || say "!! hyperliquid funding import failed"

say "funding: dydx,okx,bitget,gate from $FROM"
"$JAVA" -Dvenues=dydx,okx,bitget,gate -DvenueFundingFrom="$FROM" \
    -cp "$CP" com.smalistean.propstrategy.marketdownloader.VenueFundingImportApplication \
    >>"$LOG" 2>&1 || say "!! venue funding import failed (dydx,okx,bitget,gate)"

say "candles: bybit,dydx"
"$JAVA" -DcandleFrom="$FROM" -cp "$CP" \
    com.smalistean.propstrategy.marketdownloader.VenueCandleImportApplication \
    >>"$LOG" 2>&1 || say "!! venue candle import failed"

say "candles: hyperliquid"
"$JAVA" -cp "$CP" com.smalistean.propstrategy.marketdownloader.HyperliquidCandleImportApplication \
    >>"$LOG" 2>&1 || say "!! hyperliquid candle import failed"

# Verify the guard would pass. A refresh that "succeeded" while leaving a venue thin is exactly the
# failure this script exists to prevent, so it is checked rather than assumed.
say "verifying freshness"
psql -U "$DB_USER" -d prop_strategy -t -A -F' ' -c "
  SELECT venue, count(DISTINCT venue_symbol)
  FROM perp_funding_all
  WHERE venue IN ('binance','bybit','hyperliquid','dydx')
    AND funding_time > now() - interval '7 days'
  GROUP BY 1 ORDER BY 1;" >>"$LOG" 2>&1

THIN=$(psql -U "$DB_USER" -d prop_strategy -t -A -c "
  SELECT count(*) FROM (
    SELECT venue FROM perp_funding_all
    WHERE venue IN ('binance','bybit','hyperliquid','dydx')
      AND funding_time > now() - interval '7 days'
    GROUP BY venue HAVING count(DISTINCT venue_symbol) < 100) z;" 2>/dev/null)

if [ "${THIN:-1}" != "0" ]; then
    say "!! WARNING: ${THIN} venue(s) below 100 symbols - XvfSignalApplication will refuse to run"
else
    say "all four venues fresh"
fi
say "=== refresh done ==="
