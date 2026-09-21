#!/usr/bin/env python3
"""Extract an outcome-blind contract/deadline ledger from the frozen Binance CMS source audit.

No market-data source is opened by this script.  It parses only announcement titles, publication
times, and bodies already captured by ``audit-binance-futures-delisting-events.py``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "research" / "binance-futures-delisting-source-audit-2026-09-13.json"
OUTPUT = REPO / "research" / "binance-futures-delisting-event-ledger-2026-09-13.json"
SETTLEMENT_METHOD_CHANGE_UTC = datetime(2024, 11, 11, 8, 0, tzinfo=timezone.utc)
MIN_ACTIONABLE_NOTICE_MINUTES = 360
SETTLEMENT_METHOD_SOURCE = (
    "https://www.binance.com/en/support/announcement/detail/4bcabddf0e81423ebca242e185bf157d"
)
TIMESTAMP_RE = re.compile(
    r"(?P<date>20\d{2}-\d{2}-\d{2})(?:\s+at)?\s+"
    r"(?P<hour>\d{1,2}):(?P<minute>\d{2})(?:\s*(?P<ampm>AM|PM))?(?:\s*\(UTC\))?",
    re.IGNORECASE,
)
SYMBOL_RE = re.compile(
    r"(?<![A-Z0-9])(?P<base>[A-Z0-9]+?)/?(?P<quote>USDT|USDC|BUSD|USD)(?![A-Z0-9])"
)
MANUAL_ARTICLE_SYMBOLS = {
    # Binance's title/body omit the quote suffix even though the notice labels the pair USDⓈ-M.
    "21e399dcea734230a3c181daf5407b64": ["MEMEFIUSDT"],
}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_timestamp(match: re.Match[str]) -> datetime:
    hour = int(match.group("hour"))
    ampm = match.group("ampm")
    if ampm:
        if hour < 1 or hour > 12:
            raise ValueError(f"invalid 12-hour clock: {match.group(0)}")
        hour = hour % 12 + (12 if ampm.upper() == "PM" else 0)
    return datetime.strptime(
        f"{match.group('date')} {hour:02d}:{match.group('minute')}", "%Y-%m-%d %H:%M"
    ).replace(tzinfo=timezone.utc)


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def symbols_in(text: str) -> list[tuple[str, int]]:
    return [
        (match.group("base") + match.group("quote"), match.start())
        for match in SYMBOL_RE.finditer(text)
    ]


def extract_term_lines(body: str) -> tuple[list[str], list[str]]:
    settlement: list[str] = []
    cutoff: list[str] = []
    mode: str | None = None
    for line in body.splitlines():
        lower = line.casefold()
        settlement_header = (
            "binance futures" in lower
            and "automatic settlement" in lower
            and ("contract" in lower or "as below" in lower or "following" in lower)
        )
        cutoff_header = (
            ("users are not allowed" in lower or "users will not be able" in lower)
            and ("new" in lower or "non-reduce" in lower)
            and ("open" in lower or "place" in lower)
        )
        has_timestamp = bool(TIMESTAMP_RE.search(line))
        if settlement_header or cutoff_header:
            cutoff_starts = [
                position for phrase in ("users are not allowed", "users will not be able")
                if (position := lower.find(phrase)) >= 0
            ]
            cutoff_start = min(cutoff_starts) if cutoff_starts else None
            settlement_text = line[:cutoff_start] if cutoff_start is not None else line
            cutoff_text = line[cutoff_start:] if cutoff_start is not None else line
            if settlement_header and TIMESTAMP_RE.search(settlement_text):
                settlement.append(settlement_text)
            if cutoff_header and TIMESTAMP_RE.search(cutoff_text):
                cutoff.append(cutoff_text)
            if not has_timestamp:
                mode = "settlement" if settlement_header else "cutoff"
            else:
                mode = None
            continue
        if mode:
            if line and not line[0].isdigit():
                mode = None
            elif has_timestamp and "contract" in lower:
                (settlement if mode == "settlement" else cutoff).append(line)
                continue
    return list(dict.fromkeys(settlement)), list(dict.fromkeys(cutoff))


def line_groups(line: str) -> list[tuple[datetime, list[str]]]:
    timestamps = list(TIMESTAMP_RE.finditer(line))
    if not timestamps:
        return []
    symbol_matches = symbols_in(line)
    symbols = [symbol for symbol, _ in symbol_matches]
    if len(timestamps) == 1:
        return [(parse_timestamp(timestamps[0]), symbols)]

    lower = line.casefold()
    if "respectively" in lower and len(symbols) == len(timestamps):
        return [
            (parse_timestamp(timestamp), [symbol])
            for timestamp, symbol in zip(timestamps, symbols, strict=True)
        ]

    groups: list[tuple[datetime, list[str]]] = []
    previous_end = 0
    for timestamp in timestamps:
        segment_symbols = [
            symbol for symbol, position in symbol_matches
            if previous_end <= position < timestamp.start()
        ]
        groups.append((parse_timestamp(timestamp), segment_symbols))
        previous_end = timestamp.end()
    return groups


def collect_mapping(lines: list[str]) -> tuple[dict[str, set[datetime]], list[str]]:
    mapping: dict[str, set[datetime]] = defaultdict(set)
    unresolved: list[str] = []
    for line in lines:
        for timestamp, symbols in line_groups(line):
            if not symbols:
                unresolved.append(line)
                continue
            for symbol in symbols:
                mapping[symbol].add(timestamp)
    return mapping, unresolved


def quote_asset(symbol: str) -> str | None:
    for quote in ("USDT", "USDC", "BUSD", "USD"):
        if symbol.endswith(quote) and len(symbol) > len(quote):
            return quote
    return None


def unique_timestamp(values: set[datetime] | None) -> datetime | None:
    return next(iter(values)) if values and len(values) == 1 else None


def is_clock_revision(body: str) -> bool:
    return bool(
        re.search(
            r"announcement was (?:last )?updated[^\n]*(?:delisting|settlement) (?:date|time)",
            body,
            re.IGNORECASE,
        )
    )


def parse_article(article: dict[str, Any]) -> dict[str, Any]:
    body = str(article["bodyText"])
    title = str(article["title"])
    release = datetime.fromisoformat(str(article["releaseAtUtc"]).replace("Z", "+00:00"))
    settlement_lines, cutoff_lines = extract_term_lines(body)
    settlement_map, unresolved_settlement = collect_mapping(settlement_lines)
    cutoff_map, unresolved_cutoff = collect_mapping(cutoff_lines)

    for symbol in MANUAL_ARTICLE_SYMBOLS.get(str(article["code"]), []):
        resolved_article_settlements = {
            timestamp for values in settlement_map.values() for timestamp in values
        }
        if len(resolved_article_settlements) == 1:
            settlement_map[symbol].update(resolved_article_settlements)

    # Standard single-clock notices refer to "aforementioned contracts" in the restriction line.
    # Bind that clock to every settlement symbol only when the line has exactly one timestamp.
    if unresolved_cutoff and len(unresolved_cutoff) == 1:
        timestamps = list(TIMESTAMP_RE.finditer(unresolved_cutoff[0]))
        if len(timestamps) == 1:
            cutoff = parse_timestamp(timestamps[0])
            for symbol in settlement_map:
                cutoff_map[symbol].add(cutoff)
            unresolved_cutoff = []

    all_symbols = sorted(set(settlement_map) | set(cutoff_map))
    clock_revision_notice = bool(
        re.search(r"\b(?:postpon(?:e|ed)|reschedul(?:e|ed)|delay(?:ed)?|revis(?:e|ed))\b", title,
                  re.IGNORECASE)
    )
    rows: list[dict[str, Any]] = []
    for symbol in all_symbols:
        settlement = unique_timestamp(settlement_map.get(symbol))
        cutoff = unique_timestamp(cutoff_map.get(symbol))
        quote = quote_asset(symbol)
        reasons: list[str] = []
        if quote != "USDT":
            reasons.append("notUsdtSettledContract")
        if settlement is None:
            reasons.append("settlementClockMissingOrAmbiguous")
        if cutoff is None:
            reasons.append("noNewOrderClockMissingOrAmbiguous")
        if settlement and cutoff and cutoff >= settlement:
            reasons.append("noNewOrderClockNotBeforeSettlement")
        if cutoff and release >= cutoff:
            reasons.append("announcementNotPublishedBeforeRestriction")
        publication_to_restriction = (
            (cutoff - release).total_seconds() / 60 if cutoff else None
        )
        if (
            publication_to_restriction is not None
            and publication_to_restriction < MIN_ACTIONABLE_NOTICE_MINUTES
        ):
            reasons.append("lessThanSixHoursNoticeBeforeRestriction")
        if is_clock_revision(body):
            reasons.append("currentArticleDisclosesClockRevisionWithoutRevisionTimestamp")
        rows.append(
            {
                "eventId": f"{symbol}:{iso_utc(settlement)}" if settlement else f"{symbol}:UNRESOLVED:{article['code']}",
                "symbol": symbol,
                "quoteAsset": quote,
                "releaseAtUtc": iso_utc(release),
                "settlementAtUtc": iso_utc(settlement) if settlement else None,
                "noNewOrderAtUtc": iso_utc(cutoff) if cutoff else None,
                "announcementLeadHours": (
                    (settlement - release).total_seconds() / 3_600 if settlement else None
                ),
                "restrictionLeadMinutes": (
                    (settlement - cutoff).total_seconds() / 60 if settlement and cutoff else None
                ),
                "publicationToRestrictionMinutes": publication_to_restriction,
                "settlementIndexAverageMinutes": (
                    60 if settlement and settlement < SETTLEMENT_METHOD_CHANGE_UTC else 30
                ) if settlement else None,
                "termsEligibleBeforeHedgeAudit": not reasons,
                "exclusionReasons": reasons,
                "articleCode": str(article["code"]),
                "articleTitle": title,
                "articleUrl": str(article["supportUrl"]),
                "articleBodySha256": str(article["bodySha256"]),
                "clockRevisionNotice": clock_revision_notice,
            }
        )

    delisting_article = (
        "delist" in title.casefold()
        and "automatic settlement" in body.casefold()
        and "delist" in body.casefold()
    )
    return {
        "articleCode": str(article["code"]),
        "title": title,
        "releaseAtUtc": iso_utc(release),
        "delistingArticle": delisting_article,
        "mentionsCurrentBodyUpdate": "announcement was updated" in body.casefold()
        or "announcement was last updated" in body.casefold(),
        "clockRevisionNotice": clock_revision_notice,
        "settlementLines": settlement_lines,
        "cutoffLines": cutoff_lines,
        "unresolvedSettlementLines": unresolved_settlement,
        "unresolvedCutoffLines": unresolved_cutoff,
        "events": rows if delisting_article else [],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()

    source_sha = file_sha256(args.source)
    source = json.loads(args.source.read_text())
    if source.get("outcomeBlind") is not True:
        raise RuntimeError("source audit is not marked outcome-blind")
    candidate_key = (
        "futuresDelistingCandidateDetails"
        if "futuresDelistingCandidateDetails" in source
        else "futuresArticleDetails"
    )
    articles = [parse_article(article) for article in source[candidate_key]]
    events = [event for article in articles for event in article["events"]]

    # A later, separately published postponement supersedes every earlier clock for that symbol.
    # The replacement still needs its own explicit no-new-order clock; the standard 30-minute rule
    # is not silently imputed into an announcement that states only a new settlement time.
    latest_revision_by_symbol: dict[str, dict[str, Any]] = {}
    for event in events:
        if not event["clockRevisionNotice"] or not event["settlementAtUtc"]:
            continue
        current = latest_revision_by_symbol.get(event["symbol"])
        if current is None or event["releaseAtUtc"] > current["releaseAtUtc"]:
            latest_revision_by_symbol[event["symbol"]] = event
    for event in events:
        latest_revision = latest_revision_by_symbol.get(event["symbol"])
        if latest_revision and event["releaseAtUtc"] < latest_revision["releaseAtUtc"]:
            event["exclusionReasons"].append("supersededByLaterOfficialClockRevision")
        event["termsEligibleBeforeHedgeAudit"] = not event["exclusionReasons"]

    event_ids = [str(event["eventId"]) for event in events if event["settlementAtUtc"]]
    duplicate_event_ids = sorted(
        event_id for event_id in set(event_ids) if event_ids.count(event_id) > 1
    )
    eligible = [event for event in events if event["termsEligibleBeforeHedgeAudit"]]

    output = {
        "schemaVersion": 2,
        "generatedAtUtc": iso_utc(datetime.now(timezone.utc)),
        "outcomeBlind": True,
        "explicitlyExcludedData": source["explicitlyExcludedData"],
        "sourceAudit": str(args.source.relative_to(REPO)),
        "sourceAuditSha256": source_sha,
        "scope": {
            "catalogId": source["source"]["catalogId"],
            "catalogCoverageStartUtc": source["source"]["oldestIncludedReleaseAtUtc"],
            "cutoffExclusiveUtc": source["source"]["cutoffExclusiveUtc"],
            "sourceBodyFilter": (
                "every official Delisting catalog title inventoried; bodies fetched for the "
                "source audit's explicit futures-product title set, then candidate body/title "
                "must mention delisting, automatic settlement and a futures product marker"
            ),
            "contractScopeForTermsGate": "USDT-settled symbols with exact settlement and no-new-order clocks",
            "minimumActionableNoticeMinutesBeforeRestriction": MIN_ACTIONABLE_NOTICE_MINUTES,
            "pricesOrReturnsJoined": False,
        },
        "settlementMethodRegimes": [
            {
                "beforeUtc": iso_utc(SETTLEMENT_METHOD_CHANGE_UTC),
                "indexAverageMinutes": 60,
            },
            {
                "fromUtc": iso_utc(SETTLEMENT_METHOD_CHANGE_UTC),
                "indexAverageMinutes": 30,
            },
        ],
        "settlementMethodSource": SETTLEMENT_METHOD_SOURCE,
        "counts": {
            "futuresDelistingBodyCandidates": len(articles),
            "delistingArticles": sum(article["delistingArticle"] for article in articles),
            "parsedContractEvents": len(events),
            "usdtContractEvents": sum(event["quoteAsset"] == "USDT" for event in events),
            "termsEligibleBeforeHedgeAudit": len(eligible),
            "eligibleIndependentSettlementClocks": len(
                {event["settlementAtUtc"] for event in eligible}
            ),
            "articlesWithUnresolvedSettlementLines": sum(
                bool(article["unresolvedSettlementLines"]) for article in articles
            ),
            "articlesWithUnresolvedCutoffLines": sum(
                bool(article["unresolvedCutoffLines"]) for article in articles
            ),
            "duplicateResolvedEventIds": len(duplicate_event_ids),
        },
        "duplicateResolvedEventIds": duplicate_event_ids,
        "eligibleEventsBeforeHedgeAudit": eligible,
        "allParsedEvents": events,
        "articleAudit": articles,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".part")
    temporary.write_text(json.dumps(output, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    temporary.replace(args.output)
    print(json.dumps(output["counts"], indent=2, sort_keys=True))
    print(f"wrote {args.output} SHA256 {file_sha256(args.output)}")


if __name__ == "__main__":
    main()
