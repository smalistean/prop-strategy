#!/usr/bin/env python3
"""Outcome-blind feasibility audit for IDEA_BOARD #17.

This script deliberately reads only instrument metadata, funding settlements, and data-coverage
counts. It never reads prices around an event and cannot calculate a strategy return. Its output is
the frozen event/data inventory used before DIVIDEND_REOPENING_PREREGISTRATION.md is written.

Usage:
  python3 scripts/analysis-dividend-reopening-audit.py
  python3 scripts/analysis-dividend-reopening-audit.py OUTPUT.json
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo


REPO = Path(__file__).resolve().parent.parent
UNIVERSE_SOURCE = REPO / "research" / "earnings-night-events-2026-09-12.json"
DEFAULT_OUTPUT = REPO / "research" / "dividend-reopening-feasibility-2026-09-12.json"
DATA_CUTOFF = datetime(2026, 9, 12, tzinfo=timezone.utc)
NY = ZoneInfo("America/New_York")
MAX_SECOND_PRINT_GAP = Decimal("5")
HOURLY_TOLERANCE = Decimal("2")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def psql(sql: str) -> list[list[str]]:
    command = [
        "psql",
        "-X",
        "-U",
        os.environ.get("DB_USER", "prop_strategy_app"),
        "-d",
        os.environ.get("DB_NAME", "prop_strategy"),
        "-qAt",
        "-F",
        "|",
        "-c",
        sql,
    ]
    with tempfile.TemporaryFile(mode="w+") as output:
        subprocess.run(command, stdout=output, check=True)
        output.seek(0)
        return [line.rstrip("\n").split("|") for line in output if line.strip()]


def load_frozen_equity_symbols() -> list[str]:
    manifest = json.loads(UNIVERSE_SOURCE.read_text())
    instruments = (manifest.get("eligibleUniverse") or []) + (manifest.get("excludedUniverse") or [])
    symbols = sorted({row["symbol"] for row in instruments})
    if len(symbols) != manifest["exchangeUniverseCount"]:
        raise RuntimeError(
            f"universe mismatch: reconstructed {len(symbols)}, manifest says "
            f"{manifest['exchangeUniverseCount']}"
        )
    if not all(re.fullmatch(r"[A-Z0-9]+", symbol) for symbol in symbols):
        raise RuntimeError("unsafe symbol in frozen universe")
    return symbols


def decimal_epoch_to_datetime(value: Decimal) -> datetime:
    seconds = int(value)
    micros = int((value - seconds) * Decimal(1_000_000))
    return datetime.fromtimestamp(seconds, timezone.utc).replace(microsecond=micros)


def near_hour(delta: Decimal) -> bool:
    return abs(delta - Decimal(3600)) <= HOURLY_TOLERANCE


def event_inventory(symbols: list[str]) -> tuple[list[dict[str, object]], dict[str, object]]:
    quoted = "','".join(symbols)
    rows = psql(
        f"""
        SET TIME ZONE 'UTC';
        SELECT symbol,
               extract(epoch FROM funding_time),
               funding_rate,
               mark_price
        FROM binance_perp_funding_rate
        WHERE symbol IN ('{quoted}')
          AND funding_time < '{DATA_CUTOFF.isoformat()}'::timestamptz
        ORDER BY symbol, funding_time;
        """
    )

    by_symbol: dict[str, list[tuple[Decimal, Decimal, str | None]]] = defaultdict(list)
    marks_present = 0
    for symbol, epoch, rate, mark in rows:
        by_symbol[symbol].append((Decimal(epoch), Decimal(rate), mark or None))
        marks_present += bool(mark)

    events: list[dict[str, object]] = []
    for symbol, prints in by_symbol.items():
        for i in range(5, len(prints)):
            special_time, special_rate, _ = prints[i]
            ordinary_time, ordinary_rate, _ = prints[i - 1]
            gap = special_time - ordinary_time
            local = decimal_epoch_to_datetime(special_time).astimezone(NY)

            # Binance's US-equity procedure settles a separate, negative special row immediately
            # after the ordinary 20:00 New York row. The preceding four rows must show the declared
            # temporary hourly cadence at 16:00, 17:00, 18:00 and 19:00.
            if not (Decimal(0) < gap <= MAX_SECOND_PRINT_GAP and special_rate < 0):
                continue
            if not (local.hour == 20 and local.minute == 0 and local.second <= 5):
                continue
            prior_standard = [prints[i - j][0] for j in range(1, 6)]
            if not all(near_hour(prior_standard[j] - prior_standard[j + 1]) for j in range(4)):
                continue

            event_utc = decimal_epoch_to_datetime(special_time)
            events.append(
                {
                    "symbol": symbol,
                    "processDateNy": local.date().isoformat(),
                    "exDateNy": (local.date() + timedelta(days=1)).isoformat(),
                    "ordinarySettlementTimeUtc": decimal_epoch_to_datetime(ordinary_time).isoformat()
                    .replace("+00:00", "Z"),
                    "specialSettlementTimeUtc": event_utc.isoformat().replace("+00:00", "Z"),
                    "ordinaryRate": str(ordinary_rate),
                    "specialRate": str(special_rate),
                    "specialRateBp": float(special_rate * Decimal(10_000)),
                    "secondPrintGapMs": float(gap * Decimal(1_000)),
                }
            )

    events.sort(key=lambda row: (row["specialSettlementTimeUtc"], row["symbol"]))
    funding_coverage = {
        "rows": len(rows),
        "symbols": len(by_symbol),
        "firstTimeUtc": decimal_epoch_to_datetime(min(row[0] for values in by_symbol.values() for row in values))
        .isoformat()
        .replace("+00:00", "Z"),
        "lastTimeUtc": decimal_epoch_to_datetime(max(row[0] for values in by_symbol.values() for row in values))
        .isoformat()
        .replace("+00:00", "Z"),
        "markPricePresentRows": marks_present,
    }
    return events, funding_coverage


def kline_coverage(symbols: list[str], events: list[dict[str, object]]) -> tuple[list[dict[str, object]], int]:
    quoted = "','".join(symbols)
    coverage_rows = psql(
        f"""
        SET TIME ZONE 'UTC';
        SELECT interval,
               count(*),
               count(DISTINCT symbol),
               min(open_time),
               max(open_time)
        FROM binance_perp_kline
        WHERE symbol IN ('{quoted}')
          AND open_time < '{DATA_CUTOFF.isoformat()}'::timestamptz
        GROUP BY interval
        ORDER BY interval;
        """
    )
    coverage = [
        {
            "interval": interval,
            "rows": int(rows),
            "symbols": int(symbol_count),
            "firstOpenTime": first_time,
            "lastOpenTime": last_time,
        }
        for interval, rows, symbol_count, first_time, last_time in coverage_rows
    ]

    values = ",\n".join(
        "('%s', '%s'::timestamptz)" % (row["symbol"], row["specialSettlementTimeUtc"])
        for row in events
    )
    event_windows = psql(
        f"""
        SET TIME ZONE 'UTC';
        WITH events(symbol, event_time) AS (VALUES {values})
        SELECT count(*) FILTER (WHERE minute_rows > 0)
        FROM (
            SELECT e.symbol, e.event_time, count(k.open_time) AS minute_rows
            FROM events e
            LEFT JOIN binance_perp_kline k
              ON k.symbol = e.symbol
             AND k.interval = '1m'
             AND k.open_time >= e.event_time - interval '1 hour'
             AND k.open_time <  e.event_time + interval '1 hour'
            GROUP BY e.symbol, e.event_time
        ) windows;
        """
    )
    return coverage, int(event_windows[0][0])


def main() -> None:
    output = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_OUTPUT
    symbols = load_frozen_equity_symbols()
    events, funding_coverage = event_inventory(symbols)
    kline_rows, event_windows_with_1m = kline_coverage(symbols, events)
    abs_rates = sorted(abs(Decimal(str(row["specialRateBp"]))) for row in events)
    median_rate = abs_rates[len(abs_rates) // 2]
    months = sorted({f"{row['symbol']},{str(row['specialSettlementTimeUtc'])[:7]}" for row in events})

    result = {
        "schemaVersion": 1,
        "createdAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "outcomeBlind": True,
        "dataCutoffExclusiveUtc": DATA_CUTOFF.isoformat().replace("+00:00", "Z"),
        "universe": {
            "source": str(UNIVERSE_SOURCE.relative_to(REPO)),
            "sourceSha256": sha256(UNIVERSE_SOURCE),
            "symbols": len(symbols),
            "definition": "Frozen Binance TRADIFI_PERPETUAL, underlyingType=EQUITY, USDT universe",
        },
        "eventFingerprint": {
            "description": "negative second funding row <=5 seconds after ordinary 20:00 New York settlement, preceded by five hourly settlements",
            "maximumSecondPrintGapSeconds": float(MAX_SECOND_PRINT_GAP),
            "hourlyCadenceToleranceSeconds": float(HOURLY_TOLERANCE),
        },
        "eventSummary": {
            "events": len(events),
            "symbols": len({row["symbol"] for row in events}),
            "processDatesNy": len({row["processDateNy"] for row in events}),
            "symbolMonths": len(months),
            "firstProcessDateNy": min(row["processDateNy"] for row in events),
            "lastProcessDateNy": max(row["processDateNy"] for row in events),
            "medianAbsoluteSpecialRateBp": float(median_rate),
            "minimumAbsoluteSpecialRateBp": float(min(abs_rates)),
            "maximumAbsoluteSpecialRateBp": float(max(abs_rates)),
        },
        "fundingCoverage": funding_coverage,
        "klineCoverage": kline_rows,
        "eventWindowsWithStored1m": event_windows_with_1m,
        "requiredSymbolMonths": months,
        "events": events,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"wrote {output}")
    print(
        f"events={len(events)} symbols={result['eventSummary']['symbols']} "
        f"dates={result['eventSummary']['processDatesNy']} symbol_months={len(months)}"
    )
    print(
        f"funding_rows={funding_coverage['rows']} mark_rows={funding_coverage['markPricePresentRows']} "
        f"event_windows_with_1m={event_windows_with_1m}"
    )


if __name__ == "__main__":
    main()
