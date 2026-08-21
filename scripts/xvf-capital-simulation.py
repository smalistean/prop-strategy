#!/usr/bin/env python3
"""XVF simulation: capital split across binance/bybit/hyperliquid, CEX-CEX on a 3-day cycle,
CEX-DEX on 7, real historical funding, real per-venue maker/taker fees, per-venue capital
constraints (no cross-margin - a venue that runs low on its own balance skips a trade even if
another venue has capital free).

What this does NOT model, stated up front: price/basis divergence between legs during the hold.
XVF_STRATEGY.md's own sweeps treat that as a separately-measured cost (basis drag), not something
simulated from actual price paths - this simulation follows the same convention. So the numbers
here answer "what would funding + real fees have done," not "what would the full P&L have done" -
basis risk (XVF_LIVE_FINDINGS.md's ACE finding) is real and additional to this.

Input: two CSVs produced by scripts/analysis-capital-simulation-export.sql - a freshness-discounted
top-20 candidate list per rebalance day, and raw daily funding sums. That export mirrors
XvfSignalEngine.rankedCandidatesRaw/rankedCandidates; if the production ranking changes, the export
needs to change with it or this stops being a simulation of what the strategy actually does.

Fees are per-venue, not the flat MAKER_BPS/TAKER_BPS XvfConfig assumes - see the constants below and
XVF_LIVE_FINDINGS.md's measured table. This was added 2026-08-21 after an independent review found
the original flat 1.8/5.0bp assumption understated Bybit's real measured cost (3.6/10.0bp) by
roughly 2x, and this book is Bybit-legged on nearly every pair.

Two real runs this session, corrected (both $4,500 split 1500/1500/1500, real per-venue fees,
corrected ranking - see analysis-capital-simulation-export.sql's header for what changed):
  2025-08-21 to 2026-08-20: net +295.12 USD (+6.56%), 701 opened, 1432 skipped for venue capital
  2024-08-21 to 2025-08-20: net  +26.36 USD (+0.59%),  996 opened,  414 skipped for venue capital
These land close to the FIRST (pre-correction) run of this simulator despite three separate
corrections going in (real fees, corrected discount value, corrected lookback) - the corrections
roughly offset each other in net effect, which is worth treating as a coincidence of this specific
window, not evidence the result is insensitive to methodology. Both real years still land far below
the "signal quality" percentages the ranking backtests report in isolation - a full 20-slot book
blends in lower-ranked candidates, pays real round-trip fees, and loses real capacity every time
"skipped for lack of venue capital" fires. That skip count is the single biggest legible drag and
is a direct consequence of the no-cross-margin constraint, not a bug in this simulator.
"""
import csv
from collections import defaultdict
from datetime import date, timedelta

import os
CANDIDATES_CSV = os.environ.get("CANDIDATES_CSV", "candidates.csv")
FUNDING_CSV = os.environ.get("FUNDING_CSV", "funding_daily.csv")

# Per-venue, not the flat 1.8/5.0 XvfConfig assumes. XVF_LIVE_FINDINGS.md measured real fills:
# Binance taker 4.5bp (BNB discount active - cheaper than its own 5.0bp published schedule),
# Bybit maker 3.6bp / taker 10.0bp (nearly double its published 2.0/5.5 schedule, unexplained from
# outside but what the fills are actually charged). Hyperliquid's maker fee was not established in
# that measurement, so it keeps the original 1.8bp assumption rather than a fabricated number -
# every HL-touching pair's fee here is still an estimate, not a measured one.
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
POSITIONS = 20
START = date.fromisoformat(os.environ.get("SIM_START", "2025-08-20"))
END = date.fromisoformat(os.environ.get("SIM_END", "2026-08-20"))

VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}  # thinner (lower) rests as maker


def parse_date(s):
    return date.fromisoformat(s[:10])


def load_candidates():
    by_week = defaultdict(list)
    with open(CANDIDATES_CSV) as f:
        for row in csv.DictReader(f):
            w = parse_date(row["w"])
            by_week[w].append({
                "base": row["base"], "spread": float(row["spread"]),
                "sv": row["sv"], "sv_sym": row["sv_sym"],
                "lv": row["lv"], "lv_sym": row["lv_sym"],
                "pair_type": row["pair_type"],
            })
    for w in by_week:
        by_week[w].sort(key=lambda c: -c["spread"])
    return by_week


def load_funding():
    # (venue, symbol, date) -> summed funding_rate that day
    out = {}
    with open(FUNDING_CSV) as f:
        for row in csv.DictReader(f):
            out[(row["venue"], row["venue_symbol"], parse_date(row["d"]))] = float(row["rate_sum"])
    return out


def maker_taker(sv, lv):
    return (sv, lv) if VENUE_DEPTH[sv] < VENUE_DEPTH[lv] else (lv, sv)


