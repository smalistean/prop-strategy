#!/usr/bin/env python3
"""Fetches real positions from all three XVF venues and writes a matched-pairs snapshot, with
unrealized P&L, entry/chase fees, and funding collected so far.

Independent of the entry/reconcile logs on purpose: those mix successes, skips and rejections in one
stream, and a leg an operator closed by hand outside the app - like H's Binance leg during the ON
cleanup on 2026-08-19 - leaves no trace in them at all. This only asks the venues what they hold right
now, so a base with one leg instead of two shows up here regardless of how it got that way.

Fee and funding sums cover the whole life of each CURRENT position, not a fixed window - anchored to
Bybit's own `createdTime` for that base (every pair has a Bybit leg, by construction of how XVF pairs
venues), not a blind wide lookback. That distinction matters: ACE had an earlier, already-closed
15-minute test round-trip on the same symbol (the basis-divergence finding in XVF_LIVE_FINDINGS.md).
A blind lookback back to "whenever" would silently fold that unrelated, already-settled trade into the
current position's totals. Anchoring to createdTime - and filtering every fee/funding row against it
per base - excludes anything before the position that's actually open right now.

Sign conventions differ across venues and were verified, not assumed: Bybit's execFee on a "Funding"
execution is POSITIVE when funding was PAID (confirmed by summing it against the position's own
curRealisedPnl and getting an exact match - the opposite sign gave a different, wrong number).
Binance's FUNDING_FEE income and Hyperliquid's userFunding delta.usdc are both already positive-when-
received, so only Bybit's funding sum gets negated below.

Requires BINANCE_API_KEY/BINANCE_SECRET_KEY, BYBIT_API_KEY/BYBIT_SECRET_KEY and HL_ACCOUNT_ADDRESS in
the environment - same .env.* files every other XVF command sources.
"""
import hashlib
import hmac
import json
import os
import time
import urllib.request
from datetime import datetime, timezone

OUT_PATH = os.path.join(os.path.dirname(__file__), "..", "XVF_LIVE_BOOK.md")
LOOKBACK_DAYS = 3


def normalise_base(venue, venue_symbol):
    """Port of XvfConfig.normaliseBase - kept in step with it so a pair here matches the same pair
    the execution app traded, not a lookalike grouping."""
    if venue == "hyperliquid":
        raw = venue_symbol
    elif venue_symbol.endswith("USDT") or venue_symbol.endswith("USDC"):
        raw = venue_symbol[:-4]
    else:
        raw = venue_symbol
    for prefix in ("1000000", "100000", "10000", "1000"):
        if raw.startswith(prefix) and len(raw) > len(prefix) and raw[len(prefix)].isupper():
            return raw[len(prefix):]
    if raw.startswith("1M") and len(raw) > 2 and raw[2].isupper():
        return raw[2:]
    if raw.startswith("k") and len(raw) > 1 and raw[1].isupper():
        return raw[1:]
    return raw


