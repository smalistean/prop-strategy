#!/bin/bash
# Renames hand-downloaded Tardis archives into data/tardis/deribit/ using the date INSIDE each file.
#
# Every Tardis dataset URL serves a file called OPTIONS.csv.gz, so a browser saves the second one as
# OPTIONS-1.csv.gz, the third as OPTIONS-2.csv.gz, and the date is lost. Matching downloads back to
# dates by hand is exactly the kind of bookkeeping that silently mislabels a file, and a mislabelled
# archive would put one month's quotes under another month's timestamp with nothing raising an error.
#
# So the date is read from the data instead: the first row's timestamp column is authoritative.
#
# Usage:
#   scripts/name-tardis-downloads.sh [source-dir]      default source: ~/Downloads
#   scripts/name-tardis-downloads.sh --verify [dir]    also decompress fully to detect truncation
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/data/tardis/deribit"
VERIFY=0
if [ "${1:-}" = "--verify" ]; then VERIFY=1; shift; fi
SRC="${1:-$HOME/Downloads}"

mkdir -p "$DEST"
VERIFY=$VERIFY SRC="$SRC" DEST="$DEST" python3 - <<'PYTHON'
import gzip, os, re, shutil, subprocess, sys
from datetime import datetime, timezone

src, dest, verify = os.environ["SRC"], os.environ["DEST"], os.environ["VERIFY"] == "1"

CANONICAL = re.compile(r"^deribit-options-\d{4}-\d{2}-01\.csv\.gz$")

# Any .csv.gz in the source, not just ones called OPTIONS*: a proxy, download manager or manual save
# produces names like deribit_options_chain_2019-07-01_OPTIONS.csv.gz. The date is read from the
# file's contents either way, so the incoming name does not matter.
candidates = [(src, f) for f in sorted(os.listdir(src)) if f.endswith(".csv.gz")]
# Also normalise files already sitting in the destination under a non-canonical name. Without this
# they are invisible to the download script's "already have it" check and would be fetched again,
# spending quota on data that is already on disk.
if os.path.isdir(dest):
    candidates += [(dest, f) for f in sorted(os.listdir(dest))
                   if f.endswith(".csv.gz") and not CANONICAL.match(f)]
if not candidates:
    print(f"no .csv.gz files to name in {src}")
    sys.exit(0)

for folder, name in candidates:
    path = os.path.join(folder, name)
    # Column 2 is the exchange timestamp in MICROSECONDS.
    #
    # The FIRST row is not usable: it is the opening order-book snapshot and carries a timestamp from
    # just before the UTC day boundary. Reading row one of the verified 2019-05-01 archive returns
    # 2019-04-30, which would file every boundary-straddling archive under the previous month with no
    # error raised. So a block of rows is sampled and the maximum taken, which lands inside the
    # archive's own day.
    stamps = []
    try:
        with gzip.open(path, "rt") as handle:
            handle.readline()                    # header
            for _ in range(5000):
                row = handle.readline()
                if not row:
                    break
                parts = row.split(",")
                if len(parts) > 2 and parts[2].isdigit():
                    stamps.append(int(parts[2]))
    except OSError as error:
        print(f"  SKIP {name}: not readable as gzip ({error})")
        continue
    if not stamps:
        print(f"  SKIP {name}: no parseable timestamps in the first rows")
        continue
    stamp = datetime.fromtimestamp(max(stamps) / 1_000_000, timezone.utc)
    # Tardis publishes first-of-month archives only, so the month is what identifies the file.
    target = os.path.join(dest, f"deribit-options-{stamp:%Y-%m}-01.csv.gz")
    if os.path.exists(target):
        print(f"  SKIP {name}: {os.path.basename(target)} already present")
        continue
    if verify:
        # Full decompression. Slow (tens of seconds per file) but the only way to catch a browser
        # download that stopped early, which would otherwise look like a complete archive.
        if subprocess.run(["gzip", "-t", path], capture_output=True).returncode != 0:
            print(f"  FAIL {name}: truncated or corrupt, not moved")
            continue
    shutil.move(path, target)
    print(f"  OK   {name} -> {os.path.basename(target)} ({os.path.getsize(target)/1e9:.2f} GB)")

present = len([f for f in os.listdir(dest) if f.endswith(".csv.gz")])
print(f"{present}/89 archives present")
PYTHON
