#!/usr/bin/env python3
"""No-lookahead XVF gross-leverage and venue-capital sensitivity study.

Research-only Codex artifact.  It consumes the strict replay exports but does not alter the
repository.  "Gross leverage" scales every leg's notional while assuming the exchange leverage
setting scales by the same amount, so initial margin per leg remains USD 112.50.  Venue equity is
isolated and new entries must preserve the requested free-initial-margin reserve.

This is not a liquidation engine: it has no mark-price/basis path, tiered maintenance margin,
portfolio offsets, or venue-specific liquidation rules.  The reported stress buffers are simple
accounting sensitivities for a 0.5% illustrative maintenance-margin ratio.
"""

from __future__ import annotations

import csv
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path


VENUES = ("binance", "bybit", "hyperliquid")
POSITIONS = 20
BASE_MARGIN_PER_LEG = 112.50
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
MMR = 0.005  # illustrative only, intentionally not claimed as an exchange schedule

ARTIFACT_DIR = Path(__file__).resolve().parent.parent
CANDIDATES_PATH = ARTIFACT_DIR / "generated/candidates_production_like.csv"
FUNDING_PATH = ARTIFACT_DIR / "generated/funding_cutoff_daily.csv"


@dataclass(frozen=True)
class Candidate:
    cutoff: date
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
        return self.base, self.sv, self.sv_sym, self.lv, self.lv_sym


@dataclass
class Position:
    candidate: Candidate
    notional: float


def parse_date(value: str) -> date:
    return date.fromisoformat(value[:10])


def load_inputs():
    candidates = defaultdict(list)
    with CANDIDATES_PATH.open(newline="") as handle:
        for row in csv.DictReader(handle):
            cutoff = parse_date(row["w"])
            candidates[cutoff].append(Candidate(
                cutoff, row["base"], float(row["spread"]), float(row["raw_spread"]),
                row["sv"], row["sv_sym"], row["lv"], row["lv_sym"],
                row["pair_type"], int(row["rk"]),
            ))
    for rows in candidates.values():
        rows.sort(key=lambda c: c.rank)

    funding = {}
    with FUNDING_PATH.open(newline="") as handle:
        for row in csv.DictReader(handle):
            funding[(row["venue"], row["venue_symbol"], parse_date(row["d"]))] = float(row["rate_sum"])
    return candidates, funding


def entry_fee_rates(c: Candidate):
    maker = c.sv if VENUE_DEPTH[c.sv] < VENUE_DEPTH[c.lv] else c.lv
    taker = c.lv if maker == c.sv else c.sv
    return {maker: MAKER_BPS[maker], taker: TAKER_BPS[taker]}


def used_margin(positions):
    used = {v: 0.0 for v in VENUES}
    for p in positions.values():
        used[p.candidate.sv] += BASE_MARGIN_PER_LEG
        used[p.candidate.lv] += BASE_MARGIN_PER_LEG
    return used


def gross_by_venue(positions):
    gross = {v: 0.0 for v in VENUES}
    for p in positions.values():
        gross[p.candidate.sv] += p.notional
        gross[p.candidate.lv] += p.notional
    return gross


