#!/usr/bin/env python3
"""Compare XVF entry routing policies on the strict Codex two-year replay.

Only entry execution changes between policies. All closes remain immediately executable taker
orders on both venues. Candidates, funding timing, fixed leg size, three-day reconciliations,
exact-pair retention, rank backfill, capital constraints, and flat annual boundaries match the
strict production-like replay artifact.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path


VENUES = ("binance", "bybit", "hyperliquid")
POSITIONS = 20
LEG_NOTIONAL = 112.50
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
ARTIFACT_DIR = Path(__file__).resolve().parent.parent
COMBO_ORDER = (
    ("binance", "bybit"),
    ("binance", "hyperliquid"),
    ("bybit", "hyperliquid"),
)


@dataclass(frozen=True)
class Candidate:
    base: str
    spread: float
    raw_spread: float
    sv: str
    sv_sym: str
    lv: str
    lv_sym: str
    rank: int

    @property
    def key(self) -> tuple[str, str, str, str, str]:
        return self.base, self.sv, self.sv_sym, self.lv, self.lv_sym

    @property
    def combo(self) -> tuple[str, str]:
        venues = frozenset((self.sv, self.lv))
        for combo in COMBO_ORDER:
            if venues == frozenset(combo):
                return combo
        raise AssertionError((self.sv, self.lv))


@dataclass
class Position:
    candidate: Candidate
    notional: float = LEG_NOTIONAL


@dataclass(frozen=True)
class RoutingPolicy:
    name: str
    description: str


POLICIES = (
    RoutingPolicy("current_depth", "maker on thinner venue, taker on deeper venue"),
    RoutingPolicy("bybit_maker", "Bybit maker whenever present; current depth rule otherwise"),
    RoutingPolicy("fee_min_one_maker", "minimum total entry fee with exactly one maker leg"),
    RoutingPolicy("lower_maker_rate", "literal lower maker-rate venue; ignores other leg's taker fee"),
    RoutingPolicy("both_maker", "both entry legs maker (non-atomic theoretical fill)"),
    RoutingPolicy("all_taker", "both entry legs taker"),
)


def parse_date(value: str) -> date:
    return date.fromisoformat(value[:10])


def load_inputs(candidates_path: Path, funding_path: Path):
    candidates: dict[date, list[Candidate]] = defaultdict(list)
    with candidates_path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            cutoff = parse_date(row["w"])
            candidates[cutoff].append(Candidate(
                base=row["base"],
                spread=float(row["spread"]),
                raw_spread=float(row["raw_spread"]),
                sv=row["sv"],
                sv_sym=row["sv_sym"],
                lv=row["lv"],
                lv_sym=row["lv_sym"],
                rank=int(row["rk"]),
            ))
    for rows in candidates.values():
        rows.sort(key=lambda candidate: candidate.rank)

    funding: dict[tuple[str, str, date], float] = {}
    with funding_path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            key = row["venue"], row["venue_symbol"], parse_date(row["d"])
            funding[key] = float(row["rate_sum"])
    return candidates, funding


def depth_maker(candidate: Candidate) -> str:
    return min((candidate.sv, candidate.lv), key=lambda venue: VENUE_DEPTH[venue])


def fee_min_maker(candidate: Candidate) -> str:
    a, b = candidate.sv, candidate.lv
    cost_a_maker = MAKER_BPS[a] + TAKER_BPS[b]
    cost_b_maker = MAKER_BPS[b] + TAKER_BPS[a]
    if cost_a_maker < cost_b_maker:
        return a
    if cost_b_maker < cost_a_maker:
        return b
    return depth_maker(candidate)


def entry_rates(candidate: Candidate, policy: RoutingPolicy) -> dict[str, float]:
    a, b = candidate.sv, candidate.lv
    if policy.name == "both_maker":
        return {a: MAKER_BPS[a], b: MAKER_BPS[b]}
    if policy.name == "all_taker":
        return {a: TAKER_BPS[a], b: TAKER_BPS[b]}
    if policy.name == "current_depth":
        maker = depth_maker(candidate)
    elif policy.name == "bybit_maker":
        maker = "bybit" if "bybit" in (a, b) else depth_maker(candidate)
    elif policy.name == "fee_min_one_maker":
        maker = fee_min_maker(candidate)
    elif policy.name == "lower_maker_rate":
        maker = min((a, b), key=lambda venue: (MAKER_BPS[venue], VENUE_DEPTH[venue]))
    else:
        raise ValueError(policy.name)
    taker = b if maker == a else a
    return {maker: MAKER_BPS[maker], taker: TAKER_BPS[taker]}


def break_even_annual_pct(combo: tuple[str, str], policy: RoutingPolicy) -> float:
    candidate = Candidate("X", 0.0, 0.0, combo[0], "X", combo[1], "X", 0)
    roundtrip_bps = sum(entry_rates(candidate, policy).values()) + sum(
        TAKER_BPS[venue] for venue in combo
    )
    return roundtrip_bps * 365.0 / (3.0 * 100.0)


def simulate(candidates, funding, start: date, end_excl: date, policy: RoutingPolicy):
    allocations = {venue: 1500.0 for venue in VENUES}
    balance = dict(allocations)
    used = {venue: 0.0 for venue in VENUES}
    positions: dict[str, Position] = {}
    funding_by_venue = {venue: 0.0 for venue in VENUES}
    entry_fees_by_venue = {venue: 0.0 for venue in VENUES}
    exit_fees_by_venue = {venue: 0.0 for venue in VENUES}
    opened_by_combo: Counter[tuple[str, str]] = Counter()
    opened = closed = retained = capital_skips = 0
    position_samples: list[int] = []
    missing_leg_days = {venue: 0 for venue in VENUES}

    def charge(venue: str, amount: float, fee_bucket: dict[str, float]) -> None:
        balance[venue] -= amount
        fee_bucket[venue] += amount

    def close_position(base: str) -> None:
        nonlocal closed
        position = positions.pop(base)
        candidate = position.candidate
        for venue in (candidate.sv, candidate.lv):
            charge(venue, position.notional * TAKER_BPS[venue] / 10_000.0, exit_fees_by_venue)
            used[venue] -= position.notional
        closed += 1

    day = start
    while day <= end_excl:
        for position in positions.values():
            candidate = position.candidate
            for venue, symbol, sign in (
                (candidate.sv, candidate.sv_sym, 1.0),
                (candidate.lv, candidate.lv_sym, -1.0),
            ):
                key = venue, symbol, day
                if key not in funding:
                    missing_leg_days[venue] += 1
                pnl = sign * funding.get(key, 0.0) * position.notional
                balance[venue] += pnl
                funding_by_venue[venue] += pnl

        if day == end_excl:
            for base in list(positions):
                close_position(base)
            break

        if day in candidates:
            ranked = candidates[day]
            desired_keys = {candidate.key for candidate in ranked[:POSITIONS]}
            for base, position in list(positions.items()):
                if position.candidate.key not in desired_keys:
                    close_position(base)
            retained += len(positions)

            for candidate in ranked:
                if len(positions) >= POSITIONS:
                    break
                if candidate.base in positions:
                    continue
                if (balance[candidate.sv] - used[candidate.sv] < LEG_NOTIONAL
                        or balance[candidate.lv] - used[candidate.lv] < LEG_NOTIONAL):
                    capital_skips += 1
                    continue
                rates = entry_rates(candidate, policy)
                for venue, bps in rates.items():
                    charge(venue, LEG_NOTIONAL * bps / 10_000.0, entry_fees_by_venue)
                used[candidate.sv] += LEG_NOTIONAL
                used[candidate.lv] += LEG_NOTIONAL
                positions[candidate.base] = Position(candidate)
                opened_by_combo[candidate.combo] += 1
                opened += 1
            position_samples.append(len(positions))
        day += timedelta(days=1)

    entry_fees = sum(entry_fees_by_venue.values())
    exit_fees = sum(exit_fees_by_venue.values())
    funding_net = sum(funding_by_venue.values())
    total_start = sum(allocations.values())
    total_end = sum(balance.values())
    return {
        "policy": policy,
        "start": start,
        "end_excl": end_excl,
        "start_equity": total_start,
        "end_equity": total_end,
        "funding": funding_net,
        "entry_fees": entry_fees,
        "exit_fees": exit_fees,
        "total_fees": entry_fees + exit_fees,
        "net": total_end - total_start,
        "return_pct": 100.0 * (total_end / total_start - 1.0),
        "opened": opened,
        "closed": closed,
        "retained": retained,
        "capital_skips": capital_skips,
        "avg_positions": sum(position_samples) / len(position_samples),
        "opened_by_combo": opened_by_combo,
        "funding_by_venue": funding_by_venue,
        "entry_fees_by_venue": entry_fees_by_venue,
        "exit_fees_by_venue": exit_fees_by_venue,
        "end_balance": balance,
        "missing_leg_days": missing_leg_days,
    }


def print_result(result) -> None:
    policy = result["policy"]
    print(f"{policy.name}: {policy.description}")
    print(
        f"  funding {result['funding']:+.4f}; entry fees {result['entry_fees']:.4f}; "
        f"exit fees {result['exit_fees']:.4f}; net {result['net']:+.4f} "
        f"({result['return_pct']:+.4f}%)"
    )
    print(
        f"  opened/closed {result['opened']}/{result['closed']}; retained {result['retained']}; "
        f"capital skips {result['capital_skips']}; avg pairs {result['avg_positions']:.4f}"
    )
    print("  new pairs: " + ", ".join(
        f"{a}-{b}={result['opened_by_combo'][combo]}" for combo in COMBO_ORDER for a, b in (combo,)
    ))
    print("  break-even annual % (3d; entry policy + taker exits): " + ", ".join(
        f"{a}-{b}={break_even_annual_pct(combo, policy):.4f}%"
        for combo in COMBO_ORDER for a, b in (combo,)
    ))
    print("  entry fees by venue: " + ", ".join(
        f"{venue}={result['entry_fees_by_venue'][venue]:.4f}" for venue in VENUES
    ))
    print("  exit fees by venue: " + ", ".join(
        f"{venue}={result['exit_fees_by_venue'][venue]:.4f}" for venue in VENUES
    ))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--candidates", type=Path,
        default=ARTIFACT_DIR / "generated" / "candidates_production_like.csv",
    )
    parser.add_argument(
        "--funding", type=Path,
        default=ARTIFACT_DIR / "generated" / "funding_cutoff_daily.csv",
    )
    args = parser.parse_args()
    candidates, funding = load_inputs(args.candidates, args.funding)
    for start, end_excl in (
        (date(2024, 8, 21), date(2025, 8, 21)),
        (date(2025, 8, 21), date(2026, 8, 21)),
    ):
        print(f"\n=== [{start}, {end_excl}) ===")
        for policy in POLICIES:
            print_result(simulate(candidates, funding, start, end_excl, policy))


if __name__ == "__main__":
    main()