def binance_legs(base_entry_ms):
    key = os.environ["BINANCE_API_KEY"]
    secret = os.environ["BINANCE_SECRET_KEY"]

    def signed_get(path, params):
        params = dict(params)
        params["timestamp"] = int(time.time() * 1000)
        qs = "&".join(f"{k}={v}" for k, v in params.items())
        sig = hmac.new(secret.encode(), qs.encode(), hashlib.sha256).hexdigest()
        req = urllib.request.Request(f"https://fapi.binance.com{path}?{qs}&signature={sig}",
                                      headers={"X-MBX-APIKEY": key})
        return json.loads(urllib.request.urlopen(req).read())

    positions = {p["symbol"]: p for p in signed_get("/fapi/v2/positionRisk", {})
                 if float(p["positionAmt"]) != 0}

    # Per symbol, not one global window: each position's own entry time (via its base's Bybit
    # createdTime), falling back to LOOKBACK_DAYS only for a base createdTime somehow didn't cover.
    entry_ms = {sym: base_entry_ms.get(normalise_base("binance", sym),
                                        int(time.time() * 1000) - LOOKBACK_DAYS * 86400_000)
                for sym in positions}
    earliest = min(entry_ms.values(), default=int(time.time() * 1000))

    # Commission with a BNB fee discount enabled is charged IN BNB, not USDT - the income row's
    # "asset" field says which. Missing this made every Binance leg's fee look like ~$0.00003
    # (the raw BNB quantity, not its USD value) instead of the real ~5bp taker fee.
    #
    # Converted at BNB's price AT the minute each fee was charged, not one current price applied
    # to all of them - BNB moves on its own schedule, independent of whatever altcoin the fee was
    # actually paid on, and the fees are scattered across the whole holding period.
    bnb_klines = []   # [(open_time_ms, close_price), ...] ascending
    cursor = earliest
    while cursor < int(time.time() * 1000):
        batch = json.loads(urllib.request.urlopen(
            f"https://fapi.binance.com/fapi/v1/klines?symbol=BNBUSDT&interval=1m"
            f"&startTime={cursor}&limit=1500").read())
        if not batch:
            break
        bnb_klines.extend((k[0], float(k[4])) for k in batch)
        cursor = batch[-1][0] + 60_000
        if len(batch) < 1500:
            break
    bnb_times = [t for t, _ in bnb_klines]

    def bnb_price_at(ms):
        import bisect
        i = bisect.bisect_right(bnb_times, ms) - 1
        i = max(0, min(i, len(bnb_klines) - 1))
        return bnb_klines[i][1]

    fee_paid = {s: 0.0 for s in positions}
    funding_received = {s: 0.0 for s in positions}
    for r in signed_get("/fapi/v1/income", {"startTime": earliest, "limit": 1000}):
        sym = r.get("symbol")
        if sym not in positions or int(r["time"]) < entry_ms[sym]:
            continue   # older than this specific position's own entry - not this position's cost
        amt = float(r["income"])
        if r["incomeType"] == "COMMISSION":
            usd = amt * bnb_price_at(int(r["time"])) if r.get("asset") == "BNB" else amt
            fee_paid[sym] -= usd   # commission income is negative; cost is its magnitude
        elif r["incomeType"] == "FUNDING_FEE":
            funding_received[sym] += amt   # already positive-when-received, always USDT

    out = []
    for sym, p in positions.items():
        amt = float(p["positionAmt"])
        out.append({"venue": "binance", "symbol": sym, "qty": amt,
                    "notional": abs(amt) * float(p["markPrice"]),
                    "unrealized": float(p["unRealizedProfit"]),
                    "fee_paid": fee_paid[sym], "funding_received": funding_received[sym]})
    return out


def bybit_positions_raw():
    """Positions only, with createdTime - fetched first so its authoritative per-base entry time can
    anchor every venue's fee/funding window, not just Bybit's own."""
    key = os.environ["BYBIT_API_KEY"]
    secret = os.environ["BYBIT_SECRET_KEY"]

    def signed_get(path, qs):
        ts = str(int(time.time() * 1000))
        recv = "5000"
        sig = hmac.new(secret.encode(), (ts + key + recv + qs).encode(), hashlib.sha256).hexdigest()
        req = urllib.request.Request(f"https://api.bybit.com{path}?{qs}",
            headers={"X-BAPI-API-KEY": key, "X-BAPI-TIMESTAMP": ts, "X-BAPI-RECV-WINDOW": recv,
                     "X-BAPI-SIGN": sig})
        return json.loads(urllib.request.urlopen(req).read())

    return {p["symbol"]: p for p in
            signed_get("/v5/position/list", "category=linear&settleCoin=USDT")["result"]["list"]
            if float(p["size"]) != 0}, signed_get


