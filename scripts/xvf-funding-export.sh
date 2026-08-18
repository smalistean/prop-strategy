#!/bin/bash
# Pulls the hourly XVF funding-observation buffer out of DynamoDB into local Postgres.
#
# Counterpart to deribit-export.sh, and it exists for the same reason: the recorder Lambda writes
# each hour to DynamoDB under a 30-day TTL, and an hour that is never exported is deleted on day 31
# with no error. These rows are observations of PENDING rates - a measurement of a moment that has
# passed - so unlike settled funding history no venue endpoint will serve them again afterwards.
#
# Distinct from xvf-refresh.sh, which backfills SETTLED funding into perp_funding_all from the venue
# REST APIs. That data is refetchable; this is not. They write different tables and neither covers
# the other.
#
# Incremental: resumes from the newest hour already in Postgres, so a routine run transfers only what
# is new and re-running is free (ON CONFLICT DO NOTHING on the same primary key).
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pinned, NOT ${JAVA_HOME:-...} - an interactive shell has sdkman's current JDK (11) there, which
# cannot load class file version 69. Same reasoning as deribit-snapshot.sh.
JAVA="${PROP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"
LOG="$REPO/logs/xvf-funding-export.log"
CLASSPATH_FILE="$REPO/target/classpath.txt"

TABLE="${DYNAMO_TABLE:-xvf-funding-observation}"
REGION="${AWS_REGION:-eu-central-1}"

mkdir -p "$REPO/logs"
export DB_USER="${DB_USER:-prop_strategy_app}"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

if [ ! -x "$JAVA" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) NO JVM at $JAVA - install openjdk@25 or set PROP_JAVA" >>"$LOG"
    exit 1
fi

# `mvn clean` removes both of these and would otherwise make every scheduled run fail silently.
if [ ! -d "$REPO/target/classes" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) target/classes missing, rebuilding" >>"$LOG"
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" mvn -q compile) >>"$LOG" 2>&1
fi
# Also regenerated when pom.xml is newer: a cached classpath silently survives a dependency change,
# and the run then dies on NoClassDefFoundError for a jar that is correctly declared.
if [ ! -s "$CLASSPATH_FILE" ] || [ "$REPO/pom.xml" -nt "$CLASSPATH_FILE" ]; then
    (cd "$REPO" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" mvn -q dependency:build-classpath \
        -Dmdep.outputFile="$CLASSPATH_FILE" -DincludeScope=runtime) >>"$LOG" 2>&1
fi

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) === export start ($TABLE, $REGION) ===" >>"$LOG"
"$JAVA" -cp "$REPO/target/classes:$(cat "$CLASSPATH_FILE")" \
    -DdynamoTable="$TABLE" -DawsRegion="$REGION" ${EXPORT_FROM:+-DexportFrom="$EXPORT_FROM"} \
    com.smalistean.propstrategy.marketdownloader.VenueFundingDynamoExportApplication \
    2>&1 | tee -a "$LOG"
STATUS=${PIPESTATUS[0]}

if [ $STATUS -ne 0 ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) EXPORT FAILED (exit $STATUS)" >>"$LOG"
fi
exit $STATUS
