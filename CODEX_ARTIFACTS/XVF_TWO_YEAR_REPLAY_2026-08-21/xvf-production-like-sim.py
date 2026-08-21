#!/usr/bin/env python3
"""Temporary XVF replay with flat period boundaries and 3-day book reconciliation.

This is intentionally outside the repository. Candidates come from
/tmp/xvf-production-like-export.sql and therefore use only data available at the historical
local-midnight cutoff. Positions that remain the exact same pair are retained; dropped or changed
pairs are closed. Every pair is reconsidered on the same uniform 3-day rebalance clock.
"""
import csv
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

ARTIFACT_DIR = Path(__file__).resolve().parent
CANDIDATES_CSV = os.environ.get(
    "CANDIDATES_CSV", str(ARTIFACT_DIR / "generated" / "candidates_production_like.csv")
)
FUNDING_CSV = os.environ.get(
    "FUNDING_CSV", str(ARTIFACT_DIR / "generated" / "funding_cutoff_daily.csv")
)
START = date.fromisoformat(os.environ.get("SIM_START", "2024-08-21"))
END_EXCL = date.fromisoformat(os.environ.get("SIM_END_EXCL", "2025-08-21"))
POSITIONS = 20
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
VENUES = ("binance", "bybit", "hyperliquid")


@dataclass(frozen=True)
class Candidate:
    base: str
    spread: float
    raw_spread: float
    sv: str
    sv_sym: str
    lv: str
    lv_sym: str
    pair_type: str
    rank: int

    @property
    def key(self):
        return (self.base, self.sv, self.sv_sym, self.lv, self.lv_sym)


@dataclass
class Position:
    candidate: Candidate
    notional: float


def parse_date(value):
    return date.fromisoformat(value[:10])


def load_inputs():
    candidates = defaultdict(list)
    with open(CANDIDATES_CSV, newline="") as handle:
        for row in csv.DictReader(handle):
            d = parse_date(row["w"])
            candidates[d].append(Candidate(
                row["base"], float(row["spread"]), float(row["raw_spread"]),
                row["sv"], row["sv_sym"], row["lv"], row["lv_sym"],
                row["pair_type"], int(row["rk"])))
    for rows in candidates.values():
        rows.sort(key=lambda c: c.rank)

    funding = {}
    with open(FUNDING_CSV, newline="") as handle:
        for row in csv.DictReader(handle):
            funding[(row["venue"], row["venue_symbol"], parse_date(row["d"]))] = float(row["rate_sum"])
    return candidates, funding


def entry_fee(candidate, notional):
    maker = candidate.sv if VENUE_DEPTH[candidate.sv] < VENUE_DEPTH[candidate.lv] else candidate.lv
    taker = candidate.lv if maker == candidate.sv else candidate.sv
    return {maker: notional * MAKER_BPS[maker] / 10000.0,
            taker: notional * TAKER_BPS[taker] / 10000.0}


