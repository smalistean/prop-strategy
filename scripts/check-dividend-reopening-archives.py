#!/usr/bin/env python3
"""Verify public Binance archive objects needed by the outcome-blind #17 inventory.

Archive objects are checked with HTTP HEAD and the tiny companion CHECKSUM text objects are
downloaded to freeze their official SHA-256 values. Archive contents and event-window outcomes are
never read. Completed months use monthly objects; September dates use completed UTC daily objects.
The object plan includes every primary event timestamp and every possible frozen 56-day control.
"""

from __future__ import annotations

import concurrent.futures
import json
import urllib.error
import urllib.request
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo


REPO = Path(__file__).resolve().parent.parent
INVENTORY = REPO / "research" / "dividend-reopening-feasibility-2026-09-12.json"
OUTPUT = REPO / "research" / "dividend-reopening-archive-availability-2026-09-12.json"
ROOT = "https://data.binance.vision/data/futures/um"
STREAMS = {
    "trade": "klines",
    "index": "indexPriceKlines",
    "mark": "markPriceKlines",
}
NY = ZoneInfo("America/New_York")
REGIME_START = date(2026, 5, 16)


def utc_dates_for_boundary(process_date: date) -> set[date]:
    """UTC archive dates containing the frozen 19:58 through 20:33 New York bars."""
    return {
        datetime.combine(process_date, time(19, 58), NY).astimezone(timezone.utc).date(),
        datetime.combine(process_date, time(20, 33), NY).astimezone(timezone.utc).date(),
    }


def head(url: str) -> dict[str, object]:
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "prop-strategy-audit/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return {
                "status": response.status,
                "contentLength": int(response.headers.get("Content-Length", "0")),
                "lastModified": response.headers.get("Last-Modified"),
            }
    except urllib.error.HTTPError as error:
        return {"status": error.code, "contentLength": 0, "lastModified": None}
    except (urllib.error.URLError, TimeoutError) as error:
        return {"status": None, "contentLength": 0, "lastModified": None, "error": str(error)}