def transfer_conservatively(day, ranked, positions, balance, reserve, last_transfer):
    """One capped, infrequent replenishment of a depleted but still demanded venue.

    A venue qualifies only below 20% of total equity while at least ten of today's desired top-20
    legs need it.  It is replenished only toward 25%, never all the way to equal.  A donor must be
    above 35% and retain both that share and the equity required to keep current margin inside the
    same reserve.  The counterfactual has a 60-day cooldown, USD 225 maximum principal per event,
    and a USD 1 donor withdrawal cost.  This avoids chasing every short-lived target-weight change.
    """
    if last_transfer is not None and (day - last_transfer).days < 60:
        return 0.0, 0.0, last_transfer, None

    demand = {v: 0 for v in VENUES}
    for c in ranked[:POSITIONS]:
        demand[c.sv] += 1
        demand[c.lv] += 1
    total_demand = sum(demand.values())
    total_equity = sum(balance.values())
    target = {v: total_equity * demand[v] / total_demand for v in VENUES}
    recipients = [
        v for v in VENUES
        if balance[v] < 0.20 * total_equity and demand[v] >= 10
    ]
    if not recipients:
        return 0.0, 0.0, last_transfer, target
    recipient = min(recipients, key=lambda v: balance[v] / max(demand[v], 1))
    recipient_need = max(0.25 * total_equity - balance[recipient], 0.0)

    used = used_margin(positions)
    donor_capacity = {}
    for v in VENUES:
        reserve_floor = used[v] / (1.0 - reserve)
        safe_floor = max(0.35 * total_equity, reserve_floor)
        donor_capacity[v] = (
            max(balance[v] - safe_floor - 1.0, 0.0)
            if v != recipient and balance[v] > 0.35 * total_equity else 0.0
        )
    total_capacity = sum(donor_capacity.values())
    amount = min(225.0, total_capacity, recipient_need)
    if amount <= 1e-9:
        return 0.0, 0.0, last_transfer, target

    # To stay conservative, use the single largest donor and largest recipient.  The fee is one
    # actual withdrawal rather than an optimistic free internal rebalance.
    donor = max(VENUES, key=lambda v: donor_capacity[v])
    amount = min(amount, donor_capacity[donor], recipient_need)
    if donor == recipient or amount <= 1e-9:
        return 0.0, 0.0, last_transfer, target
    balance[donor] -= amount + 1.0
    balance[recipient] += amount
    return amount, 1.0, day, target


