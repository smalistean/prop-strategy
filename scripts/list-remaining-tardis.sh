#!/bin/bash
# Regenerates the hand-download lists from what is actually on disk.
#
# Run after every batch: scripts/name-tardis-downloads.sh && scripts/list-remaining-tardis.sh
#
# Writes data/tardis/DOWNLOAD_ORDER.md (priority-ordered, for working through by hand) and
# data/tardis/remaining-urls.txt (url<TAB>filename, for a download manager).
set -u -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/data/tardis/deribit" python3 - <<'PYTHON'
import os

dest = os.environ["DEST"]
have = {f for f in os.listdir(dest) if f.endswith(".csv.gz")} if os.path.isdir(dest) else set()

# Priority order, and the reasoning behind it:
#
# TIER 1/2 are CONSECUTIVE recent months because the first quantity the test measures is
# month-over-month persistence of the funding spread, which needs adjacent observations. A scattered
# sample across years cannot produce a lag-1 estimate at all.
#
# TIER 4 is last because the USDC altcoin chains (HYPE, SOL, XRP, TRX, AVAX) did not exist before
# ~2024 - those files contain BTC and ETH only, and the altcoin chains are the reason this dataset
# is worth having. Spending a scarce daily quota chronologically would fetch the least useful years
# first.
TIERS = [
    ("TIER 1 - 12 consecutive recent months (month-over-month persistence)",
     [(2026, m) for m in range(8, 0, -1)] + [(2025, m) for m in range(12, 8, -1)]),
    ("TIER 2 - next 12 consecutive (enables a 2025 vs 2026 split)",
     [(2025, m) for m in range(8, 0, -1)] + [(2024, m) for m in range(12, 8, -1)]),
    ("TIER 3 - 2023-01 .. 2024-08 (adds 2024; altcoin chains thin out going back)",
     [(2024, m) for m in range(8, 0, -1)] + [(2023, m) for m in range(12, 0, -1)]),
    ("TIER 4 - 2019-04 .. 2022-12 (BTC and ETH only - no USDC altcoin chains existed)",
     [(y, m) for y in (2022, 2021, 2020) for m in range(12, 0, -1)]
     + [(2019, m) for m in range(12, 3, -1)]),
]

lines = [
    "# Tardis Deribit archives - hand-download priority order",
    "",
    f"**{len(have)}/89 present.** Regenerate this file with `scripts/list-remaining-tardis.sh`.",
    "",
    "Filenames do not matter. Save anywhere, then run `scripts/name-tardis-downloads.sh` - it reads",
    "the date out of each file's contents and files it correctly. Add `--verify` to also decompress",
    "each fully, which catches a download that stopped early and would otherwise look complete.",
    "",
    "Stop whenever you like: Tier 1 alone answers whether the spread persists. Tier 4 is optional.",
    "",
]
pairs, n = [], 0
for title, months in TIERS:
    todo = [(y, m) for y, m in months
            if f"deribit-options-{y}-{m:02d}-01.csv.gz" not in have]
    done = len(months) - len(todo)
    lines += [f"## {title}", "", f"_{done}/{len(months)} already downloaded._", ""]
    if not todo:
        lines += ["Complete.", ""]
        continue
    for y, m in todo:
        n += 1
        url = f"https://datasets.tardis.dev/v1/deribit/options_chain/{y}/{m:02d}/01/OPTIONS.csv.gz"
        lines.append(f"{n}. {url}")
        pairs.append((url, f"deribit-options-{y}-{m:02d}-01.csv.gz"))
    lines.append("")

root = os.path.dirname(dest)
with open(os.path.join(root, "DOWNLOAD_ORDER.md"), "w") as handle:
    handle.write("\n".join(lines))
with open(os.path.join(root, "remaining-urls.txt"), "w") as handle:
    for url, name in pairs:
        handle.write(f"{url}\t{name}\n")

print(f"{len(have)}/89 present, {len(pairs)} remaining")
print(f"  {root}/DOWNLOAD_ORDER.md")
print(f"  {root}/remaining-urls.txt")
PYTHON
