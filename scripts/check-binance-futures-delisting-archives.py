#!/usr/bin/env python3
"""Check #19's hedge/archive coverage without reading any market-data body.

The input is the frozen, outcome-blind delisting terms ledger.  This script issues HTTP HEAD
requests for official Binance archive ZIPs and reads only their tiny CHECKSUM companions.  It
does not open a ZIP or inspect a timestamp, price, basis, volume, funding value, or return.

Object existence is only an upper bound on event usability.  A later timestamp-only pass must
prove that both legs actually cover the frozen decision and exit minutes before any price field is
revealed.  In particular, a monthly spot object may exist even if spot stopped trading before the
futures settlement date.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent
LEDGER = REPO / "research" / "binance-futures-delisting-event-ledger-2026-09-13.json"
OUTPUT = REPO / "research" / "binance-futures-delisting-archive-availability-2026-09-13.json"
ROOT = "https://data.binance.vision/data"
CURRENT_SETTLEMENT_REGIME_UTC = datetime(2024, 11, 11, 8, 0, tzinfo=timezone.utc)

# These are index/basket futures rather than a single token with the same-symbol spot leg.  They
# stay in the source ledger but cannot enter the Binance-spot paired test.
NO_SINGLE_ASSET_SPOT_LEG = {
    "BLUEBIRDUSDT": "multi-asset index contract",
    "DEFIUSDT": "multi-asset index contract",
    "FOOTBALLUSDT": "multi-asset index contract",
}

# Binance's 1000-token contracts quote a package of 1,000 underlying spot tokens.  The mapping is
# mechanical and frozen before availability is queried; no missing symbol is manually substituted.
SPOT_MULTIPLIER_PREFIX = "1000"

# Object-level feasibility is deliberately stricter than a raw event count.  Statistical inference
# will use independent settlement clocks, and the current 30-minute settlement regime is primary.
MIN_CURRENT_CLOCKS = 12


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_utc(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def spot_leg(futures_symbol: str) -> dict[str, Any]:
    if futures_symbol in NO_SINGLE_ASSET_SPOT_LEG:
        return {
            "symbol": None,
            "spotUnitsPerFuturesPriceUnit": None,
            "mappingRule": "noSingleAssetSpotLeg",
            "mappingNote": NO_SINGLE_ASSET_SPOT_LEG[futures_symbol],
        }
    if futures_symbol.startswith(SPOT_MULTIPLIER_PREFIX):
        return {
            "symbol": futures_symbol[len(SPOT_MULTIPLIER_PREFIX):],
            "spotUnitsPerFuturesPriceUnit": 1000,
            "mappingRule": "stripFrozen1000ContractPrefix",
            "mappingNote": "one futures price unit represents 1,000 underlying spot tokens",
        }
    return {
        "symbol": futures_symbol,
        "spotUnitsPerFuturesPriceUnit": 1,
        "mappingRule": "exactSameSymbol",
        "mappingNote": None,
    }


def archive_url(market: str, frequency: str, data_type: str, symbol: str, period: str,
                interval: str | None = None) -> str:
    if frequency not in {"monthly", "daily"}:
        raise ValueError(f"unsupported archive frequency: {frequency}")
    segment = "futures/um" if market == "futures" else "spot"
    if interval:
        filename = f"{symbol}-{interval}-{period}.zip"
        return f"{ROOT}/{segment}/{frequency}/{data_type}/{symbol}/{interval}/{filename}"
    filename = f"{symbol}-{data_type}-{period}.zip"
    return f"{ROOT}/{segment}/{frequency}/{data_type}/{symbol}/{filename}"


def daily_book_ticker_url(symbol: str, day: str) -> str:
    filename = f"{symbol}-bookTicker-{day}.zip"
    return f"{ROOT}/futures/um/daily/bookTicker/{symbol}/{filename}"


def request_with_retries(request: urllib.request.Request, read_limit: int | None = None) -> dict[str, Any]:
    last_error: Exception | None = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read(read_limit) if read_limit is not None else b""
                return {
                    "status": response.status,
                    "contentLength": int(response.headers.get("Content-Length", "0")),
                    "lastModified": response.headers.get("Last-Modified"),
                    "body": body,
                }
        except urllib.error.HTTPError as error:
            if error.code in {403, 404}:
                return {
                    "status": error.code,
                    "contentLength": 0,
                    "lastModified": None,
                    "body": b"",
                }
            last_error = error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
        if attempt < 4:
            time.sleep(min(2 ** attempt, 8))
    return {
        "status": None,
        "contentLength": 0,
        "lastModified": None,
        "body": b"",
        "error": str(last_error),
    }


def head(url: str) -> dict[str, Any]:
    response = request_with_retries(
        urllib.request.Request(
            url,
            method="HEAD",
            headers={"Accept-Encoding": "identity", "User-Agent": "prop-strategy-delisting-audit/1"},
        )
    )
    response.pop("body", None)
    return response


def checksum(url: str) -> dict[str, Any]:
    filename = Path(url).name
    response = request_with_retries(
        urllib.request.Request(
            url + ".CHECKSUM",
            headers={"Accept-Encoding": "identity", "User-Agent": "prop-strategy-delisting-audit/1"},
        ),
        read_limit=512,
    )
    body = response.pop("body", b"")
    official_sha: str | None = None
    if response["status"] == 200:
        fields = body.decode("ascii").strip().split()
        if len(fields) < 2 or fields[1].lstrip("*") != filename:
            raise RuntimeError(f"invalid checksum filename for {url}")
        candidate = fields[0].lower()
        if len(candidate) != 64 or any(character not in "0123456789abcdef" for character in candidate):
            raise RuntimeError(f"invalid checksum digest for {url}")
        official_sha = candidate
    response["officialSha256"] = official_sha
    return response


def inspect_object(item: dict[str, Any]) -> dict[str, Any]:
    result = dict(item)
    result["archive"] = head(item["url"])
    # bookTicker is diagnostic and its checksum is still frozen when one exists.
    result["checksum"] = checksum(item["url"])
    return result


def object_ok(item: dict[str, Any]) -> bool:
    return (
        item["archive"]["status"] == 200
        and item["checksum"]["status"] == 200
        and bool(item["checksum"].get("officialSha256"))
    )


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=LEDGER)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--threads", type=int, default=16)
    args = parser.parse_args()
    if args.threads < 1 or args.threads > 32:
        raise ValueError("--threads must be from 1 through 32")

    ledger = json.loads(args.ledger.read_text())
    if ledger.get("outcomeBlind") is not True:
        raise RuntimeError("input ledger is not marked outcome-blind")
    events = ledger["eligibleEventsBeforeHedgeAudit"]

    object_plan: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    event_plan: list[dict[str, Any]] = []
    for event in events:
        futures_symbol = str(event["symbol"])
        settlement = parse_utc(str(event["settlementAtUtc"]))
        period = settlement.strftime("%Y-%m")
        day = settlement.date().isoformat()
        spot = spot_leg(futures_symbol)
        requirements: dict[str, list[str]] = defaultdict(list)

        def require(stream: str, url: str, required: bool, frequency: str,
                    object_period: str) -> None:
            key = (stream, futures_symbol, frequency, object_period)
            if stream == "futuresBookTickerDaily":
                key = (stream, futures_symbol, "daily", day)
            object_plan.setdefault(
                key,
                {
                    "objectId": ":".join(key),
                    "stream": stream,
                    "futuresSymbol": futures_symbol,
                    "spotSymbol": spot["symbol"],
                    "frequency": key[2],
                    "period": key[3],
                    "day": day if stream == "futuresBookTickerDaily" else None,
                    "requiredForObjectGate": required,
                    "url": url,
                },
            )
            if required:
                requirements[stream].append(":".join(key))

        def require_with_daily_fallback(stream: str, market: str, data_type: str,
                                        symbol: str, interval: str | None = None) -> None:
            require(
                stream,
                archive_url(market, "monthly", data_type, symbol, period, interval),
                True,
                "monthly",
                period,
            )
            require(
                stream,
                archive_url(market, "daily", data_type, symbol, day, interval),
                True,
                "daily",
                day,
            )

        require_with_daily_fallback(
            "futuresTradeKlines1m", "futures", "klines", futures_symbol, "1m"
        )
        require_with_daily_fallback(
            "futuresMarkPriceKlines1m", "futures", "markPriceKlines", futures_symbol, "1m"
        )
        require_with_daily_fallback(
            "futuresIndexPriceKlines1m", "futures", "indexPriceKlines", futures_symbol, "1m"
        )
        require_with_daily_fallback(
            "futuresAggTrades", "futures", "aggTrades", futures_symbol
        )
        require(
            "futuresBookTickerDaily",
            daily_book_ticker_url(futures_symbol, day),
            False,
            "daily",
            day,
        )
        if spot["symbol"]:
            require_with_daily_fallback(
                "spotTradeKlines1m", "spot", "klines", spot["symbol"], "1m"
            )
            require_with_daily_fallback(
                "spotAggTrades", "spot", "aggTrades", spot["symbol"]
            )

        event_plan.append(
            {
                "eventId": event["eventId"],
                "futuresSymbol": futures_symbol,
                "spotLeg": spot,
                "releaseAtUtc": event["releaseAtUtc"],
                "noNewOrderAtUtc": event["noNewOrderAtUtc"],
                "settlementAtUtc": event["settlementAtUtc"],
                "settlementIndexAverageMinutes": event["settlementIndexAverageMinutes"],
                "settlementRegime": (
                    "current30MinuteAverage"
                    if settlement >= CURRENT_SETTLEMENT_REGIME_UTC
                    else "legacy60MinuteAverage"
                ),
                "requiredStreamObjectCandidates": dict(requirements),
            }
        )

    objects = sorted(object_plan.values(), key=lambda row: row["objectId"])
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
        checked_objects = list(executor.map(inspect_object, objects))
    checked_objects.sort(key=lambda row: row["objectId"])
    by_id = {row["objectId"]: row for row in checked_objects}

    checked_events: list[dict[str, Any]] = []
    for event in event_plan:
        missing_streams = [
            stream for stream, object_ids in event["requiredStreamObjectCandidates"].items()
            if not any(object_ok(by_id[object_id]) for object_id in object_ids)
        ]
        reasons: list[str] = []
        if event["spotLeg"]["symbol"] is None:
            reasons.append("noSingleAssetBinanceSpotLeg")
        if missing_streams:
            reasons.append("oneOrMoreRequiredArchiveObjectsUnavailable")
        checked_events.append(
            {
                **event,
                "missingRequiredStreams": missing_streams,
                "objectGateEligible": not reasons,
                "objectGateExclusionReasons": reasons,
            }
        )

    def regime_summary(name: str) -> dict[str, Any]:
        population = [row for row in checked_events if row["settlementRegime"] == name]
        eligible = [row for row in population if row["objectGateEligible"]]
        population_clocks = {row["settlementAtUtc"] for row in population}
        eligible_clocks = {row["settlementAtUtc"] for row in eligible}
        return {
            "sourceEvents": len(population),
            "sourceIndependentSettlementClocks": len(population_clocks),
            "objectGateEligibleEvents": len(eligible),
            "objectGateEligibleIndependentSettlementClocks": len(eligible_clocks),
            "eventCoverageFraction": len(eligible) / len(population) if population else 0,
        }

    current = regime_summary("current30MinuteAverage")
    legacy = regime_summary("legacy60MinuteAverage")
    current_pass = (
        current["objectGateEligibleIndependentSettlementClocks"] >= MIN_CURRENT_CLOCKS
    )
    required = [row for row in checked_objects if row["requiredForObjectGate"]]
    optional = [row for row in checked_objects if not row["requiredForObjectGate"]]
    missing_counter = Counter(
        stream
        for event in checked_events
        for stream in event["missingRequiredStreams"]
    )
    exclusion_counter = Counter(
        reason for event in checked_events for reason in event["objectGateExclusionReasons"]
    )
    stream_summary: dict[str, dict[str, int]] = defaultdict(lambda: {"objects": 0, "available": 0})
    for item in checked_objects:
        stream_summary[item["stream"]]["objects"] += 1
        stream_summary[item["stream"]]["available"] += int(object_ok(item))
    stream_requirements = sum(
        len(event["requiredStreamObjectCandidates"]) for event in checked_events
    )
    satisfied_stream_requirements = sum(
        len(event["requiredStreamObjectCandidates"]) - len(event["missingRequiredStreams"])
        for event in checked_events
    )

    output = {
        "schemaVersion": 2,
        "generatedAtUtc": iso_utc(datetime.now(timezone.utc)),
        "outcomeBlind": True,
        "explicitlyExcludedData": [
            "archive ZIP bodies",
            "timestamps inside archives",
            "prices",
            "basis",
            "volume",
            "funding",
            "returns",
        ],
        "input": {
            "ledger": str(args.ledger.relative_to(REPO)),
            "ledgerSha256": file_sha256(args.ledger),
            "termsEligibleEvents": len(events),
        },
        "spotMappingPolicy": {
            "default": "exact same Binance spot symbol",
            "multiplierRule": "strip a leading 1000 and record a 1000:1 unit multiplier",
            "noSingleAssetSymbols": NO_SINGLE_ASSET_SPOT_LEG,
            "noFallbackAliases": True,
        },
        "objectGate": {
            "primarySettlementRegime": "current30MinuteAverage",
            "minimumCurrentIndependentSettlementClocks": MIN_CURRENT_CLOCKS,
            "requiredStreams": [
                "futuresTradeKlines1m",
                "futuresMarkPriceKlines1m",
                "futuresIndexPriceKlines1m",
                "futuresAggTrades",
                "spotTradeKlines1m",
                "spotAggTrades",
            ],
            "optionalDiagnosticStreams": ["futuresBookTickerDaily"],
            "pass": current_pass,
            "importantLimitation": (
                "HTTP 200 plus an official checksum proves only that a containing object exists; "
                "timestamp coverage at the decision/settlement clock remains unproved"
            ),
        },
        "summary": {
            "objects": len(checked_objects),
            "requiredObjectCandidates": len(required),
            "requiredObjectCandidatesAvailable": sum(object_ok(row) for row in required),
            "requiredStreamRequirements": stream_requirements,
            "requiredStreamRequirementsSatisfied": satisfied_stream_requirements,
            "optionalObjects": len(optional),
            "optionalObjectsAvailable": sum(object_ok(row) for row in optional),
            "byStream": dict(sorted(stream_summary.items())),
            "currentRegime": current,
            "legacyRegimeAppendix": legacy,
            "eventExclusions": dict(sorted(exclusion_counter.items())),
            "missingRequiredStreamsByStream": dict(sorted(missing_counter.items())),
        },
        "events": checked_events,
        "objects": checked_objects,
    }
    write_json_atomic(args.output, output)
    print(f"wrote {args.output}")
    print(json.dumps(output["summary"], indent=2, sort_keys=True))
    print(f"OBJECT GATE: {'PASS' if current_pass else 'FAIL'}")


if __name__ == "__main__":
    main()