def simulate(
    candidates, funding, start, end_excl, leverage, reserve, allocations,
    transfers=False, daily_reserve_guard=True,
):
    notional = BASE_MARGIN_PER_LEG * leverage
    start_equity = sum(allocations.values())
    balance = dict(allocations)
    positions = {}
    funding_by_venue = {v: 0.0 for v in VENUES}
    fees_by_venue = {v: 0.0 for v in VENUES}
    opened = closed = retained = capital_skips = 0
    transfer_events = 0
    transfer_principal = transfer_fees = 0.0
    last_transfer = None
    position_samples = []
    margin_util_samples = {v: [] for v in VENUES}
    gross_equity_samples = {v: [] for v in VENUES}
    max_margin_util = {v: 0.0 for v in VENUES}
    min_operational_shock = {v: float("inf") for v in VENUES}
    min_liq_shock = {v: float("inf") for v in VENUES}
    min_balance = dict(balance)
    reserve_breach_days = {v: 0 for v in VENUES}
    maintenance_breach_days = {v: 0 for v in VENUES}
    risk_guard_days = 0
    risk_guard_closes = 0

    def charge(v, amount):
        balance[v] -= amount
        fees_by_venue[v] += amount

    def close(base):
        nonlocal closed
        p = positions.pop(base)
        c = p.candidate
        charge(c.sv, p.notional * TAKER_BPS[c.sv] / 10000.0)
        charge(c.lv, p.notional * TAKER_BPS[c.lv] / 10000.0)
        closed += 1

    def enforce_daily_reserve():
        """Cross out weakest held pairs until every venue restores the reserve target.

        This is an intentionally conservative operational counterfactual.  It observes only the
        current cutoff, uses the opening signal as a weakest-first proxy, and may pay extra taker
        exits.  Real execution would ideally react to live margin and current edge instead.
        """
        nonlocal risk_guard_days, risk_guard_closes
        activated = False
        while positions:
            used = used_margin(positions)
            breached = [
                v for v in VENUES
                if used[v] > (1.0 - reserve) * balance[v] + 1e-9
            ]
            if not breached:
                break
            venue = max(
                breached,
                key=lambda v: used[v] / balance[v] if balance[v] > 0 else float("inf"),
            )
            eligible = [
                (base, p) for base, p in positions.items()
                if venue in (p.candidate.sv, p.candidate.lv)
            ]
            if not eligible:
                break
            base, _ = min(eligible, key=lambda item: item[1].candidate.spread)
            close(base)
            risk_guard_closes += 1
            activated = True
        if activated:
            risk_guard_days += 1

    def record_risk():
        used = used_margin(positions)
        gross = gross_by_venue(positions)
        for v in VENUES:
            min_balance[v] = min(min_balance[v], balance[v])
            if gross[v] <= 1e-12:
                continue
            margin_util = used[v] / balance[v] if balance[v] > 0 else float("inf")
            gross_equity = gross[v] / balance[v] if balance[v] > 0 else float("inf")
            max_margin_util[v] = max(max_margin_util[v], margin_util)
            # Adverse uniform local-mark move that consumes all equity above initial margin.
            operational = (balance[v] - used[v]) / gross[v]
            # Adverse local-mark move to an illustrative maintenance threshold.
            liquidation = (balance[v] - MMR * gross[v]) / gross[v]
            min_operational_shock[v] = min(min_operational_shock[v], operational)
            min_liq_shock[v] = min(min_liq_shock[v], liquidation)
            if margin_util > (1.0 - reserve) + 1e-9:
                reserve_breach_days[v] += 1
            if balance[v] < MMR * gross[v]:
                maintenance_breach_days[v] += 1

    day = start
    while day <= end_excl:
        for p in positions.values():
            c = p.candidate
            for venue, symbol, sign in (
                (c.sv, c.sv_sym, 1.0), (c.lv, c.lv_sym, -1.0)
            ):
                pnl = sign * funding.get((venue, symbol, day), 0.0) * p.notional
                balance[venue] += pnl
                funding_by_venue[venue] += pnl

        if daily_reserve_guard:
            enforce_daily_reserve()
        # Capture funding-driven reserve pressure at every daily cutoff, not only rebalance days.
        record_risk()

        if day == end_excl:
            for base in list(positions):
                close(base)
            break

        if day in candidates:
            ranked = candidates[day]
            desired_keys = {c.key for c in ranked[:POSITIONS]}
            for base, p in list(positions.items()):
                if p.candidate.key not in desired_keys:
                    close(base)
            retained += len(positions)

            if transfers:
                amount, fee, last_transfer, _ = transfer_conservatively(
                    day, ranked, positions, balance, reserve, last_transfer
                )
                if amount:
                    transfer_events += 1
                    transfer_principal += amount
                    transfer_fees += fee

            for c in ranked:
                if len(positions) >= POSITIONS:
                    break
                if c.base in positions:
                    continue
                used = used_margin(positions)
                rates = entry_fee_rates(c)
                ok = True
                for v in (c.sv, c.lv):
                    fee = notional * rates[v] / 10000.0
                    post_balance = balance[v] - fee
                    post_used = used[v] + BASE_MARGIN_PER_LEG
                    if post_balance <= 0.0 or post_used > (1.0 - reserve) * post_balance + 1e-9:
                        ok = False
                        break
                if not ok:
                    capital_skips += 1
                    continue
                for v, bps in rates.items():
                    charge(v, notional * bps / 10000.0)
                positions[c.base] = Position(c, notional)
                opened += 1

            position_samples.append(len(positions))
            used = used_margin(positions)
            gross = gross_by_venue(positions)
            for v in VENUES:
                margin_util_samples[v].append(used[v] / balance[v] if balance[v] > 0 else float("inf"))
                gross_equity_samples[v].append(gross[v] / balance[v] if balance[v] > 0 else float("inf"))
            record_risk()

        day += timedelta(days=1)

    end_equity = sum(balance.values())
    return {
        "start": start, "end": end_excl, "leverage": leverage, "reserve": reserve,
        "allocation": allocations, "transfers": transfers,
        "start_equity": start_equity, "end_equity": end_equity,
        "net": end_equity - start_equity,
        "return_pct": 100.0 * (end_equity - start_equity) / start_equity,
        "funding": sum(funding_by_venue.values()), "fees": sum(fees_by_venue.values()),
        "opened": opened, "closed": closed, "retained": retained,
        "capital_skips": capital_skips,
        "avg_positions": sum(position_samples) / len(position_samples),
        "avg_margin_util": {v: sum(margin_util_samples[v]) / len(margin_util_samples[v]) for v in VENUES},
        "max_margin_util": max_margin_util,
        "avg_gross_equity": {v: sum(gross_equity_samples[v]) / len(gross_equity_samples[v]) for v in VENUES},
        "min_operational_shock": min_operational_shock,
        "min_liq_shock": min_liq_shock,
        "reserve_breach_days": reserve_breach_days,
        "maintenance_breach_days": maintenance_breach_days,
        "end_balance": balance, "min_balance": min_balance,
        "funding_by_venue": funding_by_venue, "fees_by_venue": fees_by_venue,
        "transfer_events": transfer_events, "transfer_principal": transfer_principal,
        "transfer_fees": transfer_fees,
        "risk_guard_days": risk_guard_days, "risk_guard_closes": risk_guard_closes,
    }


