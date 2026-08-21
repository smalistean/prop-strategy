#!/usr/bin/env python3
"""Strict no-lookahead XVF replay with isolated capital/execution counterfactuals.

This Codex artifact is isolated from production source. Its baseline preserves the strict replay's
rules:

* fixed USD 112.50 per leg (USD 4,500 / 20 pairs / 2 legs);
* a uniform three-day candidate/reconcile schedule supplied by the strict export;
* exact pairs in the desired top 20 are retained, all other held pairs are crossed out;
* the entry walk uses every exported rank for capital backfill;
* funding visible at cutoff D belongs to the pre-decision book; and
* each annual slice is flat after final all-taker liquidation.

Optional policies are applied only at the current cutoff and never inspect future rows:

* a 30-day cooldown transfer toward today's projected venue requirements, limited to free
  collateral and charged USD 1 per donor withdrawal;
* a three-day round-trip fee break-even test for new entries only; and
* a conservative all-taker entry with an additional 8.5 bp pair-level basis drag.
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Iterable


VENUES = ("binance", "bybit", "hyperliquid")
POSITIONS = 20
LEG_NOTIONAL = 112.50
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
VENUE_DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
ARTIFACT_DIR = Path(__file__).resolve().parent


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
    def key(self) -> tuple[str, str, str, str, str]:
        return self.base, self.sv, self.sv_sym, self.lv, self.lv_sym


@dataclass
class Position:
    candidate: Candidate
    notional: float = LEG_NOTIONAL


@dataclass(frozen=True)
class Policy:
    name: str
    transfers: bool = False
    filter_mode: str = "none"  # none | maker_roundtrip | taker_roundtrip_basis
    entry_mode: str = "maker_taker"  # maker_taker | all_taker
    basis_bps: float = 0.0
    transfer_cooldown_days: int = 30
    transfer_trigger_usd: float = LEG_NOTIONAL
    transfer_fee_usd: float = 1.0


@dataclass
class Result:
    policy: str
    start: date
    end_excl: date
    start_equity: float
    end_equity: float
    funding: float
    trading_fees: float
    transfer_fees: float
    basis_drag: float
    opened: int
    closed: int
    retained: int
    capital_skips: int
    cost_skips: int
    transfer_events: int
    withdrawals: int
    transferred: float
    avg_positions: float
    avg_utilization: float
    full_book_pct: float
    venue_avg_utilization: dict[str, float]
    venue_max_utilization: dict[str, float]
    max_used: dict[str, float]
    min_free: dict[str, float]
    end_balance: dict[str, float]
    funding_by_venue: dict[str, float]
    trading_fees_by_venue: dict[str, float]
    transfer_fees_by_venue: dict[str, float]
    transfer_flow: dict[tuple[str, str], float]
    missing_leg_days: dict[str, int]
    leg_days: dict[str, int]

    @property
    def net(self) -> float:
        return self.end_equity - self.start_equity

    @property
    def return_pct(self) -> float:
        return 100.0 * self.net / self.start_equity


def parse_date(value: str) -> date:
    return date.fromisoformat(value[:10])


def load_inputs(candidates_path: Path, funding_path: Path):
    candidates: dict[date, list[Candidate]] = defaultdict(list)
    with candidates_path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            cutoff = parse_date(row["w"])
            candidates[cutoff].append(Candidate(
                cutoff=cutoff,
                base=row["base"],
                spread=float(row["spread"]),
                raw_spread=float(row["raw_spread"]),
                sv=row["sv"],
                sv_sym=row["sv_sym"],
                lv=row["lv"],
                lv_sym=row["lv_sym"],
                pair_type=row["pair_type"],
                rank=int(row["rk"]),
            ))
    for cutoff, rows in candidates.items():
        rows.sort(key=lambda candidate: candidate.rank)
        assert all(candidate.cutoff == cutoff for candidate in rows)
        assert [candidate.rank for candidate in rows] == sorted(candidate.rank for candidate in rows)

    funding: dict[tuple[str, str, date], float] = {}
    with funding_path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            funding[(row["venue"], row["venue_symbol"], parse_date(row["d"]))] = float(row["rate_sum"])
    return candidates, funding


def maker_taker(candidate: Candidate) -> tuple[str, str]:
    if VENUE_DEPTH[candidate.sv] < VENUE_DEPTH[candidate.lv]:
        return candidate.sv, candidate.lv
    return candidate.lv, candidate.sv


def entry_fee_rates(candidate: Candidate, entry_mode: str) -> dict[str, float]:
    if entry_mode == "all_taker":
        return {candidate.sv: TAKER_BPS[candidate.sv], candidate.lv: TAKER_BPS[candidate.lv]}
    if entry_mode != "maker_taker":
        raise ValueError(f"unknown entry mode: {entry_mode}")
    maker, taker = maker_taker(candidate)
    return {maker: MAKER_BPS[maker], taker: TAKER_BPS[taker]}


def roundtrip_bps(candidate: Candidate, policy: Policy) -> float:
    if policy.filter_mode == "none":
        return 0.0
    if policy.filter_mode == "maker_roundtrip":
        entry = sum(entry_fee_rates(candidate, "maker_taker").values())
        basis = 0.0
    elif policy.filter_mode == "taker_roundtrip_basis":
        entry = TAKER_BPS[candidate.sv] + TAKER_BPS[candidate.lv]
        basis = policy.basis_bps
    else:
        raise ValueError(f"unknown filter mode: {policy.filter_mode}")
    exit_bps = TAKER_BPS[candidate.sv] + TAKER_BPS[candidate.lv]
    return entry + exit_bps + basis


def break_even_annual_pct(candidate: Candidate, policy: Policy) -> float:
    # Candidate spread is percent/year.  A three-day gross spread must cover pair-level bps.
    return roundtrip_bps(candidate, policy) * 365.0 / (3.0 * 100.0)


def eligible_new_entry(candidate: Candidate, policy: Policy) -> bool:
    return candidate.spread >= break_even_annual_pct(candidate, policy)


def used_by_venue(positions: dict[str, Position]) -> dict[str, float]:
    used = {venue: 0.0 for venue in VENUES}
    for position in positions.values():
        used[position.candidate.sv] += position.notional
        used[position.candidate.lv] += position.notional
    return used


def projected_book(
    ranked: list[Candidate], positions: dict[str, Position], policy: Policy
) -> list[Candidate]:
    """Construct today's filter-aware target for transfer weights using only current rows.

    Exact retained top-20 candidates are always included because their entry cost is sunk.  Other
    candidates must pass the selected new-entry filter.  Full ranks may fill filter-created gaps.
    Capital availability is deliberately not consulted: this is the demand that the transfer is
    trying to support, rather than a circular projection of today's stranded allocation.
    """
    desired_keys = {candidate.key for candidate in ranked[:POSITIONS]}
    retained_keys = {
        position.candidate.key
        for position in positions.values()
        if position.candidate.key in desired_keys
    }
    projected: list[Candidate] = []
    bases: set[str] = set()
    for candidate in ranked:
        if len(projected) >= POSITIONS:
            break
        if candidate.base in bases:
            continue
        if candidate.key not in retained_keys and not eligible_new_entry(candidate, policy):
            continue
        projected.append(candidate)
        bases.add(candidate.base)
    return projected


def transfer_toward_projected_requirements(
    day: date,
    ranked: list[Candidate],
    positions: dict[str, Position],
    balance: dict[str, float],
    policy: Policy,
) -> tuple[float, int, dict[tuple[str, str], float], dict[str, float]]:
    """Move only unencumbered collateral toward today's projected leg mix.

    Since fees have already reduced total equity below nominal collateral, targets are scaled to
    current total equity rather than pretending USD 4,500 remains available.  A donor can never
    transfer collateral backing a retained leg; its USD 1 withdrawal fee is reserved first.
    """
    del day  # Documents that the caller supplies the current cutoff; no other date is read here.
    projection = projected_book(ranked, positions, policy)
    requirement = {venue: 0.0 for venue in VENUES}
    for candidate in projection:
        requirement[candidate.sv] += LEG_NOTIONAL
        requirement[candidate.lv] += LEG_NOTIONAL
    total_requirement = sum(requirement.values())
    if total_requirement <= 0.0:
        return 0.0, 0, {}, {venue: balance[venue] for venue in VENUES}

    total_equity = sum(balance.values())
    target = {
        venue: total_equity * requirement[venue] / total_requirement
        for venue in VENUES
    }
    deficits = {venue: max(target[venue] - balance[venue], 0.0) for venue in VENUES}
    if max(deficits.values(), default=0.0) + 1e-9 < policy.transfer_trigger_usd:
        return 0.0, 0, {}, target

    used = used_by_venue(positions)
    capacity = {}
    for venue in VENUES:
        # The withdrawal fee is part of the donor-side cash movement, so reserve it before
        # measuring surplus against both the target and currently encumbered collateral.
        target_surplus = max(balance[venue] - target[venue] - policy.transfer_fee_usd, 0.0)
        fee_reserved_free = max(balance[venue] - used[venue] - policy.transfer_fee_usd, 0.0)
        capacity[venue] = min(target_surplus, fee_reserved_free)

    amount = min(sum(capacity.values()), sum(deficits.values()))
    if amount <= 1e-9:
        return 0.0, 0, {}, target

    donor_total = sum(capacity.values())
    recipient_total = sum(deficits.values())
    donor_amount = {
        venue: amount * capacity[venue] / donor_total
        for venue in VENUES if capacity[venue] > 1e-9
    }
    recipient_amount = {
        venue: amount * deficits[venue] / recipient_total
        for venue in VENUES if deficits[venue] > 1e-9
    }

    # Apply each donor withdrawal and distribute it across recipients in the same proportions.
    flow: dict[tuple[str, str], float] = defaultdict(float)
    for donor, taken in donor_amount.items():
        balance[donor] -= taken + policy.transfer_fee_usd
        for recipient, received_total in recipient_amount.items():
            flowed = taken * received_total / amount
            balance[recipient] += flowed
            flow[(donor, recipient)] += flowed
        assert balance[donor] + 1e-7 >= used[donor], (donor, balance[donor], used[donor])
    return amount, len(donor_amount), dict(flow), target


def simulate(
    candidates: dict[date, list[Candidate]],
    funding: dict[tuple[str, str, date], float],
    start: date,
    end_excl: date,
    policy: Policy,
    allocations: dict[str, float] | None = None,
) -> Result:
    allocations = allocations or {venue: 1500.0 for venue in VENUES}
    start_equity = sum(allocations.values())
    assert abs(LEG_NOTIONAL - start_equity / (POSITIONS * 2.0)) < 1e-9

    balance = dict(allocations)
    positions: dict[str, Position] = {}
    funding_by_venue = {venue: 0.0 for venue in VENUES}
    trading_fees_by_venue = {venue: 0.0 for venue in VENUES}
    transfer_fees_by_venue = {venue: 0.0 for venue in VENUES}
    missing_leg_days = {venue: 0 for venue in VENUES}
    leg_days = {venue: 0 for venue in VENUES}
    max_used = {venue: 0.0 for venue in VENUES}
    min_free = dict(allocations)
    basis_drag = 0.0
    opened = closed = retained = capital_skips = cost_skips = 0
    transfer_events = withdrawals = 0
    transferred = 0.0
    transfer_flow: dict[tuple[str, str], float] = defaultdict(float)
    last_transfer: date | None = None
    slot_samples: list[int] = []
    aggregate_utilization_samples: list[float] = []
    venue_utilization_samples: dict[str, list[float]] = {venue: [] for venue in VENUES}

    def charge_trading_fee(venue: str, amount: float) -> None:
        balance[venue] -= amount
        trading_fees_by_venue[venue] += amount

    def close_position(base: str) -> None:
        nonlocal closed
        position = positions.pop(base)
        candidate = position.candidate
        charge_trading_fee(candidate.sv, position.notional * TAKER_BPS[candidate.sv] / 10_000.0)
        charge_trading_fee(candidate.lv, position.notional * TAKER_BPS[candidate.lv] / 10_000.0)
        closed += 1

    day = start
    while day <= end_excl:
        # Only the already-open book receives events exposed by this cutoff.  Positions opened
        # below first receive a funding row at a later cutoff, preventing signal/outcome overlap.
        for position in positions.values():
            candidate = position.candidate
            for venue, symbol, sign in (
                (candidate.sv, candidate.sv_sym, 1.0),
                (candidate.lv, candidate.lv_sym, -1.0),
            ):
                leg_days[venue] += 1
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

            # Exact top-20 pairs persist.  Changed direction/venue/contract and rank drops exit.
            for base, position in list(positions.items()):
                if position.candidate.key not in desired_keys:
                    close_position(base)
            retained += len(positions)

            # Transfer after genuine exits free their collateral and before new entries need it.
            cooldown_ok = (
                last_transfer is None
                or (day - last_transfer).days >= policy.transfer_cooldown_days
            )
            if policy.transfers and cooldown_ok:
                amount, donor_count, flows, _target = transfer_toward_projected_requirements(
                    day, ranked, positions, balance, policy
                )
                if amount > 1e-9:
                    transferred += amount
                    transfer_events += 1
                    withdrawals += donor_count
                    last_transfer = day
                    for (donor, recipient), value in flows.items():
                        transfer_flow[(donor, recipient)] += value
                    # The helper reserves/charges exactly one fee per donor.
                    donors = {donor for donor, _recipient in flows}
                    for donor in donors:
                        transfer_fees_by_venue[donor] += policy.transfer_fee_usd

            # Full-rank production entry walk; filters affect only genuinely new entries.
            for candidate in ranked:
                if len(positions) >= POSITIONS:
                    break
                if candidate.base in positions:
                    continue
                if not eligible_new_entry(candidate, policy):
                    cost_skips += 1
                    continue
                used = used_by_venue(positions)
                if (
                    balance[candidate.sv] - used[candidate.sv] < LEG_NOTIONAL
                    or balance[candidate.lv] - used[candidate.lv] < LEG_NOTIONAL
                ):
                    capital_skips += 1
                    continue

                for venue, fee_bps in entry_fee_rates(candidate, policy.entry_mode).items():
                    charge_trading_fee(venue, LEG_NOTIONAL * fee_bps / 10_000.0)
                if policy.basis_bps:
                    # Pair-level drag: one-leg notional * 8.5 bp, split across the two venues.
                    drag = LEG_NOTIONAL * policy.basis_bps / 10_000.0
                    balance[candidate.sv] -= drag / 2.0
                    balance[candidate.lv] -= drag / 2.0
                    basis_drag += drag
                positions[candidate.base] = Position(candidate)
                opened += 1

            used = used_by_venue(positions)
            slot_samples.append(len(positions))
            aggregate_utilization_samples.append(sum(used.values()) / sum(balance.values()))
            for venue in VENUES:
                # A deliberately drained venue with neither equity nor positions is 0% utilized,
                # not infinity.  Positive used collateral always has positive equity because the
                # transfer helper cannot move encumbered funds.
                ratio = 0.0 if used[venue] <= 1e-9 else used[venue] / balance[venue]
                venue_utilization_samples[venue].append(ratio)
                max_used[venue] = max(max_used[venue], used[venue])
                min_free[venue] = min(min_free[venue], balance[venue] - used[venue])

        day += timedelta(days=1)

    # Reconcile the accounting identity.  Transfer principal cancels; only its fees reduce equity.
    end_equity = sum(balance.values())
    funding_total = sum(funding_by_venue.values())
    trading_fee_total = sum(trading_fees_by_venue.values())
    transfer_fee_total = sum(transfer_fees_by_venue.values())
    expected_end = start_equity + funding_total - trading_fee_total - transfer_fee_total - basis_drag
    assert abs(end_equity - expected_end) < 1e-6, (end_equity, expected_end)
    assert opened == closed, (opened, closed)

    samples = len(slot_samples)
    return Result(
        policy=policy.name,
        start=start,
        end_excl=end_excl,
        start_equity=start_equity,
        end_equity=end_equity,
        funding=funding_total,
        trading_fees=trading_fee_total,
        transfer_fees=transfer_fee_total,
        basis_drag=basis_drag,
        opened=opened,
        closed=closed,
        retained=retained,
        capital_skips=capital_skips,
        cost_skips=cost_skips,
        transfer_events=transfer_events,
        withdrawals=withdrawals,
        transferred=transferred,
        avg_positions=sum(slot_samples) / samples if samples else 0.0,
        avg_utilization=sum(aggregate_utilization_samples) / samples if samples else 0.0,
        full_book_pct=100.0 * sum(value == POSITIONS for value in slot_samples) / samples if samples else 0.0,
        venue_avg_utilization={
            venue: sum(venue_utilization_samples[venue]) / samples if samples else 0.0
            for venue in VENUES
        },
        venue_max_utilization={
            venue: max(venue_utilization_samples[venue], default=0.0)
            for venue in VENUES
        },
        max_used=max_used,
        min_free=min_free,
        end_balance=balance,
        funding_by_venue=funding_by_venue,
        trading_fees_by_venue=trading_fees_by_venue,
        transfer_fees_by_venue=transfer_fees_by_venue,
        transfer_flow=dict(transfer_flow),
        missing_leg_days=missing_leg_days,
        leg_days=leg_days,
    )


POLICIES = (
    Policy("A_baseline"),
    Policy("B_transfer_only", transfers=True),
    Policy("C_maker_fee_filter", filter_mode="maker_roundtrip"),
    Policy("D_filter_and_transfer", transfers=True, filter_mode="maker_roundtrip"),
    Policy(
        "E_all_taker_plus_basis",
        filter_mode="taker_roundtrip_basis",
        entry_mode="all_taker",
        basis_bps=8.5,
    ),
    Policy(
        "E_plus_transfer_sensitivity",
        transfers=True,
        filter_mode="taker_roundtrip_basis",
        entry_mode="all_taker",
        basis_bps=8.5,
    ),
)

PERIODS = (
    (date(2024, 8, 21), date(2025, 8, 21), "prior"),
    (date(2025, 8, 21), date(2026, 8, 21), "recent"),
)


def fmt_result(result: Result, period: str) -> str:
    return (
        f"{result.policy:29} {period:6} net={result.net:+8.2f} "
        f"ret={result.return_pct:+6.2f}% funding={result.funding:+8.2f} "
        f"trade_fee={result.trading_fees:7.2f} transfer_fee={result.transfer_fees:4.0f} "
        f"basis={result.basis_drag:6.2f} open={result.opened:4d} "
        f"retain={result.retained:4d} cap_skip={result.capital_skips:5d} "
        f"cost_skip={result.cost_skips:5d} avgP={result.avg_positions:5.2f} "
        f"util={100*result.avg_utilization:5.1f}% full={result.full_book_pct:5.1f}% "
        f"xfer={result.transfer_events:2d}/{result.withdrawals:2d}/${result.transferred:7.2f}"
    )


def print_details(result: Result) -> None:
    print("  end balance: " + ", ".join(f"{v}={result.end_balance[v]:.2f}" for v in VENUES))
    print("  avg venue utilization: " + ", ".join(
        f"{v}={100*result.venue_avg_utilization[v]:.1f}%" for v in VENUES
    ))
    print("  max venue utilization: " + ", ".join(
        f"{v}={100*result.venue_max_utilization[v]:.1f}%" for v in VENUES
    ))
    print("  missing held leg-days: " + ", ".join(
        f"{v}={result.missing_leg_days[v]}/{result.leg_days[v]}" for v in VENUES
    ))
    if result.transfer_flow:
        print("  transfer flow: " + ", ".join(
            f"{donor}->{recipient}=${amount:.2f}"
            for (donor, recipient), amount in sorted(result.transfer_flow.items())
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
    parser.add_argument("--policy", choices=[policy.name for policy in POLICIES])
    parser.add_argument("--details", action="store_true")
    args = parser.parse_args()

    candidates, funding = load_inputs(args.candidates, args.funding)
    policies: Iterable[Policy] = (
        [next(policy for policy in POLICIES if policy.name == args.policy)]
        if args.policy else POLICIES
    )
    print("fixed leg=$112.50; 20 slots; 3-day cutoff/reconcile; 30-day transfer cooldown; "
          "$112.50 projected-deficit trigger; $1/donor withdrawal")
    for policy in policies:
        for start, end_excl, label in PERIODS:
            result = simulate(candidates, funding, start, end_excl, policy)
            print(fmt_result(result, label))
            if args.details:
                print_details(result)


if __name__ == "__main__":
    main()
