#!/usr/bin/env python3
"""Read-only XVF capital counterfactual used on 2026-08-21.

This is deliberately outside the production source tree after creation.  It corrects four
structural differences in scripts/xvf-capital-simulation.py for research purposes:

* one uniform three-day target-book decision cadence;
* unchanged exact venue/side pairs are retained, rather than expired and reopened;
* the rank walk is uncapped and can backfill a capital-blocked candidate;
* each independent year is liquidated at its end instead of reporting partly charged open trades.

The candidate export feeding it is still provisional: it has the repository export's documented
calendar-week volume lookahead and its full-day-D signal does not match production's start-of-D
cutoff.  This program is a comparative capital study, not a deployable backtest.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path


VENUES = ("binance", "bybit", "hyperliquid")
MAKER_BPS = {"binance": 1.8, "bybit": 3.6, "hyperliquid": 1.8}
TAKER_BPS = {"binance": 4.5, "bybit": 10.0, "hyperliquid": 4.5}
DEPTH = {"hyperliquid": 1, "bybit": 2, "binance": 3}
ARTIFACT_DIR = Path(__file__).resolve().parent.parent


def parse_date(value: str) -> date:
    return date.fromisoformat(value[:10])


def candidate_key(candidate: dict) -> tuple[str, ...]:
    return (
        candidate["base"],
        candidate["sv"],
        candidate["sv_sym"],
        candidate["lv"],
        candidate["lv_sym"],
    )


def maker_taker(short_venue: str, long_venue: str) -> tuple[str, str]:
    if DEPTH[short_venue] < DEPTH[long_venue]:
        return short_venue, long_venue
    return long_venue, short_venue


@dataclass(frozen=True)
class Config:
    start: date
    end: date
    allocation: tuple[float, float, float] = (1500.0, 1500.0, 1500.0)
    positions: int = 20
    notional_scale: float = 1.0
    rank_cap: int | None = None
    cost_filter: str = "none"
    entry_mode: str = "maker"
    basis_bps: float = 0.0
    transfer_trigger_share: float | None = None
    transfer_cooldown_days: int = 30
    transfer_cost_per_withdrawal: float = 0.0


@dataclass
class Result:
    net: float
    funding: float
    fees: float
    basis: float
    opened: int
    closed: int
    retained_events: int
    capital_rejections: int
    cost_rejections: int
    rebalance_count: int
    average_slots: float
    full_book_count: int
    minimum_slots: int
    maximum_slots: int
    missing_leg_days: int
    total_leg_days: int
    missing_keys: Counter = field(default_factory=Counter)
    transfers: int = 0
    withdrawals: int = 0
    transferred: float = 0.0
    end_balance: dict[str, float] = field(default_factory=dict)
    funding_by_venue: dict[str, float] = field(default_factory=dict)
    fees_by_venue: dict[str, float] = field(default_factory=dict)


class Simulator:
    def __init__(self, candidates_path: Path, funding_path: Path):
        self.candidates: dict[date, list[dict]] = defaultdict(list)
        with candidates_path.open() as handle:
            for row in csv.DictReader(handle):
                row["spread"] = float(row["spread"])
                self.candidates[parse_date(row["w"])].append(row)
        for rows in self.candidates.values():
            rows.sort(key=lambda candidate: -candidate["spread"])

        self.funding: dict[tuple[str, str, date], float] = {}
        with funding_path.open() as handle:
            for row in csv.DictReader(handle):
                self.funding[(
                    row["venue"],
                    row["venue_symbol"],
                    parse_date(row["d"]),
                )] = float(row["rate_sum"])

    @staticmethod
    def round_trip_bps(candidate: dict, mode: str, basis_bps: float) -> float:
        short_venue, long_venue = candidate["sv"], candidate["lv"]
        maker_venue, taker_venue = maker_taker(short_venue, long_venue)
        maker_path = (
            MAKER_BPS[maker_venue]
            + TAKER_BPS[taker_venue]
            + TAKER_BPS[short_venue]
            + TAKER_BPS[long_venue]
        )
        taker_path = 2.0 * TAKER_BPS[short_venue] + 2.0 * TAKER_BPS[long_venue]
        if mode == "none":
            return 0.0
        if mode == "maker":
            return maker_path
        if mode == "maker_basis":
            return maker_path + basis_bps
        if mode == "taker_basis":
            return taker_path + basis_bps
        raise ValueError(f"unknown cost filter: {mode}")

    @staticmethod
    def transfer_toward_equal(
        balance: dict[str, float], used: dict[str, float]
    ) -> tuple[float, int]:
        total = sum(balance.values())
        target = {venue: total / 3.0 for venue in VENUES}
        donors = {
            venue: min(
                max(balance[venue] - target[venue], 0.0),
                max(balance[venue] - used[venue], 0.0),
            )
            for venue in VENUES
        }
        recipients = {
            venue: max(target[venue] - balance[venue], 0.0) for venue in VENUES
        }
        amount = min(sum(donors.values()), sum(recipients.values()))
        if amount <= 1e-9:
            return 0.0, 0

        donor_total = sum(donors.values())
        recipient_total = sum(recipients.values())
        withdrawals = 0
        for donor, available in donors.items():
            if available <= 0.0:
                continue
            taken = amount * available / donor_total
            balance[donor] -= taken
            withdrawals += 1
            for recipient, deficit in recipients.items():
                if deficit > 0.0:
                    balance[recipient] += taken * deficit / recipient_total
        return amount, withdrawals

    def run(self, config: Config) -> Result:
        balance = dict(zip(VENUES, config.allocation))
        positions: list[dict] = []
        funding_by_venue = {venue: 0.0 for venue in VENUES}
        fees_by_venue = {venue: 0.0 for venue in VENUES}
        basis_paid = 0.0
        opened = 0
        closed = 0
        retained_events = 0
        capital_rejections = 0
        cost_rejections = 0
        missing_keys: Counter = Counter()
        total_leg_days = 0
        slots: list[int] = []
        last_transfer: date | None = None
        transfers = 0
        withdrawals = 0
        transferred = 0.0

        day = config.start
        while day <= config.end:
            # The day-D book is formed after D's daily funding in this replay.  Thus a newly opened
            # pair first receives D+1 funding; D cannot be both signal and forward P&L.
            for position in positions:
                for venue, symbol, sign in (
                    (position["sv"], position["sv_sym"], 1.0),
                    (position["lv"], position["lv_sym"], -1.0),
                ):
                    total_leg_days += 1
                    lookup = (venue, symbol, day)
                    if lookup not in self.funding:
                        missing_keys[lookup] += 1
                    pnl = sign * self.funding.get(lookup, 0.0) * position["notional"]
                    balance[venue] += pnl
                    funding_by_venue[venue] += pnl

            # Do not open a fresh book on the artificial year-end date and immediately liquidate it.
            if day in self.candidates and day < config.end:
                used = {
                    venue: sum(
                        position["notional"]
                        for position in positions
                        if position["sv"] == venue or position["lv"] == venue
                    )
                    for venue in VENUES
                }
                total_equity = sum(balance.values())
                trigger = config.transfer_trigger_share
                transfer_due = (
                    trigger is not None
                    and min(balance.values()) / total_equity < trigger
                    and (
                        last_transfer is None
                        or (day - last_transfer).days >= config.transfer_cooldown_days
                    )
                )
                if transfer_due:
                    amount, donor_count = self.transfer_toward_equal(balance, used)
                    if amount > 0.0:
                        fee = donor_count * config.transfer_cost_per_withdrawal
                        free = {
                            venue: max(balance[venue] - used[venue], 0.0) for venue in VENUES
                        }
                        free_total = sum(free.values())
                        for venue in VENUES:
                            venue_fee = fee * free[venue] / free_total if free_total else 0.0
                            balance[venue] -= venue_fee
                            fees_by_venue[venue] += venue_fee
                        transferred += amount
                        transfers += 1
                        withdrawals += donor_count
                    last_transfer = day

                rows = self.candidates[day]
                if config.rank_cap is not None:
                    rows = rows[: config.rank_cap]
                old = {position["key"]: position for position in positions}
                leg_notional = (
                    sum(balance.values())
                    * config.notional_scale
                    / (2.0 * config.positions)
                )
                required = {venue: 0.0 for venue in VENUES}
                selected: list[tuple[dict, dict | None, float]] = []
                selected_keys: set[tuple[str, ...]] = set()

                for candidate in rows:
                    if len(selected) >= config.positions:
                        break
                    key = candidate_key(candidate)
                    held = old.get(key)
                    filter_bps = self.round_trip_bps(
                        candidate, config.cost_filter, config.basis_bps
                    )
                    break_even_annual_pct = filter_bps * 3.65 / 3.0
                    # The entry cost is sunk for an exact retained pair.  Applying the entry filter
                    # again would force churn precisely where retaining is meant to avoid it.
                    if held is None and candidate["spread"] < break_even_annual_pct:
                        cost_rejections += 1
                        continue
                    candidate_notional = held["notional"] if held else leg_notional
                    short_venue, long_venue = candidate["sv"], candidate["lv"]
                    if (
                        required[short_venue] + candidate_notional > balance[short_venue]
                        or required[long_venue] + candidate_notional > balance[long_venue]
                    ):
                        capital_rejections += 1
                        continue
                    required[short_venue] += candidate_notional
                    required[long_venue] += candidate_notional
                    selected.append((candidate, held, candidate_notional))
                    selected_keys.add(key)

                next_positions: list[dict] = []
                for position in positions:
                    if position["key"] in selected_keys:
                        continue
                    # Current ordinary reconcile crosses both exits.
                    for venue in (position["sv"], position["lv"]):
                        fee = position["notional"] * TAKER_BPS[venue] / 10_000.0
                        balance[venue] -= fee
                        fees_by_venue[venue] += fee
                    closed += 1

                for candidate, held, candidate_notional in selected:
                    if held is not None:
                        next_positions.append(held)
                        retained_events += 1
                        continue
                    maker_venue, taker_venue = maker_taker(
                        candidate["sv"], candidate["lv"]
                    )
                    if config.entry_mode == "maker":
                        entry_legs = (
                            (maker_venue, MAKER_BPS[maker_venue]),
                            (taker_venue, TAKER_BPS[taker_venue]),
                        )
                    elif config.entry_mode == "taker":
                        entry_legs = (
                            (candidate["sv"], TAKER_BPS[candidate["sv"]]),
                            (candidate["lv"], TAKER_BPS[candidate["lv"]]),
                        )
                    else:
                        raise ValueError(f"unknown entry mode: {config.entry_mode}")
                    for venue, fee_bps in entry_legs:
                        fee = candidate_notional * fee_bps / 10_000.0
                        balance[venue] -= fee
                        fees_by_venue[venue] += fee
                    if config.basis_bps:
                        basis_cost = candidate_notional * config.basis_bps / 10_000.0
                        balance[maker_venue] -= basis_cost
                        basis_paid += basis_cost
                    new_position = dict(candidate)
                    new_position.update(
                        key=candidate_key(candidate), notional=candidate_notional
                    )
                    next_positions.append(new_position)
                    opened += 1
                positions = next_positions
                slots.append(len(positions))

            day += timedelta(days=1)

        # Independent-year boundary: include an all-taker liquidation after END's funding.
        for position in positions:
            for venue in (position["sv"], position["lv"]):
                fee = position["notional"] * TAKER_BPS[venue] / 10_000.0
                balance[venue] -= fee
                fees_by_venue[venue] += fee
            closed += 1

        return Result(
            net=sum(balance.values()) - sum(config.allocation),
            funding=sum(funding_by_venue.values()),
            fees=sum(fees_by_venue.values()),
            basis=basis_paid,
            opened=opened,
            closed=closed,
            retained_events=retained_events,
            capital_rejections=capital_rejections,
            cost_rejections=cost_rejections,
            rebalance_count=len(slots),
            average_slots=sum(slots) / len(slots),
            full_book_count=sum(slot_count == config.positions for slot_count in slots),
            minimum_slots=min(slots),
            maximum_slots=max(slots),
            missing_leg_days=sum(missing_keys.values()),
            total_leg_days=total_leg_days,
            missing_keys=missing_keys,
            transfers=transfers,
            withdrawals=withdrawals,
            transferred=transferred,
            end_balance=balance,
            funding_by_venue=funding_by_venue,
            fees_by_venue=fees_by_venue,
        )


PERIODS = (
    (date(2024, 8, 21), date(2025, 8, 20)),
    (date(2025, 8, 21), date(2026, 8, 20)),
)


def pct(value: float) -> float:
    return value / 45.0


def print_result(result: Result) -> None:
    print(
        f"net={result.net:+.2f} ({pct(result.net):+.2f}%) "
        f"funding={result.funding:.2f} fees={result.fees:.2f} basis={result.basis:.2f} "
        f"new={result.opened} retain={result.retained_events} "
        f"slots={result.average_slots:.2f} missing={result.missing_leg_days}/{result.total_leg_days} "
        f"transfers={result.transfers}/{result.withdrawals}/${result.transferred:.2f}"
    )


def run_suite(simulator: Simulator) -> None:
    print("BASELINE AND BACKFILL")
    print("scenario,prior_usd,prior_pct,recent_usd,recent_pct")
    for name, overrides in (
        ("rank20_equal", {"rank_cap": 20}),
        ("full_equal", {}),
        ("full_38_38_24", {"allocation": (1710.0, 1710.0, 1080.0)}),
        ("full_40_20_40", {"allocation": (1800.0, 900.0, 1800.0)}),
    ):
        results = [
            simulator.run(Config(start=start, end=end, **overrides))
            for start, end in PERIODS
        ]
        print(
            f"{name},{results[0].net:.2f},{pct(results[0].net):.2f},"
            f"{results[1].net:.2f},{pct(results[1].net):.2f}"
        )

    print("\nFIXED SPLITS, FULL RANK, P20, NO COST FILTER OR TRANSFERS")
    print("binance_pct,bybit_pct,hyperliquid_pct,prior_usd,prior_pct,recent_usd,recent_pct")
    fixed_splits = (
        (33.333333, 33.333333, 33.333334),
        (35, 30, 35),
        (35, 35, 30),
        (40, 20, 40),
        (40, 30, 30),
        (45, 20, 35),
        (45, 25, 30),
        (50, 35, 15),
    )
    for split in fixed_splits:
        allocation = tuple(45.0 * value for value in split)
        results = [
            simulator.run(Config(start=start, end=end, allocation=allocation))
            for start, end in PERIODS
        ]
        print(
            f"{split[0]:.3f},{split[1]:.3f},{split[2]:.3f},"
            f"{results[0].net:.2f},{pct(results[0].net):.2f},"
            f"{results[1].net:.2f},{pct(results[1].net):.2f}"
        )

    print("\nPOSITION COUNT: MAKER ENTRY, FEE BREAK-EVEN FILTER, 25%/30D TRANSFER, $1/WITHDRAWAL")
    print("positions,prior_usd,prior_pct,recent_usd,recent_pct")
    for positions in (10, 12, 15, 18, 20, 21, 24, 25, 27, 30):
        results = [
            simulator.run(Config(
                start=start,
                end=end,
                positions=positions,
                cost_filter="maker",
                transfer_trigger_share=0.25,
                transfer_cooldown_days=30,
                transfer_cost_per_withdrawal=1.0,
            ))
            for start, end in PERIODS
        ]
        print(
            f"{positions},{results[0].net:.2f},{pct(results[0].net):.2f},"
            f"{results[1].net:.2f},{pct(results[1].net):.2f}"
        )

    print("\nDETAIL: P15 AND P20 RECOMMENDED RESEARCH CASE")
    for positions in (15, 20):
        for start, end in PERIODS:
            result = simulator.run(Config(
                start=start,
                end=end,
                positions=positions,
                cost_filter="maker",
                transfer_trigger_share=0.25,
                transfer_cooldown_days=30,
                transfer_cost_per_withdrawal=1.0,
            ))
            print(f"P{positions} {start}..{end}")
            print_result(result)
            print("  missing", dict(result.missing_keys))
            print("  end", {key: round(value, 2) for key, value in result.end_balance.items()})

    print("\nCONSERVATIVE: ALL-TAKER ENTRY + 8.5BP BASIS, FILTER INCLUDES BOTH")
    print("positions,prior_usd,prior_pct,recent_usd,recent_pct")
    for positions in (10, 12, 15, 20):
        results = [
            simulator.run(Config(
                start=start,
                end=end,
                positions=positions,
                cost_filter="taker_basis",
                entry_mode="taker",
                basis_bps=8.5,
                transfer_trigger_share=0.25,
                transfer_cooldown_days=30,
                transfer_cost_per_withdrawal=1.0,
            ))
            for start, end in PERIODS
        ]
        print(
            f"{positions},{results[0].net:.2f},{pct(results[0].net):.2f},"
            f"{results[1].net:.2f},{pct(results[1].net):.2f}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--candidates", type=Path,
        default=ARTIFACT_DIR / "generated" / "candidates_full.csv",
    )
    parser.add_argument(
        "--funding", type=Path,
        default=ARTIFACT_DIR / "generated" / "funding_daily_fresh.csv",
    )
    parser.add_argument("--suite", action="store_true")
    parser.add_argument("--start", type=date.fromisoformat)
    parser.add_argument("--end", type=date.fromisoformat)
    parser.add_argument("--positions", type=int, default=20)
    parser.add_argument("--cost-filter", choices=("none", "maker", "maker_basis", "taker_basis"), default="none")
    parser.add_argument("--entry-mode", choices=("maker", "taker"), default="maker")
    parser.add_argument("--basis-bps", type=float, default=0.0)
    parser.add_argument("--transfer-trigger-share", type=float)
    parser.add_argument("--transfer-cooldown-days", type=int, default=30)
    parser.add_argument("--transfer-cost", type=float, default=0.0)
    args = parser.parse_args()

    simulator = Simulator(args.candidates, args.funding)
    if args.suite:
        run_suite(simulator)
        return
    if args.start is None or args.end is None:
        parser.error("provide --suite or both --start and --end")
    result = simulator.run(Config(
        start=args.start,
        end=args.end,
        positions=args.positions,
        cost_filter=args.cost_filter,
        entry_mode=args.entry_mode,
        basis_bps=args.basis_bps,
        transfer_trigger_share=args.transfer_trigger_share,
        transfer_cooldown_days=args.transfer_cooldown_days,
        transfer_cost_per_withdrawal=args.transfer_cost,
    ))
    print_result(result)
    print("missing", dict(result.missing_keys))
    print("end", result.end_balance)


if __name__ == "__main__":
    main()