def bybit_legs(positions, signed_get, base_entry_ms):
    entry_ms = {sym: base_entry_ms.get(normalise_base("bybit", sym), int(positions[sym]["createdTime"]))
                for sym in positions}
    earliest = min(entry_ms.values(), default=int(time.time() * 1000))

    fee_paid = {s: 0.0 for s in positions}
    funding_received = {s: 0.0 for s in positions}
    cursor = ""
    for _ in range(20):
        qs = f"category=linear&startTime={earliest}&limit=200"
        if cursor:
            qs += f"&cursor={cursor}"
        ex = signed_get("/v5/execution/list", qs)
        for r in ex["result"]["list"]:
            sym = r["symbol"]
            if sym not in positions or int(r["execTime"]) < entry_ms[sym]:
                continue   # older than this specific position's own entry
            fee = float(r["execFee"])   # positive = paid, for both Trade and Funding rows - verified
            if r["execType"] == "Trade":
                fee_paid[sym] += fee
            elif r["execType"] == "Funding":
                funding_received[sym] -= fee
        cursor = ex["result"].get("nextPageCursor")
        if not cursor:
            break

    out = []
    for sym, p in positions.items():
        sz = float(p["size"])
        signed = sz if p["side"] == "Buy" else -sz
        out.append({"venue": "bybit", "symbol": sym, "qty": signed,
                    "notional": float(p["positionValue"]),
                    "unrealized": float(p["unrealisedPnl"]),
                    "fee_paid": fee_paid[sym], "funding_received": funding_received[sym]})
    return out


def hyperliquid_legs(base_entry_ms):
    addr = os.environ["HL_ACCOUNT_ADDRESS"]

    def info(payload):
        req = urllib.request.Request("https://api.hyperliquid.xyz/info",
                                      data=json.dumps(payload).encode(),
                                      headers={"Content-Type": "application/json"})
        return json.loads(urllib.request.urlopen(req).read())

    state = info({"type": "clearinghouseState", "user": addr})
    positions = {}
    for p in state.get("assetPositions", []):
        pos = p["position"]
        if float(pos["szi"]) != 0:
            positions[pos["coin"]] = pos

    entry_ms = {coin: base_entry_ms.get(normalise_base("hyperliquid", coin),
                                         int(time.time() * 1000) - LOOKBACK_DAYS * 86400_000)
                for coin in positions}
    earliest = min(entry_ms.values(), default=int(time.time() * 1000))

    fee_paid = {c: 0.0 for c in positions}
    funding_received = {c: 0.0 for c in positions}
    for f in info({"type": "userFillsByTime", "user": addr, "startTime": earliest}):
        coin = f["coin"]
        if coin in positions and int(f["time"]) >= entry_ms[coin]:
            fee_paid[coin] += float(f["fee"])   # always a cost
    for r in info({"type": "userFunding", "user": addr, "startTime": earliest}):
        coin = r["delta"].get("coin")
        if coin in positions and int(r["time"]) >= entry_ms[coin]:
            funding_received[coin] += float(r["delta"]["usdc"])   # positive = received

    out = []
    for coin, pos in positions.items():
        szi = float(pos["szi"])
        out.append({"venue": "hyperliquid", "symbol": coin, "qty": szi,
                    "notional": abs(float(pos["positionValue"])),
                    "unrealized": float(pos["unrealizedPnl"]),
                    "fee_paid": fee_paid[coin], "funding_received": funding_received[coin]})
    return out