def simulate(candidates, funding, allocations, constrained=True, backfill=True, verbose=False):
    total_start = sum(allocations.values())
    # Production sizes from the explicitly supplied xvfCapital. It does not read account equity and
    # compound automatically at each rebalance.
    leg_notional = float(os.environ.get("LEG_NOTIONAL", total_start / (POSITIONS * 2.0)))
    balance = dict(allocations)
    used = {v: 0.0 for v in VENUES}
    fees = {v: 0.0 for v in VENUES}
    funding_net = {v: 0.0 for v in VENUES}
    positions = {}
    opened = closed = retained_observations = 0
    skipped_capital = 0
    selected_ranks = []
    open_position_days = 0
    demand_samples = []
    actual_samples = []
    max_used = {v: 0.0 for v in VENUES}
    min_free = dict(allocations)
    leg_days = {v: 0 for v in VENUES}
    missing_leg_days = {v: 0 for v in VENUES}
    missing_details = []

    def charge(v, amount):
        balance[v] -= amount
        fees[v] += amount

    def close_position(base):
        nonlocal closed
        p = positions.pop(base)
        c = p.candidate
        charge(c.sv, p.notional * TAKER_BPS[c.sv] / 10000.0)
        charge(c.lv, p.notional * TAKER_BPS[c.lv] / 10000.0)
        used[c.sv] -= p.notional
        used[c.lv] -= p.notional
        closed += 1

    day = START
    while day <= END_EXCL:
        # Events visible at this midnight occurred after the preceding cutoff. Existing positions
        # receive them before the book is reconciled at this cutoff.
        for p in positions.values():
            c = p.candidate
            leg_days[c.sv] += 1
            leg_days[c.lv] += 1
            if (c.sv, c.sv_sym, day) not in funding:
                missing_leg_days[c.sv] += 1
                missing_details.append((day,c.base,c.sv,c.sv_sym))
            if (c.lv, c.lv_sym, day) not in funding:
                missing_leg_days[c.lv] += 1
                missing_details.append((day,c.base,c.lv,c.lv_sym))
            sv_pnl = funding.get((c.sv, c.sv_sym, day), 0.0) * p.notional
            lv_pnl = -funding.get((c.lv, c.lv_sym, day), 0.0) * p.notional
            balance[c.sv] += sv_pnl
            balance[c.lv] += lv_pnl
            funding_net[c.sv] += sv_pnl
            funding_net[c.lv] += lv_pnl

        if day == END_EXCL:
            for base in list(positions):
                close_position(base)
            break

        open_position_days += len(positions)

        if day in candidates:
            ranked = candidates[day]
            desired = ranked[:POSITIONS]
            desired_keys = {c.key for c in desired}
            demand = {v: 0 for v in VENUES}
            for c in desired:
                demand[c.sv] += 1
                demand[c.lv] += 1
            demand_samples.append((day, demand))

            # Exact-pair persistence is free. A changed direction, venue, or contract is a genuine
            # replacement and closes both legs.
            for base, p in list(positions.items()):
                if p.candidate.key not in desired_keys:
                    close_position(base)

            retained_observations += len(positions)

            # Production's entry path walks the uncapped ranked list after permanent/capital skips.
            # Without backfill, restrict the attempt to the desired top 20.
            attempt = ranked if backfill else desired
            for c in attempt:
                if len(positions) >= POSITIONS:
                    break
                if c.base in positions:
                    continue
                if constrained and (balance[c.sv] - used[c.sv] < leg_notional
                                    or balance[c.lv] - used[c.lv] < leg_notional):
                    skipped_capital += 1
                    continue
                ef = entry_fee(c, leg_notional)
                for v, amount in ef.items():
                    charge(v, amount)
                used[c.sv] += leg_notional
                used[c.lv] += leg_notional
                positions[c.base] = Position(c, leg_notional)
                selected_ranks.append(c.rank)
                opened += 1

            actual = {v: 0 for v in VENUES}
            for p in positions.values():
                actual[p.candidate.sv] += 1
                actual[p.candidate.lv] += 1
            actual_samples.append((day, actual))

        for v in VENUES:
            max_used[v] = max(max_used[v], used[v])
            min_free[v] = min(min_free[v], balance[v] - used[v])
        day += timedelta(days=1)

    total_end = sum(balance.values())
    n_reb = len(actual_samples)
    result = {
        "start": total_start,
        "end": total_end,
        "net": total_end-total_start,
        "return_pct": (total_end/total_start-1)*100,
        "funding": sum(funding_net.values()),
        "fees": sum(fees.values()),
        "opened": opened,
        "closed": closed,
        "retained_obs": retained_observations,
        "skipped": skipped_capital,
        "avg_positions": (sum(sum(x.values())/2 for _,x in actual_samples)/n_reb if n_reb else 0),
        "avg_rank": (sum(selected_ranks)/len(selected_ranks) if selected_ranks else 0),
        "balance": balance,
        "funding_by_venue": funding_net,
        "fees_by_venue": fees,
        "max_used": max_used,
        "min_free": min_free,
        "demand_samples": demand_samples,
        "actual_samples": actual_samples,
        "leg_notional": leg_notional,
        "leg_days": leg_days,
        "missing_leg_days": missing_leg_days,
        "missing_details": missing_details,
    }
    if verbose:
        print(f"=== flat production-like simulation [{START}, {END_EXCL}) ===")
        print(f"allocation: " + ", ".join(f"{v}=${allocations[v]:,.2f}" for v in VENUES))
        print(f"mode: {'venue-constrained' if constrained else 'capital-assumed-available'}, "
              f"backfill={'on' if backfill else 'off'}, fixed ${leg_notional:,.2f}/leg")
        print(f"opened {opened}, closed {closed}, retained pair-rebalances {retained_observations}, "
              f"capital skips {skipped_capital}, average held after rebalance {result['avg_positions']:.2f}")
        print(f"funding {result['funding']:+,.2f}, fees -{result['fees']:,.2f}, "
              f"net {result['net']:+,.2f} ({result['return_pct']:+.2f}%)")
        for v in VENUES:
            print(f"  {v:12} end {balance[v]:9.2f}  funding {funding_net[v]:+9.2f}  "
                  f"fees {fees[v]:7.2f}  max used {max_used[v]:8.2f}  min free {min_free[v]:8.2f}")
        print("missing funding rows on held leg-days: " + ", ".join(
            f"{v}={missing_leg_days[v]}/{leg_days[v]}" for v in VENUES))
        if missing_details:
            print("  missing detail: " + "; ".join(map(str,missing_details[:20])))
    return result


def percentile(values, p):
    if not values:
        return 0
    values = sorted(values)
    return values[round((len(values)-1)*p)]


def demand_summary(result):
    print("top-20 desired leg demand across rebalances:")
    for v in VENUES:
        values = [x[v] for _, x in result["demand_samples"]]
        print(f"  {v:12} median {percentile(values,.5):2d}, p90 {percentile(values,.9):2d}, "
              f"max {max(values):2d} legs")


def main():
    candidates, funding = load_inputs()
    if len(sys.argv) == 4:
        allocations = dict(zip(VENUES, map(float, sys.argv[1:4])))
    else:
        allocations = {v: 1500.0 for v in VENUES}
    constrained = os.environ.get("UNCONSTRAINED", "0") != "1"
    backfill = os.environ.get("BACKFILL", "1") != "0"
    result = simulate(candidates, funding, allocations, constrained, backfill, True)
    demand_summary(result)


if __name__ == "__main__":
    main()
