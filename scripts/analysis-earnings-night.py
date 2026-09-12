#!/usr/bin/env python3
"""Frozen SEC Item 2.02 filing-night continuation screen.

The design is declared in EARNINGS_NIGHT_PREREGISTRATION.md.

Modes:
  selfcheck
  inventory --output /tmp/earnings-events.json
  measure --manifest research/earnings-night-events-2026-09-12.json --output /tmp/earnings-result.json

`inventory` reads Binance metadata and SEC filing metadata/documents, but no Binance
prices. `measure` refuses to run unless the manifest has the exact frozen SHA-256.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import os
import re
import statistics as st
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import date, datetime, time as wall_time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo


REPO = Path(__file__).resolve().parent.parent
NY = ZoneInfo("America/New_York")
UTC = timezone.utc

WINDOW_START = date(2025, 12, 1)
WINDOW_END = date(2026, 9, 10)
TRIGGER_BP = 300.0
PRIMARY_COST_BP = 13.0
SENSITIVITY_COSTS_BP = (9.0, 25.0)

BINANCE_EXCHANGE_INFO = "https://fapi.binance.com/fapi/v1/exchangeInfo"
SEC_TICKERS = "https://www.sec.gov/files/company_tickers.json"
SEC_SUBMISSIONS = "https://data.sec.gov/submissions/CIK{cik:010d}.json"
SEC_SUBMISSIONS_FILE = "https://data.sec.gov/submissions/{name}"
SEC_ARCHIVE_DOCUMENT = "https://www.sec.gov/Archives/edgar/data/{cik}/{accession}/{document}"
USER_AGENT = os.environ.get("SEC_USER_AGENT", "PropStrategyResearch/1.0 research@example.com")

# Patched only after `inventory` has created the outcome-blind manifest.
FROZEN_MANIFEST_SHA256 = "f318b8cf40fe06e78d4a5d2d88be8b98a3d0060ffecedd49e86e0a8727ac0d00"

PILOT_BASES = {
    "SPY", "QQQ", "EWJ", "EWY", "COIN", "TSLA", "MSTR", "PLTR", "HOOD", "AAPL", "AMZN", "META",
    "INTC", "MU", "CRCL", "NVDA", "LLY", "JPM", "QCOM", "TSM", "PAYP", "SNDK", "AAOI", "AXTI", "NOK",
}
TICKER_ALIASES = {"BRKB": "BRK-B"}

US_FULL_CLOSURES = {
    date(2025, 12, 25),
    date(2026, 1, 1), date(2026, 1, 19), date(2026, 2, 16), date(2026, 4, 3),
    date(2026, 5, 25), date(2026, 6, 19), date(2026, 7, 3), date(2026, 9, 7),
}
US_EARLY_CLOSES = {date(2025, 12, 24), date(2026, 7, 2)}

EARNINGS_PHRASES = (
    "financial results",
    "quarterly results",
    "results for the quarter",
    "results for its quarter",
    "results for the fiscal year",
    "full year results",
    "earnings results",
)
LISTED_US_EXCHANGES = {"Nasdaq", "NYSE", "NYSE American", "Cboe BZX"}


def is_us_session(d: date) -> bool:
    return d.weekday() < 5 and d not in US_FULL_CLOSURES


def is_full_us_session(d: date) -> bool:
    return is_us_session(d) and d not in US_EARLY_CLOSES


def previous_us_session(d: date) -> date:
    p = d - timedelta(days=1)
    while not is_us_session(p):
        p -= timedelta(days=1)
    return p


def next_us_session(d: date) -> date:
    n = d + timedelta(days=1)
    while not is_us_session(n):
        n += timedelta(days=1)
    return n


def eligible_clock_date(d: date) -> bool:
    return (
        WINDOW_START <= d <= WINDOW_END
        and d.weekday() <= 3
        and is_full_us_session(d)
        and is_full_us_session(next_us_session(d))
        and next_us_session(d) == d + timedelta(days=1)
    )


def ny_datetime(d: date, hour: int, minute: int = 0) -> datetime:
    return datetime(d.year, d.month, d.day, hour, minute, tzinfo=NY)


def ny_epoch(d: date, hour: int, minute: int = 0) -> int:
    return int(ny_datetime(d, hour, minute).timestamp())


def iso_utc(dt: datetime) -> str:
    return dt.astimezone(UTC).isoformat().replace("+00:00", "Z")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


_last_sec_request = 0.0


def get_bytes(url: str, sec: bool = False, attempts: int = 4) -> bytes:
    global _last_sec_request
    for attempt in range(attempts):
        if sec:
            wait = 0.13 - (time.monotonic() - _last_sec_request)
            if wait > 0:
                time.sleep(wait)
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Encoding": "identity",
                "Accept": "application/json,text/html,*/*",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                body = response.read()
            if sec:
                _last_sec_request = time.monotonic()
            return body
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt + 1 == attempts:
                raise RuntimeError(f"failed to fetch {url}: {exc}") from exc
            time.sleep(1.0 * (2**attempt))
    raise AssertionError("unreachable")


def get_json(url: str, sec: bool = False) -> tuple[dict[str, Any], bytes]:
    raw = get_bytes(url, sec=sec)
    return json.loads(raw), raw


def rows_from_columns(columns: dict[str, list[Any]]) -> list[dict[str, Any]]:
    keys = [k for k, v in columns.items() if isinstance(v, list)]
    if not keys:
        return []
    length = max(len(columns[k]) for k in keys)
    return [{k: columns[k][i] if i < len(columns[k]) else None for k in keys} for i in range(length)]


def filing_rows(submission: dict[str, Any], source_hashes: dict[str, str]) -> list[dict[str, Any]]:
    filings = submission.get("filings") or {}
    rows = rows_from_columns(filings.get("recent") or {})
    for old in filings.get("files") or []:
        start = old.get("filingFrom") or "9999-12-31"
        end = old.get("filingTo") or "0001-01-01"
        if end < WINDOW_START.isoformat() or start > WINDOW_END.isoformat():
            continue
        name = old["name"]
        doc, raw = get_json(SEC_SUBMISSIONS_FILE.format(name=name), sec=True)
        source_hashes[f"sec-submissions/{name}"] = sha256(raw)
        rows.extend(rows_from_columns(doc))
    by_accession: dict[str, dict[str, Any]] = {}
    for row in rows:
        accession = row.get("accessionNumber")
        if accession:
            by_accession[accession] = row
    return list(by_accession.values())


def parse_acceptance(value: str | None) -> datetime | None:
    if not value:
        return None
    # The submissions JSON is an ISO timestamp with an explicit UTC suffix. For example,
    # APP's 2026-08-05 JSON 20:06:12Z matches raw-header 16:06:12 New York.
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"SEC acceptance timestamp has no timezone: {value}")
    return parsed.astimezone(UTC)


def item_tokens(value: Any) -> set[str]:
    return {token.strip() for token in str(value or "").split(",") if token.strip()}


def document_text(raw: bytes) -> str:
    text = raw.decode("utf-8", errors="ignore")
    text = re.sub(r"(?is)<script.*?</script>|<style.*?</style>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text).lower()
    return re.sub(r"\s+", " ", text).strip()


def classify_primary_document(raw: bytes) -> tuple[bool, list[str]]:
    text = document_text(raw)
    matched = [phrase for phrase in EARNINGS_PHRASES if phrase in text]
    accepted = "press release" in text and bool(matched) and "preliminary results" not in text
    return accepted, matched


def primary_document_url(cik: int, accession: str, primary_document: str) -> str:
    return SEC_ARCHIVE_DOCUMENT.format(
        cik=cik,
        accession=accession.replace("-", ""),
        document=urllib.parse.quote(primary_document),
    )


def make_inventory(output: Path) -> None:
    exchange, exchange_raw = get_json(BINANCE_EXCHANGE_INFO)
    tickers_doc, tickers_raw = get_json(SEC_TICKERS, sec=True)

    ticker_index: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in tickers_doc.values():
        ticker_index[str(row["ticker"]).upper()].append(row)

    exchange_symbols = []
    for row in exchange.get("symbols") or []:
        symbol = str(row.get("symbol") or "")
        if not (
            row.get("status") == "TRADING"
            and row.get("contractType") == "TRADIFI_PERPETUAL"
            and row.get("underlyingType") == "EQUITY"
            and row.get("quoteAsset") == "USDT"
            and symbol.endswith("USDT")
        ):
            continue
        base = symbol[:-4]
        exchange_symbols.append(
            {
                "symbol": symbol,
                "base": base,
                "secTickerRequested": TICKER_ALIASES.get(base, base),
                "onboardAtUtc": iso_utc(datetime.fromtimestamp(int(row["onboardDate"]) / 1000, UTC)),
            }
        )
    exchange_symbols.sort(key=lambda x: x["symbol"])
    current_sec_tickers = {row["secTickerRequested"] for row in exchange_symbols}

    source_hashes: dict[str, str] = {
        "binance-exchangeInfo": sha256(exchange_raw),
        "sec-company_tickers": sha256(tickers_raw),
    }
    eligible_universe: list[dict[str, Any]] = []
    excluded_universe: list[dict[str, Any]] = []
    events: list[dict[str, Any]] = []

    for number, instrument in enumerate(exchange_symbols, 1):
        requested = instrument["secTickerRequested"]
        matches = ticker_index.get(requested, [])
        if len(matches) != 1:
            excluded_universe.append({**instrument, "reason": f"SEC ticker matches={len(matches)}"})
            continue
        sec_ticker = matches[0]
        cik = int(sec_ticker["cik_str"])
        submission_url = SEC_SUBMISSIONS.format(cik=cik)
        submission, submission_raw = get_json(submission_url, sec=True)
        source_hashes[f"sec-submissions/CIK{cik:010d}.json"] = sha256(submission_raw)
        entity_type = submission.get("entityType")
        if entity_type != "operating":
            excluded_universe.append(
                {
                    **instrument,
                    "ticker": requested,
                    "cik": cik,
                    "issuer": submission.get("name") or sec_ticker.get("title"),
                    "secEntityType": entity_type,
                    "reason": "SEC entityType is not operating",
                }
            )
            continue

        submission_tickers = [str(value).upper() for value in submission.get("tickers") or []]
        submission_exchanges = [str(value) for value in submission.get("exchanges") or []]
        ticker_positions = [i for i, ticker in enumerate(submission_tickers) if ticker == requested]
        listed_positions = [
            i for i in ticker_positions
            if i < len(submission_exchanges) and submission_exchanges[i] in LISTED_US_EXCHANGES
        ]
        if not listed_positions:
            excluded_universe.append(
                {
                    **instrument,
                    "ticker": requested,
                    "cik": cik,
                    "issuer": submission.get("name") or sec_ticker.get("title"),
                    "secEntityType": entity_type,
                    "reason": "ticker has no recognized listed US exchange",
                }
            )
            continue
        position = min(listed_positions)
        earlier_current = next((ticker for ticker in submission_tickers[:position] if ticker in current_sec_tickers), None)
        if earlier_current:
            excluded_universe.append(
                {
                    **instrument,
                    "ticker": requested,
                    "cik": cik,
                    "issuer": submission.get("name") or sec_ticker.get("title"),
                    "secEntityType": entity_type,
                    "reason": f"secondary current Binance security for CIK; earlier SEC ticker={earlier_current}",
                }
            )
            continue

        rows = filing_rows(submission, source_hashes)
        item_202_dates: set[str] = set()
        candidates: list[dict[str, Any]] = []
        for filing in rows:
            if filing.get("form") != "8-K" or "2.02" not in item_tokens(filing.get("items")):
                continue
            accepted_utc = parse_acceptance(filing.get("acceptanceDateTime"))
            if accepted_utc:
                local_date = accepted_utc.astimezone(NY).date()
            else:
                try:
                    local_date = date.fromisoformat(str(filing.get("filingDate")))
                except ValueError:
                    continue
            if WINDOW_START <= local_date <= WINDOW_END:
                item_202_dates.add(local_date.isoformat())
            if accepted_utc is None:
                continue
            accepted_local = accepted_utc.astimezone(NY)
            if not eligible_clock_date(accepted_local.date()):
                continue
            if not (wall_time(16, 0) <= accepted_local.timetz().replace(tzinfo=None) <= wall_time(19, 30)):
                continue
            primary = str(filing.get("primaryDocument") or "")
            if not primary:
                continue
            document_url = primary_document_url(cik, filing["accessionNumber"], primary)
            raw_document = get_bytes(document_url, sec=True)
            source_hashes[f"sec-document/{cik}/{filing['accessionNumber']}/{primary}"] = sha256(raw_document)
            accepted_by_text, matched_phrases = classify_primary_document(raw_document)
            candidates.append(
                {
                    "symbol": instrument["symbol"],
                    "base": instrument["base"],
                    "ticker": requested,
                    "cik": cik,
                    "issuer": submission.get("name") or sec_ticker.get("title"),
                    "cohort": "A-pilot-contaminated" if instrument["base"] in PILOT_BASES else "B-primary",
                    "eventDateNy": accepted_local.date().isoformat(),
                    "acceptedAtUtc": iso_utc(accepted_utc),
                    "acceptedAtNy": accepted_local.isoformat(),
                    "accessionNumber": filing["accessionNumber"],
                    "filingDate": filing.get("filingDate"),
                    "reportDate": filing.get("reportDate"),
                    "items": filing.get("items"),
                    "primaryDocument": primary,
                    "primaryDocumentUrl": document_url,
                    "primaryDocumentSha256": sha256(raw_document),
                    "matchedEarningsPhrases": matched_phrases,
                    "documentTextRulePassed": accepted_by_text,
                }
            )

        universe_row = {
            **instrument,
            "ticker": requested,
            "cik": cik,
            "issuer": submission.get("name") or sec_ticker.get("title"),
            "secEntityType": entity_type,
            "secExchange": submission_exchanges[position],
            "pilotContaminated": instrument["base"] in PILOT_BASES,
            "allItem202DatesNy": sorted(item_202_dates),
        }
        eligible_universe.append(universe_row)

        # One issuer/local date: retain the earliest eligible accepted filing. Then apply the
        # deterministic document classifier without looking at prices.
        by_date: dict[str, dict[str, Any]] = {}
        for candidate in sorted(candidates, key=lambda x: x["acceptedAtUtc"]):
            by_date.setdefault(candidate["eventDateNy"], candidate)
        for candidate in by_date.values():
            if not candidate["documentTextRulePassed"]:
                continue
            onboard = datetime.fromisoformat(instrument["onboardAtUtc"].replace("Z", "+00:00"))
            event_date = date.fromisoformat(candidate["eventDateNy"])
            prior_open = ny_datetime(previous_us_session(event_date), 9, 30).astimezone(UTC)
            if onboard > prior_open:
                continue
            events.append(candidate)

        if number % 20 == 0:
            print(f"inventory: checked {number}/{len(exchange_symbols)} exchange symbols", flush=True)

    eligible_universe.sort(key=lambda x: x["symbol"])
    excluded_universe.sort(key=lambda x: x["symbol"])
    events.sort(key=lambda x: (x["eventDateNy"], x["symbol"], x["acceptedAtUtc"]))
    manifest = {
        "schemaVersion": 1,
        "createdAtUtc": iso_utc(datetime.now(UTC)),
        "declaration": "EARNINGS_NIGHT_PREREGISTRATION.md, 2026-09-12 09:43 UTC",
        "window": {"startNy": WINDOW_START.isoformat(), "endNy": WINDOW_END.isoformat()},
        "sourceUrls": {
            "binanceExchangeInfo": BINANCE_EXCHANGE_INFO,
            "secCompanyTickers": SEC_TICKERS,
            "secSubmissionsTemplate": SEC_SUBMISSIONS,
        },
        "sourceSha256": dict(sorted(source_hashes.items())),
        "pilotBases": sorted(PILOT_BASES),
        "tickerAliases": TICKER_ALIASES,
        "exchangeUniverseCount": len(exchange_symbols),
        "eligibleOperatingUniverseCount": len(eligible_universe),
        "excludedUniverseCount": len(excluded_universe),
        "eligibleEventCount": len(events),
        "eligibleUniverse": eligible_universe,
        "excludedUniverse": excluded_universe,
        "events": events,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    digest = sha256(output.read_bytes())
    print(f"inventory written: {output}")
    print(f"current EQUITY/USDT perps: {len(exchange_symbols)}")
    print(f"eligible operating issuers: {len(eligible_universe)}")
    print(f"eligible filing events: {len(events)}")
    print(f"SHA256 {digest}")


def psql_to_file(sql: str, output: Path) -> None:
    command = [
        "psql", "-X",
        "-U", os.environ.get("DB_USER", "prop_strategy_app"),
        "-d", os.environ.get("DB_NAME", "prop_strategy"),
        "-qAt", "-F", "|", "-c", sql,
    ]
    with output.open("w") as out:
        subprocess.run(command, stdout=out, check=True)


def load_market_data(symbols: list[str]) -> tuple[dict[str, dict[int, dict[str, float]]], dict[str, list[tuple[int, float]]]]:
    if not all(re.fullmatch(r"[A-Z0-9]+", symbol) for symbol in symbols):
        raise ValueError("unsafe symbol in manifest")
    quoted = "','".join(symbols)
    tmp = Path(tempfile.mkdtemp(prefix="earnings-night-"))
    bars_file = tmp / "bars.psv"
    funding_file = tmp / "funding.psv"
    bars_sql = f"""