def main():
    import sys
    if len(sys.argv) == 4:
        start_alloc = {"binance": float(sys.argv[1]), "bybit": float(sys.argv[2]),
                        "hyperliquid": float(sys.argv[3])}
    else:
        start_alloc = {"binance": 3800.0, "bybit": 3800.0, "hyperliquid": 2400.0}

    candidates = load_candidates()
    funding = load_funding()
    weeks = sorted(candidates.keys())

    balance = dict(start_alloc)
    used = {"binance": 0.0, "bybit": 0.0, "hyperliquid": 0.0}
    open_positions = []   # dicts: base, pair_type, sv, sv_sym, lv, lv_sym, exit_date, notional
    fees_paid = {"binance": 0.0, "bybit": 0.0, "hyperliquid": 0.0}
    funding_net = {"binance": 0.0, "bybit": 0.0, "hyperliquid": 0.0}
    leg_notional_history = []
    trades_opened = 0
    trades_skipped_capital = 0
    week_idx = {w: i for i, w in enumerate(weeks)}

    day = START
    while day <= END:
        # 1. accrue today's funding for every open position
        for p in open_positions:
            sv_rate = funding.get((p["sv"], p["sv_sym"], day), 0.0)
            lv_rate = funding.get((p["lv"], p["lv_sym"], day), 0.0)
            sv_pnl = sv_rate * p["notional"]     # short receives positive rate
            lv_pnl = -lv_rate * p["notional"]    # long pays positive rate
            balance[p["sv"]] += sv_pnl
            balance[p["lv"]] += lv_pnl
            funding_net[p["sv"]] += sv_pnl
            funding_net[p["lv"]] += lv_pnl

        # 2. close anything expiring today - taker/taker, a resting exit leaves the other leg naked
        still_open = []
        for p in open_positions:
            if p["exit_date"] == day:
                sv_fee = p["notional"] * (TAKER_BPS[p["sv"]] / 10000.0)
                lv_fee = p["notional"] * (TAKER_BPS[p["lv"]] / 10000.0)
                balance[p["sv"]] -= sv_fee
                balance[p["lv"]] -= lv_fee
                fees_paid[p["sv"]] += sv_fee
                fees_paid[p["lv"]] += lv_fee
                used[p["sv"]] -= p["notional"]
                used[p["lv"]] -= p["notional"]
            else:
                still_open.append(p)
        open_positions = still_open

        # 3. on a candidate week's start day, fill open slots from the ranked list
        if day in candidates:
            open_bases = {p["base"] for p in open_positions}
            total_capital = sum(balance.values())
            leg_notional = total_capital * 1.0 / (POSITIONS * 2)   # LEG_LEVERAGE=1x, matches production
            leg_notional_history.append((day, leg_notional, total_capital))
            for c in candidates[day]:
                if len(open_positions) >= POSITIONS:
                    break
                if c["base"] in open_bases:
                    continue
                sv, lv = c["sv"], c["lv"]
                if balance[sv] - used[sv] < leg_notional or balance[lv] - used[lv] < leg_notional:
                    trades_skipped_capital += 1
                    continue
                maker_v, taker_v = maker_taker(sv, lv)
                maker_fee = leg_notional * (MAKER_BPS[maker_v] / 10000.0)
                taker_fee = leg_notional * (TAKER_BPS[taker_v] / 10000.0)
                balance[maker_v] -= maker_fee
                balance[taker_v] -= taker_fee
                fees_paid[maker_v] += maker_fee
                fees_paid[taker_v] += taker_fee
                used[sv] += leg_notional
                used[lv] += leg_notional
                hold = 3 if c["pair_type"] == "CEX-CEX" else 7
                open_positions.append({
                    "base": c["base"], "pair_type": c["pair_type"],
                    "sv": sv, "sv_sym": c["sv_sym"], "lv": lv, "lv_sym": c["lv_sym"],
                    "exit_date": day + timedelta(days=hold), "notional": leg_notional,
                })
                open_bases.add(c["base"])
                trades_opened += 1

        day += timedelta(days=1)

    # close whatever's still open at year end, mark-to-market skipped (funding-only model) -
    # report both the settled balance and what's still committed as open notional
    print(f"=== simulation {START} to {END} ===")
    print(f"trades opened: {trades_opened}, skipped for lack of venue capital: {trades_skipped_capital}")
    print(f"positions still open at end: {len(open_positions)}")
    print()
    print(f"{'venue':12} {'start':>10} {'end (settled)':>15} {'still committed':>17} {'fees paid':>12} {'funding net':>13}")
    total_start = total_end = total_committed = 0.0
    for v in ("binance", "bybit", "hyperliquid"):
        committed = used[v]
        print(f"{v:12} {start_alloc[v]:>10,.2f} {balance[v]:>15,.2f} {committed:>17,.2f} "
              f"{fees_paid[v]:>12,.2f} {funding_net[v]:>13,.2f}")
        total_start += start_alloc[v]
        total_end += balance[v]
        total_committed += committed
    print(f"{'TOTAL':12} {total_start:>10,.2f} {total_end:>15,.2f} {total_committed:>17,.2f} "
          f"{sum(fees_paid.values()):>12,.2f} {sum(funding_net.values()):>13,.2f}")
    print()
    print(f"net return: {(total_end-total_start):+,.2f} USD ({(total_end/total_start-1)*100:+.2f}%)")
    print()
    firsts = leg_notional_history[0]
    lasts = leg_notional_history[-1]
    lo = min(leg_notional_history, key=lambda x: x[1])
    hi = max(leg_notional_history, key=lambda x: x[1])
    print(f"leg notional recomputed weekly from CURRENT total capital (compounding, not fixed):")
    print(f"  first rebalance ({firsts[0]}): ${firsts[1]:,.2f}/leg on ${firsts[2]:,.0f} total capital")
    print(f"  last rebalance  ({lasts[0]}): ${lasts[1]:,.2f}/leg on ${lasts[2]:,.0f} total capital")
    print(f"  lowest  ({lo[0]}): ${lo[1]:,.2f}/leg")
    print(f"  highest ({hi[0]}): ${hi[1]:,.2f}/leg")


if __name__ == "__main__":
    main()
