#!/bin/bash
# Downloads Tardis.dev's free Deribit options_chain archives into data/tardis/deribit/.
#
# Tardis publishes the first day of every month without an API key. Verified coverage is
# 2019-04-01 through 2026-08-01, which is 89 files at roughly 1.2-1.9 GB compressed each.
#
# Raw archives are kept rather than streamed and discarded so the parser can change without
# re-downloading 130 GB. data/ is gitignored; these must never be committed.
#
# Notes recorded from probing the endpoint, so they are not rediscovered the hard way:
#   * HEAD returns 404 even for dates that GET successfully. It is not a coverage test.
#   * Range requests return a Cloudflare 403. Partial reads are not possible; files stream whole.
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/data/tardis/deribit"
LOG="$REPO/logs/tardis-download.log"
BASE="https://datasets.tardis.dev/v1/deribit/options_chain"
# Sequential by default. Three concurrent downloads triggered HTTP 429 after only three files:
# the free tier rate-limits, and parallelism buys nothing once it does. Each file takes tens of
# seconds anyway, so serial transfer is close to the practical ceiling regardless.
PARALLEL="${TARDIS_PARALLEL:-1}"
# Pause between files, on top of transfer time, to stay under the same limit.
GAP_SECONDS="${TARDIS_GAP:-15}"
# Wait after a rejection. Retrying at 15s intervals for 90 seconds did not clear a 429, so the
# cooldown is longer than a burst window. Ten minutes is a guess, and the log records whether it
# was enough - if RETRY lines keep appearing at this spacing, raise it.
COOLDOWN_SECONDS="${TARDIS_COOLDOWN:-600}"
# 89 files against a limit of unknown size. This is a background job measured in hours, possibly
# days; it is resumable, so being slow costs waiting rather than work.
MAX_ATTEMPTS="${TARDIS_ATTEMPTS:-12}"

mkdir -p "$DEST" "$REPO/logs"

# NEWEST FIRST, deliberately. The quota allows only a few files a day, so order decides which data
# exists first. The USDC chains - HYPE, SOL, XRP, TRX, AVAX - are recent listings and are the reason
# this dataset is worth having; 2019-2021 files contain BTC and ETH only. Downloading chronologically
# spends the scarce quota on the least useful years.
dates() {
    for YEAR in $(seq 2026 -1 2019); do
        for MONTH in $(seq -w 12 -1 1); do
            # Coverage starts 2019-04 and the last published first-of-month is 2026-08.
            [ "$YEAR" -eq 2019 ] && [ "$MONTH" -lt 04 ] && continue
            [ "$YEAR" -eq 2026 ] && [ "$MONTH" -gt 08 ] && continue
            echo "$YEAR/$MONTH/01"
        done
    done
}

fetch() {
    local DATE="$1"
    local NAME="${DATE//\//-}"
    local OUT="$DEST/deribit-options-$NAME.csv.gz"
    local PART="$OUT.part"

    if [ -s "$OUT" ]; then
        return 0
    fi
    # -C - resumes a previous partial into .part; the file is renamed only on a clean exit, so a
    # truncated transfer can never be mistaken for a complete archive by the check above.
    local CODE
    CODE=$(curl -sS --fail -C - -o "$PART" -w '%{http_code}' \
        "$BASE/$DATE/OPTIONS.csv.gz" 2>/dev/null)
    # Captured immediately: reading $? inside the echo below yields the status of the $(date ...)
    # substitution instead, which is what made every failure log "exit 0" in an earlier version.
    local STATUS=$?

    if [ $STATUS -eq 0 ] && [ -s "$PART" ]; then
        mv "$PART" "$OUT"
        echo "$(date -u +%H:%M:%S) OK   $NAME $(du -h "$OUT" | cut -f1)" >>"$LOG"
        sleep "$GAP_SECONDS"
        return 0
    fi

    echo "$(date -u +%H:%M:%S) FAIL $NAME http=$CODE exit=$STATUS" >>"$LOG"
    # 429 means the daily quota is spent. The server reported retryAfterSeconds 81980 - 22.8 hours -
    # so retrying within this run is pure waiting. Signal the caller to stop for today.
    [ "$CODE" = "429" ] && return 42
    return 1
}

echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) starting, $(dates | wc -l | tr -d ' ') dates ===" >>"$LOG"
while read -r DATE; do
    fetch "$DATE"
    if [ $? -eq 42 ]; then
        echo "$(date -u +%H:%M:%S) QUOTA EXHAUSTED - stopping, resume on next scheduled run" >>"$LOG"
        break
    fi
done < <(dates)

COMPLETE=$(ls -1 "$DEST"/*.csv.gz 2>/dev/null | wc -l | tr -d ' ')
echo "=== done: $COMPLETE/$(dates | wc -l | tr -d ' ') files, $(du -sh "$DEST" | cut -f1) ===" >>"$LOG"
printf 'downloaded %s files, %s\n' "$COMPLETE" "$(du -sh "$DEST" | cut -f1)"