def fmt(r, name):
    util_avg = "/".join(f"{100*r['avg_margin_util'][v]:.0f}" for v in VENUES)
    util_max = "/".join(f"{100*r['max_margin_util'][v]:.0f}" for v in VENUES)
    op = min(r["min_operational_shock"].values()) * 100
    liq = min(r["min_liq_shock"].values()) * 100
    breaches = sum(r["reserve_breach_days"].values())
    return (
        f"{name:18} L={r['leverage']:.2f} R={100*r['reserve']:2.0f}% "
        f"ret={r['return_pct']:+6.2f}% net={r['net']:+7.2f} fund={r['funding']:+7.2f} "
        f"fee={r['fees']:6.2f} P={r['avg_positions']:5.2f} skip={r['capital_skips']:5d} "
        f"avgU(B/Y/H)={util_avg}% maxU={util_max}% rb={breaches:3d} "
        f"shock(init/liq)={op:5.1f}/{liq:5.1f}% "
        f"guard={r['risk_guard_days']}/{r['risk_guard_closes']} "
        f"xfer={r['transfer_events']}/${r['transfer_principal']:.0f}"
    )


def main():
    candidates, funding = load_inputs()
    periods = (
        (date(2024, 8, 21), date(2025, 8, 21), "prior"),
        (date(2025, 8, 21), date(2026, 8, 21), "recent"),
    )
    allocations = {
        "equal": {v: 1500.0 for v in VENUES},
        "40/20/40": {"binance": 1800.0, "bybit": 900.0, "hyperliquid": 1800.0},
        "35/25/40": {"binance": 1575.0, "bybit": 1125.0, "hyperliquid": 1800.0},
    }

    print("Model: notional=$112.50*L; exchange leverage=L; initial margin=$112.50/leg; "
          "maker+taker entry; taker exit; isolated venue equity")
    for start, end, label in periods:
        print(f"\n=== {label} [{start},{end}) equal allocation ===")
        for reserve in (0.15, 0.25):
            for leverage in (1.0, 1.25, 1.5, 2.0):
                print(fmt(simulate(candidates, funding, start, end, leverage, reserve,
                                   allocations["equal"], False), "equal"))

    print("\n=== allocation sensitivity at 1.25x and 25% reserve ===")
    for start, end, label in periods:
        print(f"-- {label}")
        for name, allocation in allocations.items():
            print(fmt(simulate(candidates, funding, start, end, 1.25, 0.25,
                               allocation, False), name))

    print("\n=== conservative target-driven transfer sensitivity (equal start) ===")
    for start, end, label in periods:
        print(f"-- {label}")
        for leverage in (1.0, 1.25, 1.5):
            print(fmt(simulate(candidates, funding, start, end, leverage, 0.25,
                               allocations["equal"], True), "equal+xfer"))


if __name__ == "__main__":
    main()
