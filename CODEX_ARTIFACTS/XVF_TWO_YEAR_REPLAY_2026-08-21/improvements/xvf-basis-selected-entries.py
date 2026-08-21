#!/usr/bin/env python3
"""Reproduce strict XVF baseline selection and export every newly opened lifecycle.

Temporary Codex research artifact. It intentionally leaves the repository untouched.
"""
import csv
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

IMPROVEMENTS_DIR = Path(__file__).resolve().parent
ARTIFACT_DIR = IMPROVEMENTS_DIR.parent
CANDIDATES_CSV = ARTIFACT_DIR / "generated/candidates_production_like.csv"
FUNDING_CSV = ARTIFACT_DIR / "generated/funding_cutoff_daily.csv"
OUT = IMPROVEMENTS_DIR / "generated/xvf_basis_selected_entries.csv"
PERIODS = ((date(2024, 8, 21), date(2025, 8, 21)),
           (date(2025, 8, 21), date(2026, 8, 21)))
POSITIONS = 20
LEG_NOTIONAL = 112.50
START_ALLOC = {"binance": 1500.0, "bybit": 1500.0, "hyperliquid": 1500.0}
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
VENUES = tuple(START_ALLOC)


@dataclass(frozen=True)
class Candidate:
    w: date
    base: str
    spread: float
    raw_spread: float
    fresh: bool
    thin: float
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
    c: Candidate
    row: dict


def load():
    candidates = defaultdict(list)
    with CANDIDATES_CSV.open(newline="") as f:
        for r in csv.DictReader(f):
            w = date.fromisoformat(r["w"][:10])
            c = Candidate(w, r["base"], float(r["spread"]), float(r["raw_spread"]),
                          r["fresh"].lower() in ("t", "true", "1"), float(r["thin"]),
                          r["sv"], r["sv_sym"], r["lv"], r["lv_sym"],
                          r["pair_type"], int(r["rk"]))
            candidates[w].append(c)
    for rows in candidates.values():
        rows.sort(key=lambda x: x.rank)
    funding = {}
    with FUNDING_CSV.open(newline="") as f:
        for r in csv.DictReader(f):
            funding[(r["venue"], r["venue_symbol"], date.fromisoformat(r["d"][:10]))] = float(r["rate_sum"])
    return candidates, funding


def entry_fee(c, n):
    maker = c.sv if VENUE_DEPTH[c.sv] < VENUE_DEPTH[c.lv] else c.lv
    taker = c.lv if maker == c.sv else c.sv
    return maker, taker, {maker: n * MAKER_BPS[maker] / 10000,
                          taker: n * TAKER_BPS[taker] / 10000}


def simulate(candidates, funding, start, end_excl, rows):
    label = f"{start}/{end_excl}"
    balance = dict(START_ALLOC)
    used = {v: 0.0 for v in VENUES}
    positions = {}
    agg_funding = agg_fees = 0.0
    seq = 0

    def close(base, day, reason):
        nonlocal agg_fees
        p = positions.pop(base)
        for v in (p.c.sv, p.c.lv):
            fee = LEG_NOTIONAL * TAKER_BPS[v] / 10000
            balance[v] -= fee
            agg_fees += fee
            used[v] -= LEG_NOTIONAL
        p.row["close_day"] = day.isoformat()
        p.row["close_reason"] = reason

    day = start
    while day <= end_excl:
        for p in positions.values():
            for v, sym, sign in ((p.c.sv, p.c.sv_sym, 1), (p.c.lv, p.c.lv_sym, -1)):
                pnl = sign * funding.get((v, sym, day), 0.0) * LEG_NOTIONAL
                balance[v] += pnl
                agg_funding += pnl
        if day == end_excl:
            for base in list(positions):
                close(base, day, "period_end")
            break
        if day in candidates:
            ranked = candidates[day]
            desired_keys = {c.key for c in ranked[:POSITIONS]}
            for base, p in list(positions.items()):
                if p.c.key not in desired_keys:
                    close(base, day, "rebalance")
            for c in ranked:
                if len(positions) >= POSITIONS:
                    break
                if c.base in positions:
                    continue
                if balance[c.sv] - used[c.sv] < LEG_NOTIONAL or balance[c.lv] - used[c.lv] < LEG_NOTIONAL:
                    continue
                seq += 1
                maker, taker, fees = entry_fee(c, LEG_NOTIONAL)
                for v, fee in fees.items():
                    balance[v] -= fee
                    agg_fees += fee
                used[c.sv] += LEG_NOTIONAL
                used[c.lv] += LEG_NOTIONAL
                row = {
                    "entry_id": f"{start.year}-{seq:04d}", "period": label,
                    "entry_day": day.isoformat(), "close_day": "", "close_reason": "",
                    "base": c.base, "spread": c.spread, "raw_spread": c.raw_spread,
                    "fresh": c.fresh, "thin": c.thin, "rank": c.rank,
                    "pair_type": c.pair_type, "sv": c.sv, "sv_sym": c.sv_sym,
                    "lv": c.lv, "lv_sym": c.lv_sym, "notional": LEG_NOTIONAL,
                    "entry_maker": maker, "entry_taker": taker,
                }
                rows.append(row)
                positions[c.base] = Position(c, row)
        day += timedelta(days=1)
    net = sum(balance.values()) - sum(START_ALLOC.values())
    print(label, "opened", seq, "funding", round(agg_funding, 2),
          "fees", round(agg_fees, 2), "net", round(net, 2))


def main():
    candidates, funding = load()
    rows = []
    for start, end_excl in PERIODS:
        simulate(candidates, funding, start, end_excl, rows)
    fields = list(rows[0])
    with OUT.open("w", newline="") as f:
        w = csv.DictWriter(f, fields)
        w.writeheader()
        w.writerows(rows)
    print("wrote", OUT, len(rows))


if __name__ == "__main__":
    main()
