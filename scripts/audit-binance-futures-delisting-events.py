#!/usr/bin/env python3
"""Build an outcome-blind source inventory for Binance futures delisting notices.

This script reads only Binance's public Delisting announcement catalog and article bodies.  Every
catalog title is inventoried, and bodies are fetched for every title with an explicit futures
product marker; this deliberately includes titles such as "Delisting of USDⓈ-M ... Postponed"
that omit the word "Futures".  It does not request, join, or calculate any market price, basis,
funding, volume, open-interest, or return data.  Its output is the source layer for IDEA_BOARD
#19's event-ledger data gate.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent
OUTPUT = REPO / "research" / "binance-futures-delisting-source-audit-2026-09-13.json"
CACHE = REPO / "data" / "delisting-source-cache"
CATALOG_ID = 161
PAGE_SIZE = 50
CUTOFF_EXCLUSIVE_UTC = datetime(2026, 9, 13, tzinfo=timezone.utc)
LIST_ENDPOINT = "https://www.binance.com/bapi/composite/v1/public/cms/article/list/query"
DETAIL_ENDPOINT = "https://www.binance.com/bapi/composite/v1/public/cms/article/detail/query"
HEADERS = {
    "Accept": "application/json",
    "Accept-Encoding": "identity",
    "clienttype": "web",
    "User-Agent": "prop-strategy-delisting-source-audit/1",
}
BLOCK_TAGS = {"p", "li", "h1", "h2", "h3", "h4", "tr", "table", "blockquote", "br"}


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def get_json(url: str) -> tuple[dict[str, Any], str]:
    request = urllib.request.Request(url, headers=HEADERS)
    last_error: Exception | None = None
    for attempt in range(8):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != 200:
                    raise RuntimeError(f"GET {url} returned HTTP {response.status}")
                raw = response.read()
            parsed = json.loads(raw)
            if parsed.get("code") != "000000" or parsed.get("success") is not True:
                raise RuntimeError(f"Binance CMS error for {url}: {parsed.get('code')}")
            return parsed, sha256_bytes(raw)
        except urllib.error.HTTPError as error:
            last_error = error
            if attempt == 7:
                break
            if error.code == 429:
                retry_after = error.headers.get("Retry-After")
                delay = float(retry_after) if retry_after and retry_after.isdigit() else min(
                    10 * (2 ** attempt), 120
                )
                time.sleep(delay)
            else:
                time.sleep(min(2 ** attempt, 16))
        except (urllib.error.URLError, TimeoutError, OSError, RuntimeError, json.JSONDecodeError) as error:
            last_error = error
            if attempt == 7:
                break
            time.sleep(min(2 ** attempt, 16))
    raise RuntimeError(f"failed to fetch {url}: {last_error}")


def list_url(page: int) -> str:
    query = urllib.parse.urlencode(
        {
            "type": 1,
            "catalogId": CATALOG_ID,
            "pageNo": page,
            "pageSize": PAGE_SIZE,
        }
    )
    return f"{LIST_ENDPOINT}?{query}"


def detail_url(code: str) -> str:
    return f"{DETAIL_ENDPOINT}?{urllib.parse.urlencode({'articleCode': code})}"


def is_futures_product_title(title: str) -> bool:
    lower = title.casefold()
    product_marker = any(
        marker in lower
        for marker in (
            "futures",
            "perpetual contract",
            "usdⓢ-m",
            "usdt-margined",
            "coin-margined",
        )
    )
    event_marker = any(
        marker in lower
        for marker in ("delist", "settlement", "postpon", "suspend", "cease")
    )
    return product_marker and event_marker


def catalog_from_response(response: dict[str, Any]) -> dict[str, Any]:
    catalogs = response.get("data", {}).get("catalogs", [])
    if len(catalogs) != 1 or int(catalogs[0].get("catalogId", -1)) != CATALOG_ID:
        raise RuntimeError("unexpected Binance delisting-catalog response")
    return catalogs[0]


def flatten_body(raw_body: str) -> str:
    document = json.loads(raw_body)
    pieces: list[str] = []

    def visit(node: Any) -> None:
        if isinstance(node, list):
            for child in node:
                visit(child)
            return
        if not isinstance(node, dict):
            return
        if node.get("node") == "text":
            pieces.append(str(node.get("text", "")))
            return
        tag = str(node.get("tag", "")).lower()
        for child in node.get("child", []):
            visit(child)
        if tag in BLOCK_TAGS:
            pieces.append("\n")

    visit(document)
    lines = [" ".join(line.split()) for line in "".join(pieces).splitlines()]
    return "\n".join(line for line in lines if line)


def fetch_page(page: int) -> dict[str, Any]:
    url = list_url(page)
    response, raw_sha = get_json(url)
    catalog = catalog_from_response(response)
    return {
        "page": page,
        "url": url,
        "rawSha256": raw_sha,
        "catalogId": int(catalog["catalogId"]),
        "catalogName": str(catalog["catalogName"]),
        "total": int(catalog["total"]),
        "articles": catalog["articles"],
    }


def valid_cached_detail(detail: dict[str, Any], article: dict[str, Any]) -> bool:
    return (
        int(detail.get("id", -1)) == int(article["id"])
        and str(detail.get("code")) == str(article["code"])
        and str(detail.get("title")) == str(article["title"])
        and int(detail.get("releaseDateEpochMs", -1)) == int(article["releaseDate"])
        and bool(detail.get("bodyText"))
        and len(str(detail.get("bodySha256", ""))) == 64
    )


def fetch_detail(article: dict[str, Any], cache_dir: Path, delay_seconds: float) -> dict[str, Any]:
    code = str(article["code"])
    cache_path = cache_dir / f"{code}.json"
    if cache_path.exists():
        cached = json.loads(cache_path.read_text())
        if valid_cached_detail(cached, article):
            return cached
    if delay_seconds:
        time.sleep(delay_seconds)
    url = detail_url(code)
    response, raw_sha = get_json(url)
    data = response.get("data")
    if not isinstance(data, dict):
        raise RuntimeError(f"missing detail body for {code}")
    if int(data.get("id", -1)) != int(article["id"]):
        raise RuntimeError(f"article ID mismatch for {code}")
    if str(data.get("title")) != str(article["title"]):
        raise RuntimeError(f"article title mismatch for {code}")
    raw_body = str(data.get("body", ""))
    text = flatten_body(raw_body)
    if not text:
        raise RuntimeError(f"empty article body for {code}")
    release_at = datetime.fromtimestamp(int(article["releaseDate"]) / 1_000, timezone.utc)
    detail = {
        "id": int(article["id"]),
        "code": code,
        "title": str(article["title"]),
        "releaseDateEpochMs": int(article["releaseDate"]),
        "releaseAtUtc": iso_utc(release_at),
        "supportUrl": f"https://www.binance.com/en/support/announcement/detail/{code}",
        "apiUrl": url,
        "version": str(data.get("version", "")),
        "apiResponseSha256": raw_sha,
        "bodySha256": sha256_bytes(raw_body.encode("utf-8")),
        "bodyText": text,
    }
    write_json_atomic(cache_path, detail)
    return detail


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--request-delay-ms", type=int, default=500)
    args = parser.parse_args()
    if args.workers < 1 or args.workers > 8:
        raise ValueError("--workers must be from 1 through 8")
    if args.request_delay_ms < 0 or args.request_delay_ms > 10_000:
        raise ValueError("--request-delay-ms must be from 0 through 10000")

    first = fetch_page(1)
    pages_expected = math.ceil(first["total"] / PAGE_SIZE)
    pages = [first]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        futures = [executor.submit(fetch_page, page) for page in range(2, pages_expected + 1)]
        for future in concurrent.futures.as_completed(futures):
            pages.append(future.result())
    pages.sort(key=lambda value: value["page"])
    if any(page["total"] != first["total"] for page in pages):
        raise RuntimeError("catalog total changed during pagination")
    if any(page["catalogName"] != first["catalogName"] for page in pages):
        raise RuntimeError("catalog identity changed during pagination")

    articles = [article for page in pages for article in page["articles"]]
    if len(articles) != first["total"]:
        raise RuntimeError(f"expected {first['total']} catalog articles, got {len(articles)}")
    codes = [str(article["code"]) for article in articles]
    if len(codes) != len(set(codes)):
        raise RuntimeError("duplicate article code across catalog pages")

    cutoff_ms = int(CUTOFF_EXCLUSIVE_UTC.timestamp() * 1_000)
    frozen_articles = [article for article in articles if int(article["releaseDate"]) < cutoff_ms]
    detail_articles = [
        article for article in frozen_articles if is_futures_product_title(str(article["title"]))
    ]

    # Seed the ignored, resumable cache from a prior complete artifact.  This preserves the 73
    # already-fetched title candidates when upgrading the audit to every catalog body.
    args.cache.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        previous = json.loads(args.output.read_text())
        previous_details: list[dict[str, Any]] = []
        for key in (
            "futuresProductTitleDetails",
            "catalogArticleDetails",
            "futuresDelistingCandidateDetails",
            "futuresArticleDetails",
        ):
            previous_details.extend(previous.get(key, []))
        by_code = {str(article["code"]): article for article in frozen_articles}
        for detail in previous_details:
            source_article = by_code.get(str(detail.get("code")))
            if source_article and valid_cached_detail(detail, source_article):
                cache_path = args.cache / f"{detail['code']}.json"
                if not cache_path.exists():
                    write_json_atomic(cache_path, detail)

    details: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(
                fetch_detail,
                article,
                args.cache,
                args.request_delay_ms / 1000,
            ): article
            for article in detail_articles
        }
        for number, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            details.append(future.result())
            if number % 25 == 0 or number == len(futures):
                print(f"fetched catalog article bodies {number}/{len(futures)}", flush=True)
    details.sort(key=lambda value: (value["releaseDateEpochMs"], value["code"]))

    def is_futures_delisting_candidate(article: dict[str, Any]) -> bool:
        combined = f"{article['title']}\n{article['bodyText']}".casefold()
        return (
            "delist" in combined
            and "automatic settlement" in combined
            and (
                "binance futures" in combined
                or "usdⓢ-m" in combined
                or "usdt-margined" in combined
                or "coin-m" in combined
            )
        )

    futures_delisting_candidates = [
        article for article in details if is_futures_delisting_candidate(article)
    ]

    release_dates = [int(article["releaseDate"]) for article in frozen_articles]
    output = {
        "schemaVersion": 3,
        "generatedAtUtc": iso_utc(datetime.now(timezone.utc)),
        "outcomeBlind": True,
        "explicitlyExcludedData": [
            "market prices",
            "basis",
            "funding",
            "volume",
            "open interest",
            "returns",
        ],
        "source": {
            "catalogId": CATALOG_ID,
            "catalogName": first["catalogName"],
            "listEndpoint": LIST_ENDPOINT,
            "detailEndpoint": DETAIL_ENDPOINT,
            "pageSize": PAGE_SIZE,
            "pages": pages_expected,
            "catalogTotalAtFetch": first["total"],
            "cutoffExclusiveUtc": iso_utc(CUTOFF_EXCLUSIVE_UTC),
            "oldestIncludedReleaseAtUtc": iso_utc(
                datetime.fromtimestamp(min(release_dates) / 1_000, timezone.utc)
            ),
            "newestIncludedReleaseAtUtc": iso_utc(
                datetime.fromtimestamp(max(release_dates) / 1_000, timezone.utc)
            ),
            "bodySelection": (
                "title has a futures/perpetual/USD-M/USDT-margined/coin-margined product marker "
                "and a delist/settlement/postponement/suspension/cessation event marker"
            ),
            "pageResponseSha256": [
                {"page": page["page"], "url": page["url"], "sha256": page["rawSha256"]}
                for page in pages
            ],
        },
        "counts": {
            "catalogArticles": len(articles),
            "articlesBeforeCutoff": len(frozen_articles),
            "futuresProductTitleCandidates": len(detail_articles),
            "futuresProductTitleDetailsFetched": len(details),
            "futuresDelistingBodyCandidates": len(futures_delisting_candidates),
        },
        "catalogArticlesBeforeCutoff": frozen_articles,
        "futuresProductTitleDetails": details,
        "futuresDelistingCandidateDetails": futures_delisting_candidates,
    }
    write_json_atomic(args.output, output)
    print(f"wrote {args.output} SHA256 {sha256_bytes(args.output.read_bytes())}")


if __name__ == "__main__":
    main()