SET TIME ZONE 'UTC';
SELECT symbol, extract(epoch FROM open_time)::bigint, open_price, close_price,
       quote_asset_volume, trade_count
FROM binance_perp_kline
WHERE \"interval\" = '1h'
  AND symbol IN ('{quoted}')
  AND open_time >= '2025-11-25 00:00+00'
  AND open_time <  '2026-09-12 00:00+00'
  AND (open_time AT TIME ZONE 'America/New_York')::time IN
      (TIME '10:00', TIME '15:00', TIME '19:00', TIME '20:00')
ORDER BY symbol, open_time;
"""
    funding_sql = f"""
SET TIME ZONE 'UTC';
SELECT symbol, extract(epoch FROM funding_time)::bigint, funding_rate
FROM binance_perp_funding_rate
WHERE symbol IN ('{quoted}')
  AND funding_time >= '2025-12-01 00:00+00'
  AND funding_time <  '2026-09-12 00:00+00'
ORDER BY symbol, funding_time;
"""
    psql_to_file(bars_sql, bars_file)
    psql_to_file(funding_sql, funding_file)

    bars: dict[str, dict[int, dict[str, float]]] = defaultdict(dict)
    with bars_file.open() as rows:
        for line in rows:
            parts = line.rstrip("\n").split("|")
            if len(parts) != 6:
                continue
            symbol, epoch, open_price, close_price, quote_volume, trade_count = parts
            bars[symbol][int(epoch)] = {
                "open": float(open_price),
                "close": float(close_price),
                "quoteVolume": float(quote_volume),
                "tradeCount": int(trade_count),
            }
    funding: dict[str, list[tuple[int, float]]] = defaultdict(list)
    with funding_file.open() as rows:
        for line in rows:
            parts = line.rstrip("\n").split("|")
            if len(parts) != 3:
                continue
            symbol, epoch, rate = parts
            funding[symbol].append((int(epoch), float(rate)))
    return bars, funding


def bar_times(d: date) -> dict[str, int]:
    return {
        "anchor": ny_epoch(d, 15),
        "decision": ny_epoch(d, 19),
        "entry": ny_epoch(d, 20),
        "exit": ny_epoch(next_us_session(d), 10),
    }


def shock_bucket(abs_shock_bp: float) -> str:
    if abs_shock_bp < 500:
        return "3-5pct"
    if abs_shock_bp < 1000:
        return "5-10pct"
    return "10pct-plus"


def quarter_for(d: date) -> str:
    return f"{d.year}-Q{(d.month - 1) // 3 + 1}"


def build_outcome(
    symbol: str,
    d: date,
    bars: dict[str, dict[int, dict[str, float]]],
    funding: dict[str, list[tuple[int, float]]],
) -> tuple[dict[str, Any] | None, str | None]:
    times = bar_times(d)
    symbol_bars = bars.get(symbol, {})
    missing = [name for name, epoch in times.items() if epoch not in symbol_bars]
    if missing:
        return None, "missing bars: " + ",".join(missing)
    required = {name: symbol_bars[epoch] for name, epoch in times.items()}
    if any(row["open"] <= 0 or row["close"] <= 0 for row in required.values()):
        return None, "non-positive price"
    if any(row["tradeCount"] <= 0 for row in required.values()):
        return None, "required bar has zero trades"

    anchor = required["anchor"]["close"]
    decision = required["decision"]["close"]
    entry = required["entry"]["open"]
    exit_price = required["exit"]["open"]
    shock_bp = (decision / anchor - 1.0) * 10_000
    if abs(shock_bp) < TRIGGER_BP:
        return {
            "triggered": False,
            "anchorPrice": anchor,
            "decisionPrice": decision,
            "entryPrice": entry,
            "exitPrice": exit_price,
            "shockBp": shock_bp,
            "decisionQuoteVolume": required["decision"]["quoteVolume"],
            "capacity10PctUsd": required["decision"]["quoteVolume"] * 0.10,
        }, None

    direction = 1 if shock_bp > 0 else -1
    funding_rows = [(t, rate) for t, rate in funding.get(symbol, []) if times["entry"] < t <= times["exit"]]
    if not funding_rows:
        return None, "missing funding settlement in (entry,exit]"
    price_bp = direction * (exit_price / entry - 1.0) * 10_000
    funding_bp = -direction * sum(rate for _, rate in funding_rows) * 10_000
    gross_bp = price_bp + funding_bp
    return {
        "triggered": True,
        "direction": "long" if direction > 0 else "short",
        "directionSign": direction,
        "shockBucket": shock_bucket(abs(shock_bp)),
        "anchorPrice": anchor,
        "decisionPrice": decision,
        "entryPrice": entry,
        "exitPrice": exit_price,
        "shockBp": shock_bp,
        "entryGapBpSigned": direction * (entry / decision - 1.0) * 10_000,
        "pricePnlBp": price_bp,
        "fundingPnlBp": funding_bp,
        "fundingSettlements": len(funding_rows),
        "grossPnlBp": gross_bp,
        "net9Bp": gross_bp - 9.0,
        "net13Bp": gross_bp - 13.0,
        "net25Bp": gross_bp - 25.0,
        "decisionQuoteVolume": required["decision"]["quoteVolume"],
        "capacity10PctUsd": required["decision"]["quoteVolume"] * 0.10,
    }, None


def numeric_summary(values: Iterable[float]) -> dict[str, Any]:
    xs = list(values)
    if not xs:
        return {"n": 0, "mean": None, "median": None, "sd": None, "t": None, "positiveFraction": None, "worst": None}
    mean = st.mean(xs)
    sd = st.stdev(xs) if len(xs) >= 2 else None
    t_stat = mean / (sd / math.sqrt(len(xs))) if sd and sd > 0 else None
    return {
        "n": len(xs),
        "mean": mean,
        "median": st.median(xs),
        "sd": sd,
        "t": t_stat,
        "positiveFraction": sum(x > 0 for x in xs) / len(xs),
        "worst": min(xs),
    }


def nightly_values(rows: list[dict[str, Any]], value_key: str) -> dict[str, float]:
    grouped: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        grouped[row["eventDateNy"]].append(float(row[value_key]))
    return {d: st.mean(values) for d, values in sorted(grouped.items())}


def date_declustered_summary(rows: list[dict[str, Any]], value_key: str) -> dict[str, Any]:
    nights = nightly_values(rows, value_key)
    return {**numeric_summary(nights.values()), "nightly": nights, "events": len(rows)}


def group_summaries(rows: list[dict[str, Any]], group_key: str, value_key: str = "net13Bp") -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[str(row[group_key])].append(row)
    return {key: date_declustered_summary(value, value_key) for key, value in sorted(groups.items())}


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    xs = sorted(values)
    position = (len(xs) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return xs[lower]
    return xs[lower] * (upper - position) + xs[upper] * (position - lower)


def remove_best_symbol(rows: list[dict[str, Any]]) -> dict[str, Any]:
    full = date_declustered_summary(rows, "net13Bp")
    trials = []
    for symbol in sorted({row["symbol"] for row in rows}):
        remaining = [row for row in rows if row["symbol"] != symbol]
        result = date_declustered_summary(remaining, "net13Bp")
        trials.append({"symbol": symbol, "meanAfterRemoval": result["mean"], "nNightsAfterRemoval": result["n"]})
    valid = [trial for trial in trials if trial["meanAfterRemoval"] is not None]
    if not valid:
        return {"symbol": None, "meanAfterRemoval": None, "dropFromFullMean": None}
    worst = min(valid, key=lambda trial: trial["meanAfterRemoval"])
    return {**worst, "dropFromFullMean": full["mean"] - worst["meanAfterRemoval"]}


def control_cell(row: dict[str, Any]) -> tuple[str, str, int, str]:
    return (row["direction"], row["shockBucket"], int(row["weekdayNy"]), row["quarter"])


def measure(manifest_path: Path, output: Path) -> None:
    raw_manifest = manifest_path.read_bytes()
    manifest_hash = sha256(raw_manifest)
    if FROZEN_MANIFEST_SHA256 == "TO_BE_FROZEN":
        raise RuntimeError("manifest hash has not been frozen in the script")
    if manifest_hash != FROZEN_MANIFEST_SHA256:
        raise RuntimeError(f"manifest hash mismatch: expected {FROZEN_MANIFEST_SHA256}, got {manifest_hash}")
    manifest = json.loads(raw_manifest)

    universe = manifest["eligibleUniverse"]
    universe_by_symbol = {row["symbol"]: row for row in universe}
    symbols = sorted(set(universe_by_symbol) | {"BTCUSDT"})
    bars, funding = load_market_data(symbols)

    event_ledger: list[dict[str, Any]] = []
    for event in manifest["events"]:
        d = date.fromisoformat(event["eventDateNy"])
        outcome, unusable = build_outcome(event["symbol"], d, bars, funding)
        ledger_row = {**event, "unusableReason": unusable}
        if outcome:
            ledger_row.update(outcome)
            btc, _ = build_outcome("BTCUSDT", d, bars, funding)
            if btc:
                times = bar_times(d)
                btc_entry = bars["BTCUSDT"][times["entry"]]["open"]
                btc_exit = bars["BTCUSDT"][times["exit"]]["open"]
                ledger_row["btcSameClockBp"] = (btc_exit / btc_entry - 1.0) * 10_000
        event_ledger.append(ledger_row)

    controls: list[dict[str, Any]] = []
    d = WINDOW_START
    while d <= WINDOW_END:
        if eligible_clock_date(d):
            for universe_row in universe:
                if universe_row["pilotContaminated"]:
                    continue
                onboard = datetime.fromisoformat(universe_row["onboardAtUtc"].replace("Z", "+00:00"))
                if onboard > ny_datetime(previous_us_session(d), 9, 30).astimezone(UTC):
                    continue
                if d.isoformat() in set(universe_row["allItem202DatesNy"]):
                    continue
                outcome, _ = build_outcome(universe_row["symbol"], d, bars, funding)
                if not outcome or not outcome.get("triggered"):
                    continue
                controls.append(
                    {
                        "symbol": universe_row["symbol"],
                        "eventDateNy": d.isoformat(),
                        "weekdayNy": d.weekday(),
                        "quarter": quarter_for(d),
                        **outcome,
                    }
                )
        d += timedelta(days=1)

    for row in event_ledger:
        row["weekdayNy"] = date.fromisoformat(row["eventDateNy"]).weekday()
        row["quarter"] = quarter_for(date.fromisoformat(row["eventDateNy"]))

    cohort_a = [row for row in event_ledger if row["cohort"] == "A-pilot-contaminated" and row.get("triggered")]
    cohort_b = [row for row in event_ledger if row["cohort"] == "B-primary" and row.get("triggered")]

    primary = date_declustered_summary(cohort_b, "net13Bp")
    sensitivities = {
        "9bp": date_declustered_summary(cohort_b, "net9Bp"),
        "13bp": primary,
        "25bp": date_declustered_summary(cohort_b, "net25Bp"),
    }
    event_weighted = numeric_summary(row["net13Bp"] for row in cohort_b)

    nightly = nightly_values(cohort_b, "net13Bp")
    if nightly:
        best_date = max(nightly, key=nightly.get)
        without_best_date = numeric_summary(value for d, value in nightly.items() if d != best_date)
    else:
        best_date, without_best_date = None, numeric_summary([])
    best_symbol_result = remove_best_symbol(cohort_b)

    control_cells: dict[tuple[str, str, int, str], list[float]] = defaultdict(list)
    for row in controls:
        control_cells[control_cell(row)].append(row["net13Bp"])
    control_means = {cell: st.mean(values) for cell, values in control_cells.items()}
    adjusted_rows = []
    missing_cells = set()
    for row in cohort_b:
        cell = control_cell(row)
        if cell not in control_means:
            missing_cells.add(cell)
            continue
        adjusted_rows.append({**row, "controlCellMeanBp": control_means[cell], "adjustedBp": row["net13Bp"] - control_means[cell]})
    adjusted = None if missing_cells else date_declustered_summary(adjusted_rows, "adjustedBp")

    capacity = [row["capacity10PctUsd"] for row in cohort_b]
    funding_nightly = date_declustered_summary(cohort_b, "fundingPnlBp")
    btc_rows = [row for row in cohort_b if row.get("btcSameClockBp") is not None]
    btc_nightly = date_declustered_summary(btc_rows, "btcSameClockBp")
    quarter_stats = group_summaries(cohort_b, "quarter")
    positive_qualified_quarters = sum(
        stats_["n"] >= 3 and stats_["mean"] is not None and stats_["mean"] > 0
        for stats_ in quarter_stats.values()
    )

    enough = len(cohort_b) >= 30 and primary["n"] >= 20
    conditions = {
        "atLeast30Events": len(cohort_b) >= 30,
        "atLeast20Nights": primary["n"] >= 20,
        "nightlyMeanAtLeast50Bp": primary["mean"] is not None and primary["mean"] >= 50,
        "nightlyMedianPositive": primary["median"] is not None and primary["median"] > 0,
        "nightlyTAtLeast2": primary["t"] is not None and primary["t"] >= 2,
        "atLeastTwoPositiveQuartersWithThreeNights": positive_qualified_quarters >= 2,
        "meanWithoutBestDatePositive": without_best_date["mean"] is not None and without_best_date["mean"] > 0,
        "meanWithoutBestSymbolPositive": best_symbol_result["meanAfterRemoval"] is not None and best_symbol_result["meanAfterRemoval"] > 0,
        "controlAdjustedMeanPositive": adjusted is not None and adjusted["mean"] is not None and adjusted["mean"] > 0,
    }
    verdict = "INSUFFICIENT" if not enough else ("PASS" if all(conditions.values()) else "FAIL")

    report = {
        "schemaVersion": 1,
        "measuredAtUtc": iso_utc(datetime.now(UTC)),
        "manifestPath": str(manifest_path),
        "manifestSha256": manifest_hash,
        "verdict": verdict,
        "decisionConditions": conditions,
        "counts": {
            "eligibleEvents": len(event_ledger),
            "cohortAEligible": sum(row["cohort"] == "A-pilot-contaminated" for row in event_ledger),
            "cohortBEligible": sum(row["cohort"] == "B-primary" for row in event_ledger),
            "cohortATriggeredUsable": len(cohort_a),
            "cohortBTriggeredUsable": len(cohort_b),
            "unusable": sum(row["unusableReason"] is not None for row in event_ledger),
            "genericControlRows": len(controls),
        },
        "cohortB": {
            "primaryDateDeclustered13Bp": primary,
            "eventWeighted13Bp": event_weighted,
            "sensitivities": sensitivities,
            "byDirection": group_summaries(cohort_b, "direction"),
            "byQuarter": quarter_stats,
            "positiveQualifiedQuarterCount": positive_qualified_quarters,
            "withoutBestDate": {"removedDate": best_date, **without_best_date},
            "withoutBestSymbol": best_symbol_result,
            "fundingDateDeclusteredBp": funding_nightly,
            "btcSameClockDateDeclusteredBp": btc_nightly,
            "capacity10PctUsd": {
                "n": len(capacity),
                "minimum": min(capacity) if capacity else None,
                "p25": percentile(capacity, 0.25),
                "median": st.median(capacity) if capacity else None,
                "p75": percentile(capacity, 0.75),
                "maximum": max(capacity) if capacity else None,
                "fractionAtLeast1000Usd": sum(value >= 1000 for value in capacity) / len(capacity) if capacity else None,
            },
        },
        "cohortADescriptive": {
            "primaryDateDeclustered13Bp": date_declustered_summary(cohort_a, "net13Bp"),
            "eventWeighted13Bp": numeric_summary(row["net13Bp"] for row in cohort_a),
        },
        "genericControl": {
            "dateDeclustered13Bp": date_declustered_summary(controls, "net13Bp"),
            "eventWeighted13Bp": numeric_summary(row["net13Bp"] for row in controls),
            "cellMeans": {"|".join(map(str, key)): numeric_summary(values) for key, values in sorted(control_cells.items())},
        },
        "controlAdjusted": {
            "available": adjusted is not None,
            "missingCells": ["|".join(map(str, cell)) for cell in sorted(missing_cells)],
            "dateDeclusteredResidualBp": adjusted,
        },
        "eventLedger": event_ledger,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")

    def fmt(value: Any, digits: int = 1) -> str:
        return "NA" if value is None else f"{value:.{digits}f}"

    print(f"VERDICT {verdict}")
    print(
        f"cohort B: {len(cohort_b)} usable triggers / {primary['n']} nights; "
        f"mean {fmt(primary['mean'])} bp, median {fmt(primary['median'])} bp, t {fmt(primary['t'], 2)}"
    )
    print(
        f"event-weighted mean {fmt(event_weighted['mean'])} bp; without best date {fmt(without_best_date['mean'])} bp; "
        f"without best symbol {fmt(best_symbol_result['meanAfterRemoval'])} bp"
    )
    print(
        f"generic control nightly mean {fmt(report['genericControl']['dateDeclustered13Bp']['mean'])} bp; "
        f"adjusted mean {fmt(adjusted['mean'] if adjusted else None)} bp"
    )
    print(f"result written: {output}")


def selfcheck() -> None:
    summer = date(2026, 7, 15)
    winter = date(2026, 1, 14)
    fmt = lambda epoch: datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%d %H:%M")
    assert [fmt(bar_times(summer)[k]) for k in ("anchor", "decision", "entry", "exit")] == [
        "2026-07-15 19:00", "2026-07-15 23:00", "2026-07-16 00:00", "2026-07-16 14:00"
    ]
    assert [fmt(bar_times(winter)[k]) for k in ("anchor", "decision", "entry", "exit")] == [
        "2026-01-14 20:00", "2026-01-15 00:00", "2026-01-15 01:00", "2026-01-15 15:00"
    ]
    assert eligible_clock_date(date(2026, 6, 30))
    assert not eligible_clock_date(date(2026, 7, 2))
    assert not eligible_clock_date(date(2026, 9, 7))
    ok, matched = classify_primary_document(b"<p>Press release: quarterly financial results</p>")
    assert ok and "financial results" in matched
    assert not classify_primary_document(b"press release preliminary results for the quarter")[0]
    assert shock_bucket(300) == "3-5pct" and shock_bucket(500) == "5-10pct" and shock_bucket(1000) == "10pct-plus"

    d = date(2026, 7, 15)
    times = bar_times(d)
    bars = {"TESTUSDT": {}}
    for name, epoch in times.items():
        bars["TESTUSDT"][epoch] = {"open": 100.0, "close": 100.0, "quoteVolume": 10_000.0, "tradeCount": 5}
    bars["TESTUSDT"][times["anchor"]]["close"] = 100.0
    bars["TESTUSDT"][times["decision"]]["close"] = 104.0
    bars["TESTUSDT"][times["entry"]]["open"] = 105.0
    bars["TESTUSDT"][times["exit"]]["open"] = 107.1
    outcome, error = build_outcome("TESTUSDT", d, bars, {"TESTUSDT": [(times["entry"] + 3600, 0.0001)]})
    assert error is None and outcome and outcome["triggered"] and outcome["direction"] == "long"
    assert abs(outcome["pricePnlBp"] - 200.0) < 1e-9
    assert abs(outcome["fundingPnlBp"] + 1.0) < 1e-9
    assert abs(outcome["net13Bp"] - 186.0) < 1e-9
    print("SELFCHECK OK")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("selfcheck")
    inventory = sub.add_parser("inventory")
    inventory.add_argument("--output", type=Path, required=True)
    measure_parser = sub.add_parser("measure")
    measure_parser.add_argument("--manifest", type=Path, required=True)
    measure_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.mode == "selfcheck":
        selfcheck()
    elif args.mode == "inventory":
        make_inventory(args.output)
    elif args.mode == "measure":
        measure(args.manifest, args.output)
    else:
        raise AssertionError(args.mode)


if __name__ == "__main__":
    main()
