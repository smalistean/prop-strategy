#!/bin/bash
# Records one Deribit option-chain snapshot. Intended to be run hourly by a scheduler.
#
# Item 4 in RESEARCH_OPTIONS.md needs option QUOTES, and Deribit serves only trade history for free,
# so the data has to be accumulated going forward. Nothing is testable from it for months. A missed
# hour is a permanent hole - the quotes for a past hour cannot be fetched later - so this logs every
# run, including failures, rather than exiting silently.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pinned, NOT ${JAVA_HOME:-...}. launchd carries no JAVA_HOME so scheduled runs fell through to the
# default and worked, but an interactive shell has sdkman's current JDK (11) there, so every hand-run
# died on UnsupportedClassVersionError against class file version 69. Deferring to the environment
# broke the script in exactly the case where a human is watching. PROP_JAVA overrides on a machine
# where the JDK lives elsewhere.
JAVA="${PROP_JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}"
# Deliberately outside target/, which `mvn clean` deletes. The log is the only record of which hours
# were missed, and losing it to a routine rebuild would erase exactly that.
LOG="$REPO/logs/deribit-snapshot.log"
CLASSPATH_FILE="$REPO/target/classpath.txt"

mkdir -p "$REPO/logs"
export DB_USER="${DB_USER:-prop_strategy_app}"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

# Named up front rather than left to a bare "No such file or directory" 30 lines down. Nobody is
# watching this run; the log line has to say what to do.
if [ ! -x "$JAVA" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) NO JVM at $JAVA - install openjdk@25 or set PROP_JAVA" >>"$LOG"
    exit 1
fi

# `mvn clean` removes target/classes and the cached classpath, which would otherwise make every
# subsequent hourly run fail until someone rebuilt by hand. Both are restored here instead.
if [ ! -d "$REPO/target/classes" ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) target/classes missing, rebuilding" >>"$LOG"
    (cd "$REPO" && mvn -q compile) >>"$LOG" 2>&1
fi
# Also regenerated when pom.xml is newer: a cached classpath silently survives a dependency change,
# and the run then dies on NoClassDefFoundError for a jar that is correctly declared.
if [ ! -s "$CLASSPATH_FILE" ] || [ "$REPO/pom.xml" -nt "$CLASSPATH_FILE" ]; then
    (cd "$REPO" && mvn -q dependency:build-classpath \
        -Dmdep.outputFile="$CLASSPATH_FILE" -DincludeScope=runtime) >>"$LOG" 2>&1
fi

"$JAVA" -cp "$REPO/target/classes:$(cat "$CLASSPATH_FILE")" \
    com.smalistean.propstrategy.marketdownloader.DeribitChainSnapshotApplication \
    >>"$LOG" 2>&1
STATUS=$?

if [ $STATUS -ne 0 ]; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) SNAPSHOT FAILED (exit $STATUS)" >>"$LOG"
fi
exit $STATUS