def main():
    bybit_positions, bybit_signed_get = bybit_positions_raw()
    base_entry_ms = {}
    for sym, p in bybit_positions.items():
        base = normalise_base("bybit", sym)
        base_entry_ms[base] = min(base_entry_ms.get(base, int(p["createdTime"])), int(p["createdTime"]))

    legs = (binance_legs(base_entry_ms) + bybit_legs(bybit_positions, bybit_signed_get, base_entry_ms)
            + hyperliquid_legs(base_entry_ms))
    by_base = {}
    for leg in legs:
        by_base.setdefault(normalise_base(leg["venue"], leg["symbol"]), []).append(leg)

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    matched = {b: l for b, l in by_base.items() if len(l) == 2}
    unmatched = {b: l for b, l in by_base.items() if len(l) != 2}

    oldest = min(base_entry_ms.values(), default=None)
    newest = max(base_entry_ms.values(), default=None)
    oldest_s = datetime.fromtimestamp(oldest / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M UTC") if oldest else "?"
    newest_s = datetime.fromtimestamp(newest / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M UTC") if newest else "?"

    total_notional = sum(leg["notional"] for l in by_base.values() for leg in l)
    total_unrealized = sum(leg["unrealized"] for l in by_base.values() for leg in l)
    total_fees = sum(leg["fee_paid"] for l in by_base.values() for leg in l)
    total_funding = sum(leg["funding_received"] for l in by_base.values() for leg in l)
    total_net = total_unrealized + total_funding - total_fees

    lines = [
        "# XVF live book",
        "",
        "Snapshot from the venues directly, not from any run's log. Regenerate with "
        "`python3 scripts/xvf-position-snapshot.py`.",
        "",
        f"**As of:** {now}  |  fees and funding cover each pair's whole life, from its own entry "
        f"(oldest {oldest_s}, newest {newest_s})",
        "",
        f"**{len(matched)} matched pairs, {sum(len(l) for l in matched.values())} legs, "
        f"~{total_notional:,.0f} USD gross notional**",
        "",
        f"**Unrealized {total_unrealized:+,.2f} + funding {total_funding:+,.2f} "
        f"- fees {total_fees:,.2f} = net {total_net:+,.2f} USD**",
        "",
    ]

    if unmatched:
        lines.append(f"## Unmatched — {len(unmatched)} base(s) with other than 2 legs")
        lines.append("")
        lines.append("A base without exactly two legs is not hedged. Investigate before assuming it "
                      "is fine.")
        lines.append("")
        for base in sorted(unmatched):
            lines.append(f"- **{base}**")
            for leg in unmatched[base]:
                lines.append(f"  - {leg['venue']:12} {leg['symbol']:16} {leg['qty']:>14.4f}  "
                              f"~{leg['notional']:>8.2f} USD")
        lines.append("")

    lines.append("## Matched pairs")
    lines.append("")
    lines.append("| Base | Leg | Qty | Notional | Unrealized | Funding | Fees | Net |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
    pair_totals = []
    for base in sorted(matched):
        pu = pf = pfee = 0.0
        for leg in sorted(matched[base], key=lambda l: l["venue"]):
            net = leg["unrealized"] + leg["funding_received"] - leg["fee_paid"]
            pu += leg["unrealized"]; pf += leg["funding_received"]; pfee += leg["fee_paid"]
            lines.append(f"| {base} | {leg['venue']} {leg['symbol']} | {leg['qty']:.4f} | "
                          f"{leg['notional']:,.2f} | {leg['unrealized']:+.3f} | "
                          f"{leg['funding_received']:+.3f} | {leg['fee_paid']:.3f} | {net:+.3f} |")
        pair_totals.append((base, pu, pf, pfee, pu + pf - pfee))
    lines.append("")

    lines.append("## Per-pair totals")
    lines.append("")
    lines.append("| Base | Unrealized | Funding | Fees | Net |")
    lines.append("|---|---:|---:|---:|---:|")
    for base, pu, pf, pfee, pnet in sorted(pair_totals, key=lambda x: x[4]):
        lines.append(f"| {base} | {pu:+.3f} | {pf:+.3f} | {pfee:.3f} | {pnet:+.3f} |")

    content = "\n".join(lines) + "\n"
    with open(OUT_PATH, "w") as f:
        f.write(content)

    print(f"{len(matched)} matched pairs, {len(unmatched)} unmatched base(s), "
          f"~{total_notional:,.0f} USD gross notional")
    print(f"unrealized {total_unrealized:+,.2f}  funding {total_funding:+,.2f}  "
          f"fees {total_fees:,.2f}  net {total_net:+,.2f}")
    print(f"written to {os.path.abspath(OUT_PATH)}")
    if unmatched:
        print("!! unmatched bases found - see the file for detail")


if __name__ == "__main__":
    main()