def get_checksum(url: str, filename: str) -> dict[str, object]:
    request = urllib.request.Request(url, headers={"User-Agent": "prop-strategy-audit/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read(512).decode("ascii").strip()
            fields = body.split()
            if len(fields) < 2 or fields[1].lstrip("*") != filename or len(fields[0]) != 64:
                raise RuntimeError(f"invalid checksum body for {filename}")
            digest = fields[0].lower()
            if any(character not in "0123456789abcdef" for character in digest):
                raise RuntimeError(f"invalid checksum digest for {filename}")
            return {
                "status": response.status,
                "contentLength": int(response.headers.get("Content-Length", str(len(body)))),
                "lastModified": response.headers.get("Last-Modified"),
                "officialSha256": digest,
            }
    except urllib.error.HTTPError as error:
        return {
            "status": error.code,
            "contentLength": 0,
            "lastModified": None,
            "officialSha256": None,
        }
    except (urllib.error.URLError, TimeoutError) as error:
        return {
            "status": None,
            "contentLength": 0,
            "lastModified": None,
            "officialSha256": None,
            "error": str(error),
        }


def main() -> None:
    inventory = json.loads(INVENTORY.read_text())
    # Map each logical requirement to the smallest official archive object that contains it.
    # Monthly archives are complete through August. September remains in daily archives.
    requirements: dict[tuple[str, str, str, str], set[str]] = {}
    primary_events = [
        event for event in inventory["events"]
        if date.fromisoformat(str(event["processDateNy"])) >= REGIME_START
    ]
    event_dates_by_symbol: dict[str, set[date]] = {}
    control_candidates: set[tuple[str, date]] = set()
    for event in primary_events:
        event_dates_by_symbol.setdefault(str(event["symbol"]), set()).add(
            date.fromisoformat(str(event["processDateNy"]))
        )

    def require(symbol: str, utc_day: date, stream: str, scope: str) -> None:
        month = utc_day.strftime("%Y-%m")
        frequency, period = ("monthly", month) if month < "2026-09" else ("daily", utc_day.isoformat())
        requirements.setdefault((symbol, frequency, period, stream), set()).add(scope)

    for event in primary_events:
        symbol = str(event["symbol"])
        process_date = date.fromisoformat(str(event["processDateNy"]))
        for utc_day in utc_dates_for_boundary(process_date):
            for stream in STREAMS:
                require(symbol, utc_day, stream, "primary")
        for weeks in range(1, 9):
            control_date = process_date - timedelta(days=7 * weeks)
            if control_date < REGIME_START or control_date in event_dates_by_symbol[symbol]:
                continue
            control_candidates.add((symbol, control_date))
            for utc_day in utc_dates_for_boundary(control_date):
                require(symbol, utc_day, "trade", "control")

    objects: list[dict[str, object]] = []
    for (symbol, frequency, period, stream), scopes in sorted(requirements.items()):
        archive_name = STREAMS[stream]
        filename = f"{symbol}-1m-{period}.zip"
        url = f"{ROOT}/{frequency}/{archive_name}/{symbol}/1m/{filename}"
        objects.append(
            {
                "symbol": symbol,
                "frequency": frequency,
                "period": period,
                "stream": stream,
                "scopes": sorted(scopes),
                "url": url,
                "checksumUrl": url + ".CHECKSUM",
            }
        )

    def inspect(item: dict[str, object]) -> dict[str, object]:
        checked = dict(item)
        checked["archive"] = head(str(item["url"]))
        checked["checksum"] = get_checksum(str(item["checksumUrl"]), Path(str(item["url"])).name)
        return checked

    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
        checked = list(executor.map(inspect, objects))
    checked.sort(key=lambda row: (row["period"], row["symbol"], row["stream"]))

    archive_ok = sum(row["archive"]["status"] == 200 for row in checked)
    checksum_ok = sum(row["checksum"]["status"] == 200 for row in checked)

    def scope_summary(scope: str) -> dict[str, object]:
        rows = [row for row in checked if scope in row["scopes"]]
        scope_archive_ok = sum(row["archive"]["status"] == 200 for row in rows)
        scope_checksum_ok = sum(row["checksum"]["status"] == 200 for row in rows)
        scope_values = sum(bool(row["checksum"].get("officialSha256")) for row in rows)
        return {
            "objects": len(rows),
            "archiveHttp200": scope_archive_ok,
            "checksumHttp200": scope_checksum_ok,
            "officialChecksumValues": scope_values,
            "allAvailable": (
                scope_archive_ok == len(rows)
                and scope_checksum_ok == len(rows)
                and scope_values == len(rows)
            ),
            "totalCompressedBytes": sum(
                int(row["archive"]["contentLength"])
                for row in rows
                if row["archive"]["status"] == 200
            ),
        }

    output = {
        "schemaVersion": 3,
        "createdAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "outcomeBlind": True,
        "inventory": str(INVENTORY.relative_to(REPO)),
        "population": {
            "regimeStartNy": REGIME_START.isoformat(),
            "events": len(primary_events),
            "eventDatesNy": len({event["processDateNy"] for event in primary_events}),
            "symbols": len({event["symbol"] for event in primary_events}),
            "controlLookbackDays": 56,
            "uniqueControlCandidates": len(control_candidates),
        },
        "summary": {
            "objects": len(checked),
            "archiveHttp200": archive_ok,
            "checksumHttp200": checksum_ok,
            "officialChecksumValues": sum(bool(row["checksum"].get("officialSha256")) for row in checked),
            "allAvailable": (
                archive_ok == len(checked)
                and checksum_ok == len(checked)
                and all(row["checksum"].get("officialSha256") for row in checked)
            ),
            "totalCompressedBytes": sum(
                int(row["archive"]["contentLength"])
                for row in checked
                if row["archive"]["status"] == 200
            ),
            "primary": scope_summary("primary"),
            "control": scope_summary("control"),
        },
        "objects": checked,
    }
    OUTPUT.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")
    print(f"wrote {OUTPUT}")
    print(json.dumps(output["summary"], sort_keys=True))
    for row in checked:
        if row["archive"]["status"] != 200 or row["checksum"]["status"] != 200:
            print(
                f"MISSING {row['stream']} {row['symbol']} {row['frequency']} {row['period']} "
                f"archive={row['archive']['status']} checksum={row['checksum']['status']}"
            )


if __name__ == "__main__":
    main()
