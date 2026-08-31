#!/usr/bin/env python3
"""Pull all active Polymarket markets to one JSON file for offline consistency analysis.

Run from a network Polymarket serves (it geo-blocks US IPs at DNS/CDN level; Moldova is fine):

    python3 scripts/polymarket-pull.py

Writes data/polymarket-markets.json (~a few MB). No account or key needed - the gamma API is
public. The analysis this feeds is the consistency scan: related markets priced by different
crowds (P(A) vs P(A and B), threshold ladders, mutually exclusive outcome sets summing away
from 1) checked on QUOTED prices; anything interesting then gets its order book pulled for
executable depth before being believed.
"""
import json
import pathlib
import time
import urllib.request

BASE = "https://gamma-api.polymarket.com/markets"
OUT = pathlib.Path(__file__).resolve().parent.parent / "data" / "polymarket-markets.json"

def main():
    OUT.parent.mkdir(exist_ok=True)
    markets, offset = [], 0
    while True:
        url = f"{BASE}?active=true&closed=false&limit=500&offset={offset}"
        with urllib.request.urlopen(url, timeout=30) as response:
            page = json.load(response)
        if not page:
            break
        markets.extend(page)
        offset += len(page)
        print(f"  {offset} markets...")
        time.sleep(0.5)
    keep = [
        {k: m.get(k) for k in (
            "id", "question", "conditionId", "slug", "outcomes", "outcomePrices",
            "volumeNum", "liquidityNum", "endDate", "events", "negRisk",
            "bestBid", "bestAsk", "spread", "lastTradePrice")}
        for m in markets
    ]
    OUT.write_text(json.dumps(keep))
    print(f"DONE: {len(keep)} active markets -> {OUT}")

if __name__ == "__main__":
    main()
