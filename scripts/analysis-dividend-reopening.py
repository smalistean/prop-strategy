#!/usr/bin/env python3
"""Frozen one-look screen for IDEA_BOARD #17.

The economic design is frozen in DIVIDEND_REOPENING_PREREGISTRATION.md.  The commands are
deliberately separated so the complete event/control ledger exists before any aggregate result
is calculated or printed:

  selfcheck
  download --directory data/dividend-reopening
  ledger --directory data/dividend-reopening
  summarize

Downloaded market archives live under ignored ``data/``.  Only compact provenance, ledgers and
results belong under ``research/``.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import io
import json
import math
import statistics as st
import time
import urllib.error
import urllib.request
import zipfile
from collections import defaultdict
from datetime import date, datetime, time as wall_time, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo


REPO = Path(__file__).resolve().parent.parent
SCRIPT = Path(__file__).resolve()
PREREGISTRATION = REPO / "DIVIDEND_REOPENING_PREREGISTRATION.md"
INVENTORY = REPO / "research" / "dividend-reopening-feasibility-2026-09-12.json"
AVAILABILITY = REPO / "research" / "dividend-reopening-archive-availability-2026-09-12.json"
CODE_FREEZE = REPO / "research" / "dividend-reopening-code-freeze-2026-09-13.json"
DEFAULT_DIRECTORY = REPO / "data" / "dividend-reopening"
DEFAULT_DOWNLOAD_MANIFEST = REPO / "research" / "dividend-reopening-download-manifest-2026-09-13.json"
DEFAULT_LEDGER = REPO / "research" / "dividend-reopening-ledger-2026-09-13.json"
DEFAULT_RESULT = REPO / "research" / "dividend-reopening-result-2026-09-13.json"

FROZEN_PREREGISTRATION_SHA256 = "f0e2baed7810f36e7951d8d06682087adfa1e1a1eec1a646a7a95ad19c08ef1e"
FROZEN_INVENTORY_SHA256 = "7294a753d8a3b1300474cb876bdd07e5ccff403cf0cbbb165adaf8371ec20786"
FROZEN_AVAILABILITY_SHA256 = "5549244bf26ebee72962f88c3a150ed58b1e4ae0aabb7f166d76458b6a29e8a6"

NY = ZoneInfo("America/New_York")
UTC = timezone.utc
REGIME_START = date(2026, 5, 16)
DATA_CUTOFF_UTC = datetime(2026, 9, 12, tzinfo=UTC)
USDT_CONVERSION_DATE = date(2026, 7, 16)
MARK_METHOD_DATE = date(2026, 8, 31)
TRIGGER_BP = Decimal("50")
PRIMARY_COST_BP = Decimal("25")
SENSITIVITY_COSTS_BP = (Decimal("13"), Decimal("40"))
MATCH_RATIO_MIN = Decimal("0.8")
MATCH_RATIO_MAX = Decimal("1.25")
MATCH_BALANCE_MIN = Decimal("0.9")
MATCH_BALANCE_MAX = Decimal("1.1")

TRADE_CLOCKS = {
    "pMinus": (19, 58),
    "pPlus": (20, 1),
    "entry": (20, 3),
    "exit5": (20, 8),
    "exit30": (20, 33),
}
PRIMARY_EXIT_CLOCKS = [(20, minute) for minute in range(18, 24)]
MARK_CLOCKS = {"markSettlement": (20, 0)}
INDEX_CLOCKS = {
    "indexMinus": (19, 58),
    "indexPlus": (20, 1),
    "indexExit": (20, 18),
}


def iso_utc(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_frozen_file(path: Path, expected: str) -> bytes:
    raw = path.read_bytes()
    actual = hashlib.sha256(raw).hexdigest()
    if actual != expected:
        raise RuntimeError(f"frozen file mismatch for {path}: expected {expected}, got {actual}")
    return raw


def write_json_new(path: Path, value: Any) -> None:
    if path.exists():
        raise RuntimeError(f"refusing to overwrite one-look artifact: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")
    temporary.replace(path)


def frozen_checksum_set_sha256() -> tuple[str, int]:
    availability = json.loads(AVAILABILITY.read_text())
    lines = sorted(
        f"{item['url']} {item['checksum']['officialSha256']}\n"
        for item in availability["objects"]
        if item["archive"]["status"] == 200 and item["checksum"]["status"] == 200
    )
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest(), len(lines)


def verify_code_freeze() -> tuple[str, dict[str, Any]]:
    raw = CODE_FREEZE.read_bytes()
    freeze_sha = hashlib.sha256(raw).hexdigest()
    frozen = json.loads(raw)
    if frozen.get("schemaVersion") != 1 or frozen.get("outcomeBlind") is not True:
        raise RuntimeError("invalid or non-outcome-blind code freeze")
    expected = {
        "analysisScriptSha256": file_sha256(SCRIPT),
        "preregistrationSha256": file_sha256(PREREGISTRATION),
        "inventorySha256": file_sha256(INVENTORY),
        "availabilitySha256": file_sha256(AVAILABILITY),
    }
    for field, actual in expected.items():
        if frozen.get(field) != actual:
            raise RuntimeError(f"code freeze mismatch for {field}: {actual} != {frozen.get(field)}")
    checksum_set_sha, checksum_count = frozen_checksum_set_sha256()
    if frozen.get("availableArchiveChecksums") != checksum_count or checksum_count != 235:
        raise RuntimeError("code freeze does not bind all 235 available archive checksums")
    if frozen.get("archiveChecksumSetSha256") != checksum_set_sha:
        raise RuntimeError("code freeze archive-checksum set mismatch")
    return freeze_sha, frozen


def relative_or_absolute(path: Path) -> str:
    resolved = path.resolve()
    try:
        return str(resolved.relative_to(REPO))
    except ValueError:
        return str(resolved)


def resolve_recorded_path(value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else REPO / path


def ny_epoch_millis(process_date: date, hour: int, minute: int) -> int:
    local = datetime.combine(process_date, wall_time(hour, minute), NY)
    return int(local.timestamp() * 1_000)


def normalize_archive_timestamp(raw: str) -> tuple[int, str]:
    value = int(raw)
    if value >= 100_000_000_000_000:
        if value % 1_000:
            raise ValueError(f"microsecond timestamp is not millisecond-aligned: {value}")
        return value // 1_000, "microseconds"
    if value >= 100_000_000_000:
        return value, "milliseconds"
    if value >= 100_000_000:
        return value * 1_000, "seconds"
    raise ValueError(f"unsupported epoch timestamp: {value}")


def archive_target(directory: Path, item: dict[str, Any]) -> Path:
    filename = Path(str(item["url"])).name
    return directory / str(item["stream"]) / str(item["frequency"]) / str(item["symbol"]) / filename


def download_archive(item: dict[str, Any], directory: Path) -> dict[str, Any]:
    target = archive_target(directory, item)
    target.parent.mkdir(parents=True, exist_ok=True)
    expected_sha = str(item["checksum"]["officialSha256"])
    if len(expected_sha) != 64:
        raise RuntimeError(f"missing frozen official checksum for {target.name}")
    expected_bytes = int(item["archive"]["contentLength"])
    frozen_last_modified = item["archive"]["lastModified"]

    if target.is_file() and file_sha256(target) == expected_sha:
        size = target.stat().st_size
        if expected_bytes and size != expected_bytes:
            raise RuntimeError(f"frozen byte count changed for {target.name}: {size} != {expected_bytes}")
        return {
            **item,
            "relativePath": relative_or_absolute(target),
            "expectedSha256": expected_sha,
            "actualSha256": expected_sha,
            "bytes": size,
            "reused": True,
        }

    partial = target.with_suffix(target.suffix + ".part")
    for attempt in range(5):
        existing = partial.stat().st_size if partial.is_file() else 0
        headers = {
            "User-Agent": "prop-strategy-dividend-screen/1",
            "Accept-Encoding": "identity",
        }
        if existing:
            headers["Range"] = f"bytes={existing}-"
        request = urllib.request.Request(str(item["url"]), headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status not in (200, 206):
                    raise RuntimeError(f"archive GET returned HTTP {response.status}")
                if response.headers.get("Last-Modified") != frozen_last_modified:
                    raise RuntimeError(f"archive vintage changed for {target.name}")
                append = existing > 0 and response.status == 206
                mode = "ab" if append else "wb"
                with partial.open(mode) as output:
                    while True:
                        block = response.read(1 << 20)
                        if not block:
                            break
                        output.write(block)
            break
        except urllib.error.HTTPError as error:
            if error.code == 416 and partial.exists():
                partial.unlink()
            if attempt == 4:
                raise
            time.sleep(2**attempt)
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 4:
                raise
            time.sleep(2**attempt)
    size = partial.stat().st_size
    if expected_bytes and size != expected_bytes:
        raise RuntimeError(f"frozen byte count changed for {target.name}: {size} != {expected_bytes}")
    actual_sha = file_sha256(partial)
    if actual_sha != expected_sha:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"checksum mismatch for {target.name}: {actual_sha} != {expected_sha}")
    partial.replace(target)
    return {
        **item,
        "relativePath": relative_or_absolute(target),
        "expectedSha256": expected_sha,
        "actualSha256": actual_sha,
        "bytes": size,
        "reused": False,
    }


def download_command(directory: Path, manifest_path: Path) -> None:
    code_freeze_sha, code_freeze = verify_code_freeze()
    verify_frozen_file(PREREGISTRATION, FROZEN_PREREGISTRATION_SHA256)
    raw_availability = verify_frozen_file(AVAILABILITY, FROZEN_AVAILABILITY_SHA256)
    availability = json.loads(raw_availability)
    available = [
        item for item in availability["objects"]
        if item["archive"]["status"] == 200 and item["checksum"]["status"] == 200
    ]
    unavailable = [item for item in availability["objects"] if item not in available]
    downloaded: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(download_archive, item, directory): item for item in available}
        for number, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            downloaded.append(future.result())
            if number % 25 == 0 or number == len(futures):
                print(f"downloaded/verified {number}/{len(futures)}", flush=True)
    downloaded.sort(key=lambda row: (row["period"], row["symbol"], row["stream"]))

    manifest = {
        "schemaVersion": 1,
        "createdAtUtc": iso_utc(datetime.now(UTC)),
        "codeFreeze": relative_or_absolute(CODE_FREEZE),
        "codeFreezeSha256": code_freeze_sha,
        "analysisScriptSha256": code_freeze["analysisScriptSha256"],
        "preregistration": relative_or_absolute(PREREGISTRATION),
        "preregistrationSha256": FROZEN_PREREGISTRATION_SHA256,
        "availability": relative_or_absolute(AVAILABILITY),
        "availabilitySha256": FROZEN_AVAILABILITY_SHA256,
        "directory": relative_or_absolute(directory),
        "summary": {
            "availableObjects": len(downloaded),
            "unavailableObjects": len(unavailable),
            "bytes": sum(int(row["bytes"]) for row in downloaded),
            "reusedObjects": sum(bool(row["reused"]) for row in downloaded),
            "allAvailableObjectsVerified": len(downloaded) == len(available),
        },
        "archives": downloaded,
        "unavailable": unavailable,
    }
    write_json_new(manifest_path, manifest)
    print(f"manifest {manifest_path} SHA256 {file_sha256(manifest_path)}")


def required_targets(events: list[dict[str, Any]]) -> tuple[set[tuple[str, str, int]], dict[str, list[str]]]:
    targets: set[tuple[str, str, int]] = set()
    parents: dict[str, list[str]] = defaultdict(list)
    event_dates_by_symbol: dict[str, set[date]] = defaultdict(set)
    for event in events:
        event_dates_by_symbol[str(event["symbol"])].add(date.fromisoformat(str(event["processDateNy"])))

    for event in events:
        symbol = str(event["symbol"])
        process_date = date.fromisoformat(str(event["processDateNy"]))
        event_id = f"{process_date.isoformat()}:{symbol}"
        for hour, minute in TRADE_CLOCKS.values():
            targets.add((symbol, "trade", ny_epoch_millis(process_date, hour, minute)))
        for hour, minute in PRIMARY_EXIT_CLOCKS:
            targets.add((symbol, "trade", ny_epoch_millis(process_date, hour, minute)))
        for hour, minute in MARK_CLOCKS.values():
            targets.add((symbol, "mark", ny_epoch_millis(process_date, hour, minute)))
        for hour, minute in INDEX_CLOCKS.values():
            targets.add((symbol, "index", ny_epoch_millis(process_date, hour, minute)))
        for hour, minute in PRIMARY_EXIT_CLOCKS:
            targets.add((symbol, "index", ny_epoch_millis(process_date, hour, minute)))
        for weeks in range(1, 9):
            control_date = process_date - timedelta(days=7 * weeks)
            if control_date < REGIME_START or control_date in event_dates_by_symbol[symbol]:
                continue
            control_id = f"{control_date.isoformat()}:{symbol}"
            parents[control_id].append(event_id)
            for hour, minute in TRADE_CLOCKS.values():
                targets.add((symbol, "trade", ny_epoch_millis(control_date, hour, minute)))
            for hour, minute in PRIMARY_EXIT_CLOCKS:
                targets.add((symbol, "trade", ny_epoch_millis(control_date, hour, minute)))
    return targets, {key: sorted(set(value)) for key, value in sorted(parents.items())}


def archive_object_key(symbol: str, stream: str, epoch_millis: int) -> tuple[str, str, str, str]:
    utc_date = datetime.fromtimestamp(epoch_millis / 1_000, UTC).date()
    month = utc_date.strftime("%Y-%m")
    frequency, period = ("monthly", month) if month < "2026-09" else ("daily", utc_date.isoformat())
    return symbol, frequency, period, stream


def parse_needed_bars(
    download_manifest: dict[str, Any], targets: set[tuple[str, str, int]]
) -> tuple[dict[tuple[str, str, int], dict[str, Any]], dict[str, Any]]:
    bars: dict[tuple[str, str, int], dict[str, Any]] = {}
    duplicate_identical = 0
    timestamp_units: dict[str, int] = defaultdict(int)
    archives_read = 0
    rows_read = 0
    object_keys: set[tuple[str, str, str, str]] = set()

    for archive in download_manifest["archives"]:
        path = resolve_recorded_path(str(archive["relativePath"]))
        actual_sha = file_sha256(path)
        if actual_sha != archive["expectedSha256"] or actual_sha != archive["actualSha256"]:
            raise RuntimeError(f"download-manifest checksum mismatch for {path}")
        symbol = str(archive["symbol"])
        stream = str(archive["stream"])
        object_key = (symbol, str(archive["frequency"]), str(archive["period"]), stream)
        if object_key in object_keys:
            raise RuntimeError(f"duplicate archive object in manifest: {object_key}")
        object_keys.add(object_key)
        wanted_epochs = {epoch for wanted_symbol, wanted_stream, epoch in targets if wanted_symbol == symbol and wanted_stream == stream}
        with zipfile.ZipFile(path) as zipped:
            members = [member for member in zipped.infolist() if not member.is_dir()]
            if len(members) != 1 or not members[0].filename.lower().endswith(".csv"):
                raise RuntimeError(f"expected exactly one CSV member in {path}")
            with zipped.open(members[0]) as binary:
                reader = csv.reader(io.TextIOWrapper(binary, encoding="utf-8-sig", newline=""))
                seen_archive_rows: dict[int, tuple[str, ...]] = {}
                for row_number, row in enumerate(reader, start=1):
                    if not row:
                        continue
                    try:
                        epoch, unit = normalize_archive_timestamp(row[0])
                    except ValueError:
                        if row_number == 1 and row[0].strip().lower() in {"open_time", "open time"}:
                            continue
                        raise RuntimeError(f"invalid timestamp at {path}:{row_number}")
                    rows_read += 1
                    timestamp_units[unit] += 1
                    if archive_object_key(symbol, stream, epoch) != object_key:
                        raise RuntimeError(f"timestamp outside declared archive period at {path}:{row_number}")
                    canonical = tuple(field.strip() for field in row)
                    prior = seen_archive_rows.get(epoch)
                    if prior is None:
                        seen_archive_rows[epoch] = canonical
                    elif prior == canonical:
                        duplicate_identical += 1
                        continue
                    else:
                        raise RuntimeError(f"conflicting duplicate timestamp at {path}:{row_number}")
                    if epoch not in wanted_epochs:
                        continue
                    if len(row) < 5:
                        raise RuntimeError(f"short kline row at {path}:{row_number}")
                    parsed: dict[str, Any] = {
                        "open": str(Decimal(row[1])),
                        "high": str(Decimal(row[2])),
                        "low": str(Decimal(row[3])),
                        "close": str(Decimal(row[4])),
                    }
                    if stream == "trade":
                        if len(row) < 9:
                            raise RuntimeError(f"short trade-kline row at {path}:{row_number}")
                        parsed.update(
                            {
                                "volume": str(Decimal(row[5])),
                                "quoteVolume": str(Decimal(row[7])),
                                "tradeCount": int(row[8]),
                            }
                        )
                    key = (symbol, stream, epoch)
                    if key in bars:
                        raise RuntimeError(f"target timestamp appears in multiple archive objects: {key}")
                    bars[key] = parsed
        archives_read += 1

    return bars, {
        "archivesRead": archives_read,
        "rowsRead": rows_read,
        "targets": len(targets),
        "targetsFound": len(bars),
        "targetsMissing": len(targets - set(bars)),
        "identicalDuplicateRows": duplicate_identical,
        "timestampUnits": dict(sorted(timestamp_units.items())),
    }


def get_bar(
    bars: dict[tuple[str, str, int], dict[str, Any]], symbol: str, stream: str,
    process_date: date, clock: tuple[int, int]
) -> dict[str, Any] | None:
    return bars.get((symbol, stream, ny_epoch_millis(process_date, *clock)))


def decimal_field(bar: dict[str, Any], field: str) -> Decimal:
    return Decimal(str(bar[field]))


def bar_issue(
    bar: dict[str, Any] | None,
    selected_field: str,
    symbol: str,
    stream: str,
    process_date: date,
    clock: tuple[int, int],
    unavailable_objects: set[tuple[str, str, str, str]],
    require_trade: bool,
) -> str | None:
    if bar is None:
        key = archive_object_key(symbol, stream, ny_epoch_millis(process_date, *clock))
        return "sourceArchiveUnavailable" if key in unavailable_objects else "minuteMissing"
    if decimal_field(bar, selected_field) <= 0:
        return "selectedPriceNonPositive"
    if require_trade and (decimal_field(bar, "volume") <= 0 or int(bar["tradeCount"]) <= 0):
        return "inactiveTradeBar"
    return None


def bp(value: Decimal) -> float:
    return float(value.quantize(Decimal("0.000000001")))


def pnl_fields(direction: int, entry: Decimal, exit_price: Decimal, prefix: str = "") -> dict[str, Any]:
    gross = Decimal(direction) * (exit_price / entry - 1) * Decimal(10_000)
    gross_key = f"{prefix}GrossBp" if prefix else "grossBp"
    net13_key = f"{prefix}Net13Bp" if prefix else "net13Bp"
    net25_key = f"{prefix}Net25Bp" if prefix else "net25Bp"
    net40_key = f"{prefix}Net40Bp" if prefix else "net40Bp"
    net13 = gross - Decimal("13")
    net25 = gross - PRIMARY_COST_BP
    net40 = gross - Decimal("40")
    return {
        gross_key: bp(gross),
        gross_key + "Exact": str(gross),
        net13_key: bp(net13),
        net13_key + "Exact": str(net13),
        net25_key: bp(net25),
        net25_key + "Exact": str(net25),
        net40_key: bp(net40),
        net40_key + "Exact": str(net40),
    }


def regime_key(process_date: date) -> tuple[str, str]:
    return (
        "preUsdtConversion" if process_date < USDT_CONVERSION_DATE else "fromUsdtConversion",
        "preMarkMethod" if process_date < MARK_METHOD_DATE else "fromMarkMethod",
    )


def resolve_execution(
    symbol: str,
    process_date: date,
    bars: dict[tuple[str, str, int], dict[str, Any]],
    unavailable_objects: set[tuple[str, str, str, str]],
) -> tuple[dict[str, Any] | None, dict[str, Any] | None, tuple[int, int] | None, str | None]:
    entry_clock = TRADE_CLOCKS["entry"]
    entry = get_bar(bars, symbol, "trade", process_date, entry_clock)
    entry_issue = bar_issue(
        entry, "open", symbol, "trade", process_date, entry_clock, unavailable_objects, True
    )
    if entry_issue:
        return None, None, None, f"entry:{entry_issue}"
    exit_issues: list[str] = []
    for clock in PRIMARY_EXIT_CLOCKS:
        candidate = get_bar(bars, symbol, "trade", process_date, clock)
        issue = bar_issue(
            candidate, "open", symbol, "trade", process_date, clock, unavailable_objects, True
        )
        if issue is None:
            return entry, candidate, clock, None
        exit_issues.append(f"{clock[0]:02d}:{clock[1]:02d}:{issue}")
    return entry, None, None, "noExecutableExit[" + ",".join(exit_issues) + "]"


def optional_pnl(
    row: dict[str, Any],
    trade: dict[str, dict[str, Any] | None],
    direction: int,
    entry_price: Decimal,
    symbol: str,
    process_date: date,
    unavailable_objects: set[tuple[str, str, str, str]],
) -> None:
    for name, prefix in (("exit5", "exit5"), ("exit30", "exit30")):
        issue = bar_issue(
            trade[name], "open", symbol, "trade", process_date, TRADE_CLOCKS[name],
            unavailable_objects, True
        )
        if issue is None:
            row.update(pnl_fields(direction, entry_price, decimal_field(trade[name], "open"), prefix))


def build_event_row(
    event: dict[str, Any],
    bars: dict[tuple[str, str, int], dict[str, Any]],
    unavailable_objects: set[tuple[str, str, str, str]],
) -> dict[str, Any]:
    symbol = str(event["symbol"])
    process_date = date.fromisoformat(str(event["processDateNy"]))
    row: dict[str, Any] = {
        **event,
        "eventId": f"{process_date.isoformat()}:{symbol}",
        "regime": list(regime_key(process_date)),
    }
    trade = {name: get_bar(bars, symbol, "trade", process_date, clock) for name, clock in TRADE_CLOCKS.items()}
    mark = get_bar(bars, symbol, "mark", process_date, MARK_CLOCKS["markSettlement"])
    signal_issues = {
        "pMinus": bar_issue(
            trade["pMinus"], "close", symbol, "trade", process_date, TRADE_CLOCKS["pMinus"],
            unavailable_objects, True
        ),
        "pPlus": bar_issue(
            trade["pPlus"], "close", symbol, "trade", process_date, TRADE_CLOCKS["pPlus"],
            unavailable_objects, True
        ),
        "markSettlement": bar_issue(
            mark, "open", symbol, "mark", process_date, MARK_CLOCKS["markSettlement"],
            unavailable_objects, False
        ),
    }
    signal_issues = {key: value for key, value in signal_issues.items() if value is not None}
    if signal_issues:
        row.update(
            {
                "signalUsable": False,
                "signalUnusableReasons": signal_issues,
                "triggered": None,
                "executionFailure": None,
            }
        )
        return row

    p_minus = decimal_field(trade["pMinus"], "close")
    p_plus = decimal_field(trade["pPlus"], "close")
    mark_proxy = decimal_field(mark, "open")
    special_rate = Decimal(str(event["specialRate"]))
    dividend_hat = -special_rate * mark_proxy
    gap = (p_plus - p_minus + dividend_hat) / p_minus * Decimal(10_000)
    proxy_dividend = -special_rate * p_minus
    proxy_gap = (p_plus - p_minus + proxy_dividend) / p_minus * Decimal(10_000)
    triggered = abs(gap) >= TRIGGER_BP
    direction = -1 if gap > 0 else (1 if gap < 0 else 0)
    proxy_triggered = abs(proxy_gap) >= TRIGGER_BP
    proxy_direction = -1 if proxy_gap > 0 else (1 if proxy_gap < 0 else 0)
    row.update(
        {
            "signalUsable": True,
            "signalUnusableReasons": {},
            "pMinus": str(p_minus),
            "pPlus": str(p_plus),
            "markProxy": str(mark_proxy),
            "dividendHat": str(dividend_hat),
            "gapBpExact": str(gap),
            "gapBp": bp(gap),
            "pMinusProxyGapBpExact": str(proxy_gap),
            "pMinusProxyGapBp": bp(proxy_gap),
            "pMinusProxyTriggered": proxy_triggered,
            "pMinusProxyDirectionSign": proxy_direction if proxy_triggered else None,
            "pMinusProxyDirection": ("long" if proxy_direction > 0 else "short") if proxy_triggered else None,
            "markVsPMinusBp": bp((mark_proxy / p_minus - 1) * Decimal(10_000)),
            "triggered": triggered,
            "directionSign": direction if triggered else None,
            "direction": ("long" if direction > 0 else "short") if triggered else None,
            "executionFailure": None,
            "pMinusProxyExecutionFailure": None,
        }
    )

    entry, exit_bar, exit_clock, execution_issue = resolve_execution(
        symbol, process_date, bars, unavailable_objects
    ) if (triggered or proxy_triggered) else (None, None, None, None)
    if triggered and execution_issue:
        row["executionFailure"] = execution_issue
    if proxy_triggered and execution_issue:
        row["pMinusProxyExecutionFailure"] = execution_issue
    if execution_issue is None and entry is not None and exit_bar is not None and exit_clock is not None:
        entry_price = decimal_field(entry, "open")
        exit_price = decimal_field(exit_bar, "open")
        row.update(
            {
                "entryPrice": str(entry_price),
                "exitPrice": str(exit_price),
                "exitClockNy": f"{exit_clock[0]:02d}:{exit_clock[1]:02d}",
                "exitDelayMinutes": exit_clock[1] - 18,
                "entryQuoteVolume": entry["quoteVolume"],
                "entryTradeCount": entry["tradeCount"],
                "entryAdjustedDisplacementBp": bp(
                    (entry_price - p_minus + dividend_hat) / p_minus * Decimal(10_000)
                ),
                "exitAdjustedDisplacementBp": bp(
                    (exit_price - p_minus + dividend_hat) / p_minus * Decimal(10_000)
                ),
            }
        )
        if triggered:
            row.update(pnl_fields(direction, entry_price, exit_price))
            optional_pnl(
                row, trade, direction, entry_price, symbol, process_date, unavailable_objects
            )
        if proxy_triggered:
            proxy_pnl = pnl_fields(proxy_direction, entry_price, exit_price, "pMinusProxy")
            row.update(proxy_pnl)

    index_minus = get_bar(bars, symbol, "index", process_date, INDEX_CLOCKS["indexMinus"])
    index_plus = get_bar(bars, symbol, "index", process_date, INDEX_CLOCKS["indexPlus"])
    index_exit = get_bar(bars, symbol, "index", process_date, INDEX_CLOCKS["indexExit"])
    if bar_issue(index_minus, "close", symbol, "index", process_date, INDEX_CLOCKS["indexMinus"], unavailable_objects, False) is None:
        value = decimal_field(index_minus, "close")
        row["indexMinus"] = str(value)
        row["perpIndexMinusBp"] = bp((p_minus / value - 1) * Decimal(10_000))
    if bar_issue(index_plus, "close", symbol, "index", process_date, INDEX_CLOCKS["indexPlus"], unavailable_objects, False) is None:
        value = decimal_field(index_plus, "close")
        row["indexPlus"] = str(value)
        row["perpIndexPlusBp"] = bp((p_plus / value - 1) * Decimal(10_000))
    if exit_clock is not None:
        index_exit = get_bar(bars, symbol, "index", process_date, exit_clock)
    if exit_clock is not None and bar_issue(index_exit, "open", symbol, "index", process_date, exit_clock, unavailable_objects, False) is None:
        value = decimal_field(index_exit, "open")
        row["indexExit"] = str(value)
        row["perpIndexExitBp"] = bp((exit_price / value - 1) * Decimal(10_000))
    return row


def build_control_row(
    control_id: str,
    parent_event_ids: list[str],
    bars: dict[tuple[str, str, int], dict[str, Any]],
    unavailable_objects: set[tuple[str, str, str, str]],
) -> dict[str, Any]:
    date_text, symbol = control_id.split(":", 1)
    process_date = date.fromisoformat(date_text)
    row: dict[str, Any] = {
        "controlId": control_id,
        "symbol": symbol,
        "processDateNy": date_text,
        "parentEventIds": parent_event_ids,
        "regime": list(regime_key(process_date)),
    }
    trade = {name: get_bar(bars, symbol, "trade", process_date, clock) for name, clock in TRADE_CLOCKS.items()}
    signal_issues = {
        name: bar_issue(
            trade[name], "close", symbol, "trade", process_date, TRADE_CLOCKS[name],
            unavailable_objects, True
        )
        for name in ("pMinus", "pPlus")
    }
    signal_issues = {key: value for key, value in signal_issues.items() if value is not None}
    if signal_issues:
        row.update(
            {
                "signalUsable": False,
                "signalUnusableReasons": signal_issues,
                "standaloneTriggered": None,
                "executionFailure": None,
            }
        )
        return row
    p_minus = decimal_field(trade["pMinus"], "close")
    p_plus = decimal_field(trade["pPlus"], "close")
    gap = (p_plus / p_minus - 1) * Decimal(10_000)
    direction = -1 if gap > 0 else (1 if gap < 0 else 0)
    row.update(
        {
            "signalUsable": True,
            "signalUnusableReasons": {},
            "pMinus": str(p_minus),
            "pPlus": str(p_plus),
            "gapBpExact": str(gap),
            "gapBp": bp(gap),
            "directionSign": direction if direction else None,
            "direction": ("long" if direction > 0 else "short") if direction else None,
            "standaloneTriggered": abs(gap) >= TRIGGER_BP,
            "executionFailure": None,
        }
    )
    if direction:
        entry, exit_bar, exit_clock, execution_issue = resolve_execution(
            symbol, process_date, bars, unavailable_objects
        )
        if execution_issue:
            row["executionFailure"] = execution_issue
        else:
            assert entry is not None and exit_bar is not None and exit_clock is not None
            entry_price = decimal_field(entry, "open")
            exit_price = decimal_field(exit_bar, "open")
            row.update(
                {
                    "entryPrice": str(entry_price),
                    "exitPrice": str(exit_price),
                    "exitClockNy": f"{exit_clock[0]:02d}:{exit_clock[1]:02d}",
                    "exitDelayMinutes": exit_clock[1] - 18,
                    "entryQuoteVolume": entry["quoteVolume"],
                    "entryTradeCount": entry["tradeCount"],
                }
            )
            row.update(pnl_fields(direction, entry_price, exit_price))
            optional_pnl(
                row, trade, direction, entry_price, symbol, process_date, unavailable_objects
            )
    return row


def choose_control(
    event: dict[str, Any], controls: list[dict[str, Any]], used_control_ids: set[str]
) -> dict[str, Any] | None:
    event_gap = Decimal(str(event["gapBpExact"]))
    event_regime = regime_key(date.fromisoformat(str(event["processDateNy"])))
    candidates: list[tuple[Decimal, int, dict[str, Any], Decimal]] = []
    for control in controls:
        if (
            control["controlId"] in used_control_ids
            or control.get("signalUsable") is not True
            or control.get("directionSign") != event["directionSign"]
            or tuple(control["regime"]) != event_regime
        ):
            continue
        control_gap = Decimal(str(control["gapBpExact"]))
        ratio = abs(control_gap) / abs(event_gap)
        if MATCH_RATIO_MIN <= ratio <= MATCH_RATIO_MAX:
            distance = abs(abs(control_gap) - abs(event_gap))
            ordinal = date.fromisoformat(str(control["processDateNy"])).toordinal()
            candidates.append((distance, -ordinal, control, ratio))
    if not candidates:
        return None
    distance, _, control, ratio = min(candidates, key=lambda item: (item[0], item[1]))
    return {"control": control, "ratio": ratio, "distance": distance}


def load_and_validate_download_manifest(
    download_manifest_path: Path, directory: Path
) -> tuple[dict[str, Any], str, dict[str, Any], str, dict[str, Any]]:
    code_freeze_sha, code_freeze = verify_code_freeze()
    verify_frozen_file(PREREGISTRATION, FROZEN_PREREGISTRATION_SHA256)
    verify_frozen_file(INVENTORY, FROZEN_INVENTORY_SHA256)
    availability = json.loads(verify_frozen_file(AVAILABILITY, FROZEN_AVAILABILITY_SHA256))
    raw_download_manifest = download_manifest_path.read_bytes()
    download_manifest_sha = hashlib.sha256(raw_download_manifest).hexdigest()
    download_manifest = json.loads(raw_download_manifest)
    if download_manifest["preregistrationSha256"] != FROZEN_PREREGISTRATION_SHA256:
        raise RuntimeError("download manifest does not belong to the frozen preregistration")
    if download_manifest["availabilitySha256"] != FROZEN_AVAILABILITY_SHA256:
        raise RuntimeError("download manifest does not belong to the frozen availability plan")
    if download_manifest.get("codeFreezeSha256") != code_freeze_sha:
        raise RuntimeError("download manifest code-freeze hash mismatch")
    if download_manifest.get("analysisScriptSha256") != code_freeze["analysisScriptSha256"]:
        raise RuntimeError("download manifest analysis-script hash mismatch")
    if resolve_recorded_path(str(download_manifest["directory"])).resolve() != directory.resolve():
        raise RuntimeError("download directory differs from the source manifest")
    expected_by_url = {
        str(item["url"]): item
        for item in availability["objects"]
        if item["archive"]["status"] == 200 and item["checksum"]["status"] == 200
    }
    manifest_urls = {str(item["url"]) for item in download_manifest["archives"]}
    if len(download_manifest["archives"]) != len(manifest_urls) or manifest_urls != set(expected_by_url):
        raise RuntimeError("download manifest does not contain the exact frozen available-object set")
    for archive in download_manifest["archives"]:
        frozen = expected_by_url[str(archive["url"])]
        for field in ("symbol", "frequency", "period", "stream", "url", "checksumUrl", "scopes"):
            if archive.get(field) != frozen.get(field):
                raise RuntimeError(f"archive metadata differs from frozen {field}: {archive['url']}")
        if archive.get("archive") != frozen.get("archive") or archive.get("checksum") != frozen.get("checksum"):
            raise RuntimeError(f"archive HTTP/checksum metadata differs from frozen plan: {archive['url']}")
        if archive["expectedSha256"] != frozen["checksum"]["officialSha256"]:
            raise RuntimeError(f"archive checksum is not the frozen official value: {archive['url']}")
        if archive.get("actualSha256") != archive["expectedSha256"]:
            raise RuntimeError(f"archive actual SHA-256 differs from expected: {archive['url']}")
        if int(archive["bytes"]) != int(frozen["archive"]["contentLength"]):
            raise RuntimeError(f"archive byte count differs from frozen metadata: {archive['url']}")
        expected_path = archive_target(directory, frozen).resolve()
        if resolve_recorded_path(str(archive["relativePath"])).resolve() != expected_path:
            raise RuntimeError(f"archive path differs from canonical target: {archive['url']}")
    return download_manifest, download_manifest_sha, availability, code_freeze_sha, code_freeze


def ledger_constants() -> dict[str, Any]:
    return {
        "regimeStartNy": REGIME_START.isoformat(),
        "dataCutoffExclusiveUtc": iso_utc(DATA_CUTOFF_UTC),
        "triggerBp": float(TRIGGER_BP),
        "primaryCostBp": float(PRIMARY_COST_BP),
        "sensitivityCostsBp": [float(value) for value in SENSITIVITY_COSTS_BP],
        "matchRatio": [float(MATCH_RATIO_MIN), float(MATCH_RATIO_MAX)],
        "matchBalanceRatio": [float(MATCH_BALANCE_MIN), float(MATCH_BALANCE_MAX)],
        "tradeClocksNy": {
            key: f"{hour:02d}:{minute:02d}" for key, (hour, minute) in TRADE_CLOCKS.items()
        },
        "primaryExitClocksNy": [f"{hour:02d}:{minute:02d}" for hour, minute in PRIMARY_EXIT_CLOCKS],
        "markClockNy": "20:00",
        "usdtConversionDateNy": USDT_CONVERSION_DATE.isoformat(),
        "markMethodDateNy": MARK_METHOD_DATE.isoformat(),
        "controlReuse": "without-replacement; eventId ascending",
    }


def construct_ledgers(
    download_manifest: dict[str, Any], availability: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    inventory = json.loads(INVENTORY.read_text())

    events = [
        event for event in inventory["events"]
        if date.fromisoformat(str(event["processDateNy"])) >= REGIME_START
    ]
    if len(events) != 50 or len({event["processDateNy"] for event in events}) != 31:
        raise RuntimeError("frozen primary population is not 50 events / 31 dates")
    targets, control_parents = required_targets(events)
    if len(control_parents) != 310:
        raise RuntimeError(f"frozen control population is not 310: got {len(control_parents)}")
    bars, parsing = parse_needed_bars(download_manifest, targets)
    unavailable_objects = {
        (str(item["symbol"]), str(item["frequency"]), str(item["period"]), str(item["stream"]))
        for item in availability["objects"]
        if item["archive"]["status"] != 200 or item["checksum"]["status"] != 200
    }

    event_ledger = [build_event_row(event, bars, unavailable_objects) for event in events]
    control_ledger = [
        build_control_row(key, parents, bars, unavailable_objects)
        for key, parents in control_parents.items()
    ]
    controls_by_parent: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for control in control_ledger:
        for event_id in control["parentEventIds"]:
            controls_by_parent[event_id].append(control)
    matched_by_control: dict[str, list[str]] = defaultdict(list)
    selected_by_control: dict[str, list[str]] = defaultdict(list)
    used_control_ids: set[str] = set()
    for event in sorted(event_ledger, key=lambda row: row["eventId"]):
        event["selectedControlId"] = None
        event["selectedControlExecutionFailure"] = None
        event["matchedControlId"] = None
        if event.get("triggered") is not True:
            continue
        selected = choose_control(event, controls_by_parent[event["eventId"]], used_control_ids)
        if selected:
            control = selected["control"]
            used_control_ids.add(control["controlId"])
            selected_by_control[control["controlId"]].append(event["eventId"])
            event.update(
                {
                    "selectedControlId": control["controlId"],
                    "selectedControlExecutionFailure": control.get("executionFailure"),
                    "matchedControlGapRatioExact": str(selected["ratio"]),
                    "matchedControlGapRatio": bp(selected["ratio"]),
                    "matchedControlGapDistanceBpExact": str(selected["distance"]),
                    "matchedControlGapDistanceBp": bp(selected["distance"]),
                }
            )
            if event.get("executionFailure") is None and control.get("executionFailure") is None:
                event["matchedControlId"] = control["controlId"]
                matched_by_control[control["controlId"]].append(event["eventId"])
    for control in control_ledger:
        control["selectedByEventIds"] = sorted(selected_by_control.get(control["controlId"], []))
        control["matchedByEventIds"] = sorted(matched_by_control.get(control["controlId"], []))
    return event_ledger, control_ledger, parsing


def ledger_command(directory: Path, download_manifest_path: Path, output: Path) -> None:
    download_manifest, download_manifest_sha, availability, code_freeze_sha, code_freeze = (
        load_and_validate_download_manifest(download_manifest_path, directory)
    )
    event_ledger, control_ledger, parsing = construct_ledgers(download_manifest, availability)

    ledger = {
        "schemaVersion": 1,
        "completedAtUtc": iso_utc(datetime.now(UTC)),
        "codeFreeze": relative_or_absolute(CODE_FREEZE),
        "codeFreezeSha256": code_freeze_sha,
        "analysisScriptSha256": code_freeze["analysisScriptSha256"],
        "declaration": relative_or_absolute(PREREGISTRATION),
        "preregistrationSha256": FROZEN_PREREGISTRATION_SHA256,
        "inventory": relative_or_absolute(INVENTORY),
        "inventorySha256": FROZEN_INVENTORY_SHA256,
        "availability": relative_or_absolute(AVAILABILITY),
        "availabilitySha256": FROZEN_AVAILABILITY_SHA256,
        "downloadManifest": relative_or_absolute(download_manifest_path),
        "downloadManifestSha256": download_manifest_sha,
        "constants": ledger_constants(),
        "parsing": parsing,
        "counts": {"events": len(event_ledger), "controls": len(control_ledger)},
        "eventLedger": event_ledger,
        "controlLedger": control_ledger,
    }
    write_json_new(output, ledger)
    print(f"complete ledger {output} SHA256 {file_sha256(output)}")


def numeric_summary(values: Iterable[float]) -> dict[str, Any]:
    xs = list(values)
    if not xs:
        return {
            "n": 0,
            "mean": None,
            "median": None,
            "sd": None,
            "t": None,
            "zeroVariance": None,
            "positiveFraction": None,
            "worst": None,
        }
    mean = st.mean(xs)
    sd = st.stdev(xs) if len(xs) >= 2 else None
    t_stat = mean / (sd / math.sqrt(len(xs))) if sd is not None and sd > 0 else None
    return {
        "n": len(xs),
        "mean": mean,
        "median": st.median(xs),
        "sd": sd,
        "t": t_stat,
        "zeroVariance": sd == 0 if sd is not None else None,
        "positiveFraction": sum(value > 0 for value in xs) / len(xs),
        "worst": min(xs),
    }


def date_values(rows: list[dict[str, Any]], value_key: str, date_key: str = "processDateNy") -> dict[str, float]:
    grouped: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        if row.get(value_key) is not None:
            grouped[str(row[date_key])].append(float(row[value_key]))
    return {key: st.mean(values) for key, values in sorted(grouped.items())}


def date_summary(rows: list[dict[str, Any]], value_key: str, date_key: str = "processDateNy") -> dict[str, Any]:
    values = date_values(rows, value_key, date_key)
    return {**numeric_summary(values.values()), "events": len(rows), "byDate": values}


def group_summary(rows: list[dict[str, Any]], group_key: str, value_key: str) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[str(row[group_key])].append(row)
    return {key: {"dateWeighted": date_summary(group, value_key), "eventWeighted": numeric_summary(float(row[value_key]) for row in group)} for key, group in sorted(grouped.items())}


def decimal_mean(values: Iterable[Decimal]) -> Decimal | None:
    xs = list(values)
    return sum(xs, Decimal(0)) / Decimal(len(xs)) if xs else None


def exact_date_values(
    rows: list[dict[str, Any]], value_key: str, date_key: str = "processDateNy"
) -> dict[str, Decimal]:
    grouped: dict[str, list[Decimal]] = defaultdict(list)
    for row in rows:
        if row.get(value_key) is not None:
            grouped[str(row[date_key])].append(Decimal(str(row[value_key])))
    return {key: decimal_mean(values) for key, values in sorted(grouped.items())}  # type: ignore[return-value]


def condition_at_least(value: float | None, threshold: float) -> bool | None:
    return None if value is None else value >= threshold


def condition_positive(value: float | None) -> bool | None:
    return None if value is None else value > 0


def condition_nonnegative(value: float | None) -> bool | None:
    return None if value is None else value >= 0


def summarize_command(ledger_path: Path, output: Path) -> None:
    code_freeze_sha, code_freeze = verify_code_freeze()
    verify_frozen_file(PREREGISTRATION, FROZEN_PREREGISTRATION_SHA256)
    raw_ledger = ledger_path.read_bytes()
    ledger_sha = hashlib.sha256(raw_ledger).hexdigest()
    ledger = json.loads(raw_ledger)
    if ledger["preregistrationSha256"] != FROZEN_PREREGISTRATION_SHA256:
        raise RuntimeError("ledger does not belong to the frozen preregistration")
    if ledger["inventorySha256"] != FROZEN_INVENTORY_SHA256 or ledger["availabilitySha256"] != FROZEN_AVAILABILITY_SHA256:
        raise RuntimeError("ledger frozen-input hash mismatch")
    if ledger.get("codeFreezeSha256") != code_freeze_sha or ledger.get("analysisScriptSha256") != code_freeze["analysisScriptSha256"]:
        raise RuntimeError("ledger code-freeze mismatch")
    if ledger["constants"] != ledger_constants():
        raise RuntimeError("ledger constants differ from the frozen implementation")
    if ledger["counts"] != {"events": 50, "controls": 310}:
        raise RuntimeError("ledger population mismatch")

    download_manifest_path = resolve_recorded_path(str(ledger["downloadManifest"]))
    raw_download_manifest = download_manifest_path.read_bytes()
    download_manifest_preview = json.loads(raw_download_manifest)
    directory = resolve_recorded_path(str(download_manifest_preview["directory"]))
    download_manifest, download_manifest_sha, availability, _, _ = load_and_validate_download_manifest(
        download_manifest_path, directory
    )
    if download_manifest_sha != ledger["downloadManifestSha256"]:
        raise RuntimeError("ledger download-manifest hash mismatch")
    expected_events, expected_controls, expected_parsing = construct_ledgers(
        download_manifest, availability
    )
    if ledger["eventLedger"] != expected_events:
        raise RuntimeError("event ledger differs from checksummed-source reconstruction")
    if ledger["controlLedger"] != expected_controls:
        raise RuntimeError("control ledger differs from checksummed-source reconstruction")
    if ledger["parsing"] != expected_parsing:
        raise RuntimeError("parser diagnostics differ from checksummed-source reconstruction")

    events = ledger["eventLedger"]
    controls = ledger["controlLedger"]
    if len(events) != 50 or len({row["eventId"] for row in events}) != 50:
        raise RuntimeError("event ledger does not contain 50 unique event IDs")
    if len(controls) != 310 or len({row["controlId"] for row in controls}) != 310:
        raise RuntimeError("control ledger does not contain 310 unique control IDs")

    signal_usable = [row for row in events if row.get("signalUsable") is True]
    triggered_signals = [row for row in signal_usable if row.get("triggered") is True]
    execution_failures = [row for row in triggered_signals if row.get("executionFailure") is not None]
    triggered = [row for row in triggered_signals if row.get("executionFailure") is None]
    primary = date_summary(triggered, "net25Bp")
    event_weighted = numeric_summary(float(row["net25Bp"]) for row in triggered)
    sensitivities = {
        "13bp": date_summary(triggered, "net13Bp"),
        "25bp": primary,
        "40bp": date_summary(triggered, "net40Bp"),
    }
    primary_nightly_exact = exact_date_values(triggered, "net25BpExact")
    primary_mean_exact = decimal_mean(primary_nightly_exact.values())
    event_mean_exact = decimal_mean(Decimal(str(row["net25BpExact"])) for row in triggered)
    net40_mean_exact = decimal_mean(exact_date_values(triggered, "net40BpExact").values())

    nightly = primary["byDate"]
    if nightly:
        best_value_exact = max(primary_nightly_exact.values())
        best_date = min(
            key for key, value in primary_nightly_exact.items() if value == best_value_exact
        )
        without_best_date = numeric_summary(value for key, value in nightly.items() if key != best_date)
    else:
        best_date = None
        without_best_date = numeric_summary([])

    symbol_total: dict[str, Decimal] = defaultdict(Decimal)
    for row in triggered:
        symbol_total[str(row["symbol"])] += Decimal(str(row["net25BpExact"]))
    best_symbol = None
    if symbol_total:
        best_total = max(symbol_total.values())
        best_symbol = min(symbol for symbol, total in symbol_total.items() if total == best_total)
    without_best_symbol_rows = [row for row in triggered if row["symbol"] != best_symbol]
    without_best_symbol = numeric_summary(float(row["net25Bp"]) for row in without_best_symbol_rows)
    without_best_symbol_exact = decimal_mean(
        Decimal(str(row["net25BpExact"])) for row in without_best_symbol_rows
    )
    without_best_date_exact = decimal_mean(
        value for key, value in primary_nightly_exact.items() if key != best_date
    )

    positive_by_symbol: dict[str, Decimal] = defaultdict(Decimal)
    for row in triggered:
        value = Decimal(str(row["net25BpExact"]))
        positive_by_symbol[str(row["symbol"])] += max(value, Decimal(0))
    total_positive = sum(positive_by_symbol.values(), Decimal(0))
    concentration_symbol = None
    if total_positive > 0:
        largest_positive = max(positive_by_symbol.values())
        concentration_symbol = min(
            symbol for symbol, value in positive_by_symbol.items() if value == largest_positive
        )
    concentration_share_exact = (
        positive_by_symbol[concentration_symbol] / total_positive if concentration_symbol else None
    )
    concentration_share = float(concentration_share_exact) if concentration_share_exact is not None else None

    controls_by_id = {row["controlId"]: row for row in controls}
    selected_control_execution_failures = [
        row for row in triggered_signals
        if row.get("selectedControlId") and row.get("selectedControlExecutionFailure") is not None
    ]
    matched_pairs: list[dict[str, Any]] = []
    for event in triggered:
        control_id = event.get("matchedControlId")
        if not control_id:
            continue
        control = controls_by_id[control_id]
        matched_pairs.append(
            {
                "eventId": event["eventId"],
                "eventDateNy": event["processDateNy"],
                "symbol": event["symbol"],
                "controlId": control_id,
                "controlDateNy": control["processDateNy"],
                "eventGapBp": event["gapBp"],
                "controlGapBp": control["gapBp"],
                "gapRatio": event["matchedControlGapRatio"],
                "gapRatioExact": event["matchedControlGapRatioExact"],
                "gapDistanceBp": event["matchedControlGapDistanceBp"],
                "gapDistanceBpExact": event["matchedControlGapDistanceBpExact"],
                "eventNet25Bp": event["net25Bp"],
                "controlNet25Bp": control["net25Bp"],
                "eventMinusControlNet25BpExact": str(
                    Decimal(str(event["net25BpExact"]))
                    - Decimal(str(control["net25BpExact"]))
                ),
                "eventMinusControlNet25Bp": float(event["net25Bp"]) - float(control["net25Bp"]),
            }
        )
    matched_control_summary = date_summary(matched_pairs, "eventMinusControlNet25Bp", "eventDateNy")
    matched_event_dates = len({row["eventDateNy"] for row in matched_pairs})
    matched_control_dates = len({row["controlDateNy"] for row in matched_pairs})
    matched_ratio_mean_exact = decimal_mean(
        Decimal(str(row["gapRatioExact"])) for row in matched_pairs
    )
    matched_ratio_mean = float(matched_ratio_mean_exact) if matched_ratio_mean_exact is not None else None
    matched_difference_mean_exact = decimal_mean(
        exact_date_values(
            matched_pairs, "eventMinusControlNet25BpExact", "eventDateNy"
        ).values()
    )

    standalone_control_signals = [
        row for row in controls
        if row.get("signalUsable") is True
        and row.get("standaloneTriggered") is True
    ]
    standalone_control_execution_failures = [
        row for row in standalone_control_signals if row.get("executionFailure") is not None
    ]
    standalone_controls = [
        row for row in standalone_control_signals if row.get("executionFailure") is None
    ]
    before = [row for row in triggered if date.fromisoformat(row["processDateNy"]) < USDT_CONVERSION_DATE]
    after = [row for row in triggered if date.fromisoformat(row["processDateNy"]) >= USDT_CONVERSION_DATE]
    post_mark = [row for row in triggered if date.fromisoformat(row["processDateNy"]) >= MARK_METHOD_DATE]

    attrition = (len(events) - len(signal_usable)) / len(events)
    pre_summary = date_summary(before, "net25Bp")
    post_summary = date_summary(after, "net25Bp")
    pre_mean_exact = decimal_mean(exact_date_values(before, "net25BpExact").values())
    post_mean_exact = decimal_mean(exact_date_values(after, "net25BpExact").values())
    strata_condition = None
    if pre_mean_exact is not None and post_mean_exact is not None:
        strata_condition = pre_mean_exact >= 0 and post_mean_exact >= 0
    matched_sample_enough = matched_event_dates >= 8 and matched_control_dates >= 8
    conditions: dict[str, bool | None] = {
        "signalBarAttritionAtMost20Pct": attrition <= 0.20,
        "atLeast12TriggerDates": len({row["processDateNy"] for row in triggered_signals}) >= 12,
        "zeroTriggeredExecutionFailures": len(execution_failures) == 0,
        "dateWeightedMeanNet25AtLeast25Bp": (
            None if primary_mean_exact is None else primary_mean_exact >= Decimal("25")
        ),
        "eventWeightedMeanNet25AtLeast25Bp": (
            None if event_mean_exact is None else event_mean_exact >= Decimal("25")
        ),
        "dateDeclusteredTAtLeast2": condition_at_least(primary["t"], 2.0),
        "atLeast8DistinctMatchedEventAndControlDates": matched_sample_enough,
        "matchedMeanGapRatioBetween0_9And1_1": (
            MATCH_BALANCE_MIN <= matched_ratio_mean_exact <= MATCH_BALANCE_MAX
            if matched_sample_enough and matched_ratio_mean_exact is not None else None
        ),
        "matchedEventMinusControlMeanPositive": (
            matched_difference_mean_exact > 0
            if matched_sample_enough and matched_difference_mean_exact is not None else None
        ),
        "meanWithoutBestDatePositive": (
            None if without_best_date_exact is None else without_best_date_exact > 0
        ),
        "eventMeanWithoutBestSymbolPositive": (
            None if without_best_symbol_exact is None else without_best_symbol_exact > 0
        ),
        "dateWeightedMeanNet40Nonnegative": (
            None if net40_mean_exact is None else net40_mean_exact >= 0
        ),
        "preAndPostUsdtConversionMeansNonnegative": strata_condition,
        "bestSymbolPositivePnlShareAtMost25Pct": (
            None if concentration_share_exact is None
            else concentration_share_exact <= Decimal("0.25")
        ),
    }
    sample_conditions = {
        "atLeast12TriggerDates",
        "atLeast8DistinctMatchedEventAndControlDates",
    }
    matched_dependents = {
        "matchedMeanGapRatioBetween0_9And1_1",
        "matchedEventMinusControlMeanPositive",
    }
    failed_non_sample = [
        key for key, value in conditions.items()
        if key not in sample_conditions
        and not (key in matched_dependents and not matched_sample_enough)
        and value is not True
    ]
    if failed_non_sample:
        verdict = "FAIL"
    elif all(value is True for value in conditions.values()):
        verdict = "PASS"
    else:
        verdict = "INSUFFICIENT"

    proxy_triggered_signals = [row for row in signal_usable if row.get("pMinusProxyTriggered") is True]
    proxy_triggered = [
        row for row in proxy_triggered_signals
        if row.get("pMinusProxyExecutionFailure") is None
    ]
    proxy_trigger_disagreement = sum(
        row.get("triggered") != row.get("pMinusProxyTriggered") for row in signal_usable
    )
    proxy_direction_disagreement = sum(
        row.get("directionSign") != row.get("pMinusProxyDirectionSign")
        for row in signal_usable
        if row.get("triggered") is True and row.get("pMinusProxyTriggered") is True
    )
    symbol_contributions = {
        symbol: {
            "events": len([row for row in triggered if row["symbol"] == symbol]),
            "net25BpSum": float(total),
            "net25BpMean": st.mean(float(row["net25Bp"]) for row in triggered if row["symbol"] == symbol),
        }
        for symbol, total in sorted(symbol_total.items())
    }
    date_contributions = {
        process_date: {
            "events": sum(row["processDateNy"] == process_date for row in triggered),
            "net25BpMean": value,
        }
        for process_date, value in nightly.items()
    }

    report = {
        "schemaVersion": 1,
        "revealedAtUtc": iso_utc(datetime.now(UTC)),
        "verdict": verdict,
        "codeFreeze": relative_or_absolute(CODE_FREEZE),
        "codeFreezeSha256": code_freeze_sha,
        "analysisScriptSha256": code_freeze["analysisScriptSha256"],
        "ledger": relative_or_absolute(ledger_path),
        "ledgerSha256": ledger_sha,
        "preregistrationSha256": FROZEN_PREREGISTRATION_SHA256,
        "decisionConditions": conditions,
        "failedNonSampleConditions": failed_non_sample,
        "counts": {
            "sourceEvents": len(events),
            "signalUsableEvents": len(signal_usable),
            "signalUnusableEvents": len(events) - len(signal_usable),
            "signalAttritionFraction": attrition,
            "triggeredSignals": len(triggered_signals),
            "triggeredSignalDates": len({row["processDateNy"] for row in triggered_signals}),
            "triggeredExecutionFailures": len(execution_failures),
            "triggeredEvents": len(triggered),
            "triggeredDates": primary["n"],
            "triggerRateOfSourceEvents": len(triggered_signals) / len(events),
            "triggerRateOfSignalUsableEvents": (
                len(triggered_signals) / len(signal_usable) if signal_usable else None
            ),
            "controlCandidates": len(controls),
            "signalUsableControlCandidates": sum(row.get("signalUsable") is True for row in controls),
            "standaloneControlTriggerSignals": len(standalone_control_signals),
            "standaloneControlExecutionFailures": len(standalone_control_execution_failures),
            "standaloneControlExecutedTriggers": len(standalone_controls),
            "selectedControls": sum(bool(row.get("selectedControlId")) for row in triggered_signals),
            "selectedControlExecutionFailures": len(selected_control_execution_failures),
            "matchedEvents": len(matched_pairs),
            "matchedEventDates": matched_event_dates,
            "matchedControlDates": matched_control_dates,
        },
        "primary": {
            "dateWeightedNet25Bp": primary,
            "eventWeightedNet25Bp": event_weighted,
            "sensitivitiesDateWeighted": sensitivities,
            "byDirection": group_summary(triggered, "direction", "net25Bp"),
            "byUsdtConversionRegime": {
                "before2026-07-16": pre_summary,
                "from2026-07-16": post_summary,
            },
            "post2026-08-31MarkMethodUnderpowered": date_summary(post_mark, "net25Bp"),
            "withoutBestDate": {"removedDate": best_date, **without_best_date},
            "withoutBestSymbolEventWeighted": {"removedSymbol": best_symbol, **without_best_symbol},
            "positivePnlConcentration": {
                "symbol": concentration_symbol,
                "share": concentration_share,
                "totalPositiveEventPnlBp": float(total_positive),
            },
            "symbolContributions": symbol_contributions,
            "dateContributions": date_contributions,
            "capacity": {
                "entryQuoteVolume": numeric_summary(float(row["entryQuoteVolume"]) for row in triggered),
                "entryTradeCount": numeric_summary(float(row["entryTradeCount"]) for row in triggered),
                "exitDelayMinutes": numeric_summary(float(row["exitDelayMinutes"]) for row in triggered),
            },
        },
        "matchedControl": {
            "dateWeightedEventMinusControlNet25Bp": matched_control_summary,
            "meanAbsoluteGapRatio": matched_ratio_mean,
            "gapDistanceBp": numeric_summary(float(row["gapDistanceBp"]) for row in matched_pairs),
            "pairs": matched_pairs,
            "selectedControlExecutionFailureDetails": [
                {
                    "eventId": row["eventId"],
                    "selectedControlId": row["selectedControlId"],
                    "reason": row["selectedControlExecutionFailure"],
                }
                for row in selected_control_execution_failures
            ],
        },
        "standaloneControl": {
            "dateWeightedNet25Bp": date_summary(standalone_controls, "net25Bp"),
            "eventWeightedNet25Bp": numeric_summary(float(row["net25Bp"]) for row in standalone_controls),
        },
        "diagnosticsNotDecisionRules": {
            "gapBp": numeric_summary(float(row["gapBp"]) for row in signal_usable),
            "triggeredEntryAdjustedDisplacementBp": numeric_summary(float(row["entryAdjustedDisplacementBp"]) for row in triggered),
            "triggeredExitAdjustedDisplacementBp": numeric_summary(float(row["exitAdjustedDisplacementBp"]) for row in triggered),
            "markVsPMinusBp": numeric_summary(float(row["markVsPMinusBp"]) for row in signal_usable),
            "pMinusDividendProxy": {
                "triggeredSignals": len(proxy_triggered_signals),
                "triggeredSignalDates": len({row["processDateNy"] for row in proxy_triggered_signals}),
                "executionFailures": sum(row.get("pMinusProxyExecutionFailure") is not None for row in proxy_triggered_signals),
                "triggerStatusDisagreements": proxy_trigger_disagreement,
                "directionDisagreementsWhenBothTrigger": proxy_direction_disagreement,
                "dateWeightedNet25Bp": date_summary(proxy_triggered, "pMinusProxyNet25Bp"),
                "eventWeightedNet25Bp": numeric_summary(float(row["pMinusProxyNet25Bp"]) for row in proxy_triggered),
            },
            "perpIndexMinusBp": numeric_summary(float(row["perpIndexMinusBp"]) for row in signal_usable if row.get("perpIndexMinusBp") is not None),
            "perpIndexPlusBp": numeric_summary(float(row["perpIndexPlusBp"]) for row in signal_usable if row.get("perpIndexPlusBp") is not None),
            "perpIndexExitBp": numeric_summary(float(row["perpIndexExitBp"]) for row in signal_usable if row.get("perpIndexExitBp") is not None),
            "signalFailureReasons": {
                reason: count
                for reason, count in sorted(
                    {
                        reason: sum(reason in row.get("signalUnusableReasons", {}).values() for row in events)
                        for reason in {reason for row in events for reason in row.get("signalUnusableReasons", {}).values()}
                    }.items()
                )
            },
            "triggeredExecutionFailureDetails": [
                {"eventId": row["eventId"], "reason": row["executionFailure"]}
                for row in execution_failures
            ],
        },
        "appendixNonDecisionHorizons": {
            "fiveMinuteDateWeightedNet25Bp": date_summary([row for row in triggered if row.get("exit5Net25Bp") is not None], "exit5Net25Bp"),
            "thirtyMinuteDateWeightedNet25Bp": date_summary([row for row in triggered if row.get("exit30Net25Bp") is not None], "exit30Net25Bp"),
        },
    }
    write_json_new(output, report)

    def fmt(value: Any, digits: int = 1) -> str:
        return "NA" if value is None else f"{value:.{digits}f}"

    print(f"VERDICT {verdict}")
    print(
        f"primary: {len(triggered_signals)} signals / {primary['n']} executed dates; "
        f"net25 mean {fmt(primary['mean'])} bp, median {fmt(primary['median'])} bp, t {fmt(primary['t'], 2)}"
    )
    print(
        f"event-weighted {fmt(event_weighted['mean'])} bp; matched-control difference "
        f"{fmt(matched_control_summary['mean'])} bp over {matched_event_dates} event dates"
    )
    print(f"result {output} SHA256 {file_sha256(output)}")


def selfcheck() -> None:
    summer = date(2026, 7, 15)
    winter = date(2026, 1, 14)
    fmt = lambda epoch: datetime.fromtimestamp(epoch / 1_000, UTC).strftime("%Y-%m-%d %H:%M")
    assert [
        fmt(ny_epoch_millis(summer, *clock))
        for clock in (TRADE_CLOCKS["pMinus"], TRADE_CLOCKS["pPlus"], TRADE_CLOCKS["entry"], PRIMARY_EXIT_CLOCKS[0])
    ] == [
        "2026-07-15 23:58", "2026-07-16 00:01", "2026-07-16 00:03", "2026-07-16 00:18"
    ]
    assert [
        fmt(ny_epoch_millis(winter, *clock))
        for clock in (TRADE_CLOCKS["pMinus"], TRADE_CLOCKS["pPlus"], TRADE_CLOCKS["entry"], PRIMARY_EXIT_CLOCKS[0])
    ] == [
        "2026-01-15 00:58", "2026-01-15 01:01", "2026-01-15 01:03", "2026-01-15 01:18"
    ]
    assert normalize_archive_timestamp("1784160000000") == (1784160000000, "milliseconds")
    assert normalize_archive_timestamp("1784160000000000") == (1784160000000, "microseconds")

    process_date = date(2026, 7, 15)
    symbol = "TESTUSDT"
    bars: dict[tuple[str, str, int], dict[str, Any]] = {}
    prices = {"pMinus": ("100", "100"), "pPlus": ("98.4", "98.4"), "entry": ("98.5", "98.5"), "exit5": ("98.7", "98.7"), "exit30": ("99.2", "99.2")}
    for name, clock in TRADE_CLOCKS.items():
        open_price, close_price = prices[name]
        bars[(symbol, "trade", ny_epoch_millis(process_date, *clock))] = {
            "open": open_price, "high": "101", "low": "98", "close": close_price,
            "volume": "10", "quoteVolume": "1000", "tradeCount": 5,
        }
    bars[(symbol, "mark", ny_epoch_millis(process_date, 20, 0))] = {
        "open": "100", "high": "100", "low": "100", "close": "100"
    }
    bars[(symbol, "trade", ny_epoch_millis(process_date, *PRIMARY_EXIT_CLOCKS[0]))] = {
        "open": "99", "high": "99", "low": "99", "close": "99",
        "volume": "10", "quoteVolume": "990", "tradeCount": 5,
    }
    event = {
        "symbol": symbol,
        "processDateNy": process_date.isoformat(),
        "specialRate": "-0.01",
        "specialRateBp": -100.0,
    }
    outcome = build_event_row(event, bars, set())
    assert outcome["triggered"] and outcome["direction"] == "long"
    assert abs(outcome["gapBp"] + 60.0) < 1e-9
    assert abs(outcome["grossBp"] - 50.76142132) < 1e-6
    assert abs(outcome["net25Bp"] - 25.76142132) < 1e-6

    delayed_bars = dict(bars)
    delayed_bars[(symbol, "trade", ny_epoch_millis(process_date, 20, 18))] = {
        "open": "99", "high": "99", "low": "99", "close": "99",
        "volume": "0", "quoteVolume": "0", "tradeCount": 0,
    }
    delayed_bars[(symbol, "trade", ny_epoch_millis(process_date, 20, 19))] = {
        "open": "99.1", "high": "99.1", "low": "99.1", "close": "99.1",
        "volume": "10", "quoteVolume": "991", "tradeCount": 2,
    }
    delayed = build_event_row(event, delayed_bars, set())
    assert delayed["triggered"] and delayed["executionFailure"] is None
    assert delayed["exitClockNy"] == "20:19" and delayed["exitDelayMinutes"] == 1

    no_exit_bars = {
        key: value for key, value in bars.items()
        if not (key[0] == symbol and key[1] == "trade" and key[2] >= ny_epoch_millis(process_date, 20, 18))
    }
    no_exit = build_event_row(event, no_exit_bars, set())
    assert no_exit["triggered"] and no_exit["executionFailure"].startswith("noExecutableExit")

    synthetic_event = {
        "gapBpExact": "-100", "directionSign": 1, "processDateNy": "2026-07-15"
    }
    synthetic_controls = [
        {"controlId": "2026-07-01:TESTUSDT", "processDateNy": "2026-07-01", "gapBpExact": "-60", "directionSign": 1, "signalUsable": True, "executionFailure": None, "regime": list(regime_key(date(2026, 7, 1)))},
        {"controlId": "2026-07-08:TESTUSDT", "processDateNy": "2026-07-08", "gapBpExact": "-85", "directionSign": 1, "signalUsable": True, "executionFailure": "entry:minuteMissing", "regime": list(regime_key(date(2026, 7, 8)))},
        {"controlId": "2026-07-16:TESTUSDT", "processDateNy": "2026-07-16", "gapBpExact": "-99", "directionSign": 1, "signalUsable": True, "executionFailure": None, "regime": list(regime_key(date(2026, 7, 16)))},
    ]
    chosen = choose_control(synthetic_event, synthetic_controls, set())
    assert chosen and chosen["control"]["controlId"].startswith("2026-07-08")
    assert chosen["control"]["executionFailure"] is not None
    assert choose_control(synthetic_event, synthetic_controls, {chosen["control"]["controlId"]}) is None
    print("SELFCHECK OK")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("selfcheck")
    download = sub.add_parser("download")
    download.add_argument("--directory", type=Path, default=DEFAULT_DIRECTORY)
    download.add_argument("--manifest", type=Path, default=DEFAULT_DOWNLOAD_MANIFEST)
    ledger = sub.add_parser("ledger")
    ledger.add_argument("--directory", type=Path, default=DEFAULT_DIRECTORY)
    ledger.add_argument("--download-manifest", type=Path, default=DEFAULT_DOWNLOAD_MANIFEST)
    ledger.add_argument("--output", type=Path, default=DEFAULT_LEDGER)
    summarize = sub.add_parser("summarize")
    summarize.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    summarize.add_argument("--output", type=Path, default=DEFAULT_RESULT)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.mode == "selfcheck":
        selfcheck()
    elif args.mode == "download":
        download_command(args.directory, args.manifest)
    elif args.mode == "ledger":
        ledger_command(args.directory, args.download_manifest, args.output)
    elif args.mode == "summarize":
        summarize_command(args.ledger, args.output)
    else:
        raise AssertionError(args.mode)


if __name__ == "__main__":
    main()
