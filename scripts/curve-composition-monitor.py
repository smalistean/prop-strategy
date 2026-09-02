#!/usr/bin/env python3
"""Curve stablecoin-pool composition monitor.

Discovers the admitted pools (Curve API, cached), reads every pool's composition and marginal price
impact from ITS OWN on-chain state, aggregates per tracked coin, stores one row per
(observation, pool, coin) in PostgreSQL (`curve_pool_composition`, migration V31) and writes
CURVE_COMPOSITION_MONITOR.md with the alert level.

Design, discovery rule, aggregate definition and thresholds are frozen in
CURVE_MONITOR_PREREGISTRATION.md (amendments A1, A2). Actions per level: STABLECOIN_DEPEG_DOSSIER.md.
Read-only: eth_call requests plus one Curve-API GET; no keys, no orders.

Usage: python3 scripts/curve-composition-monitor.py   (DB_USER/DB_NAME/DB_PASSWORD from .env)
"""
import csv
import datetime
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "CURVE_COMPOSITION_MONITOR.md")
UNIVERSE_CACHE = os.path.join(REPO, "data", "curve-pool-universe.json")
CURVE_API = "https://api.curve.finance/api/getPools/all/ethereum"

RPCS = [os.environ["ETH_RPC"]] if os.environ.get("ETH_RPC") else [
    "https://ethereum-rpc.publicnode.com",
    "https://eth.drpc.org",
    "https://1rpc.io/eth",
]
RPC = RPCS[0]

TRACKED = ("USDT", "USDC", "USDe")
# Pinned so the monitor never goes blind if discovery fails: (name, address).
PINNED = [
    ("3pool",     "0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7"),
    ("FRAX/USDe", "0x5dc1BF6f1e983C0b21EfB003c105133736fA0743"),
]
# Discovery admission (pre-registration A2)
MIN_POOL_TVL = 1_000_000
PRICE_LO, PRICE_HI = 0.85, 1.03      # asymmetric: rich = yield-bearing/non-USD (out); cheap = depegging (kept)
# Alerting (pre-registration, unchanged by A1/A2)
EXC_L1, EXC_L2, EXC_L3 = 0.32, 0.42, 0.52      # excess = share - 1/N, single pool AND aggregate
IMP_L1, IMP_L2, IMP_L3 = 30.0, 100.0, 300.0    # bp, adverse
D7_L1 = 10.0                                   # pp rise in a coin's share over 7 days
MIN_TVL_FOR_LEVEL = 10_000_000                 # thinner pools are informational only
# Measurement
PROBE_FRACTION, PROBE_FLOOR = 0.001, 1_000.0   # near-marginal get_dy probe
SEL = {"balances": "0x4903b0d1", "A": "0xf446c1d0",
       "get_dy_i128": "0x5e0d443f", "get_dy_u256": "0x556d6e9f"}
MIN_CALL_INTERVAL = 0.35
_next_call_at = 0.0
_HDRS = {"Content-Type": "application/json",
         "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) curve-composition-monitor"}


# ----------------------------------------------------------------------------------------- chain --

def call(to, data):
    """eth_call, throttled, with RPC failover.

    Public endpoints reject a default urllib user agent (403) and rate-limit bursts even when single
    calls succeed, so requests are spaced. A revert is a definitive on-chain answer (e.g. a selector
    the pool does not implement), not a transport failure - it returns None and is not retried.
    """
    global RPC, _next_call_at
    wait = _next_call_at - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    _next_call_at = time.monotonic() + MIN_CALL_INTERVAL
    payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_call",
                          "params": [{"to": to, "data": data}, "latest"]}).encode()
    last = None
    for attempt in range(3):
        for rpc in [RPC] + [r for r in RPCS if r != RPC]:
            try:
                with urllib.request.urlopen(urllib.request.Request(rpc, payload, _HDRS), timeout=30) as r:
                    out = json.load(r)
                if "result" in out:
                    RPC = rpc
                    return out["result"]
                err = out.get("error") or {}
                if "execution reverted" in str(err.get("message", "")):
                    RPC = rpc
                    return None
                last = err
            except Exception as e:  # noqa: BLE001 - transport failure: try the next RPC
                last = e
        time.sleep(2.0 * (attempt + 1))
    raise RuntimeError(f"all RPCs failed after retries: {last}")


def word(i):
    return format(i, "064x")


def as_int(hexstr):
    return int(hexstr, 16) if hexstr and hexstr != "0x" else None


# ------------------------------------------------------------------------------------- discovery --

def discover():
    """Admitted pools per pre-registration A2, from the Curve API with a local cache fallback.

    Returns a list of dicts: name, address, coins=[{symbol, address, decimals, usdPrice}].
    """
    pools = None
    try:
        req = urllib.request.Request(CURVE_API, headers={"User-Agent": _HDRS["User-Agent"]})
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = json.load(r).get("data", {}).get("poolData", [])
        pools = []
        for p in raw:
            coins = p.get("coins") or []
            if p.get("isMetaPool") or p.get("isBroken") or not coins:
                continue
            if any(c.get("isBasePoolLpToken") for c in coins):
                continue
            if float(p.get("usdTotal") or 0) < MIN_POOL_TVL:
                continue
            syms = [c.get("symbol", "") for c in coins]
            if not any(s in TRACKED for s in syms):
                continue
            prices = [float(c.get("usdPrice") or 0) for c in coins]
            if not all(PRICE_LO <= x <= PRICE_HI for x in prices):
                continue
            pools.append({"name": "/".join(syms), "address": p["address"],
                          "coins": [{"symbol": c["symbol"], "address": c["address"],
                                     "decimals": int(c["decimals"]), "usdPrice": float(c.get("usdPrice") or 0)}
                                    for c in coins]})
        os.makedirs(os.path.dirname(UNIVERSE_CACHE), exist_ok=True)
        with open(UNIVERSE_CACHE, "w") as f:
            json.dump({"fetched_at": datetime.datetime.now(datetime.UTC).isoformat(), "pools": pools}, f, indent=1)
        source = "api"
    except Exception as e:  # noqa: BLE001 - discovery is best-effort; cache and pins keep us running
        print(f"discovery via API failed ({e}); using cache/pinned", file=sys.stderr)
        if os.path.exists(UNIVERSE_CACHE):
            with open(UNIVERSE_CACHE) as f:
                pools = json.load(f).get("pools", [])
            source = "cache"
        else:
            pools = []
            source = "pinned-only"
    # pinned pools always present (coin metadata filled from the API entry when available)
    have = {p["address"].lower() for p in pools}
    for name, addr in PINNED:
        if addr.lower() not in have:
            pools.append({"name": name, "address": addr, "coins": None})
    return pools, source


def coin_meta_on_chain(addr):
    """Fallback when a pinned pool has no API metadata: coins(i)/decimals/symbol from chain."""
    coins = []
    for i in range(8):
        r = call(addr, "0xc6610657" + word(i))
        if not r or r == "0x":
            break
        c = "0x" + r[-40:]
        if int(c, 16) == 0:
            break
        dec = as_int(call(c, "0x313ce567")) or 18
        s = call(c, "0x95d89b41") or ""
        sym = "?"
        if len(s) > 130:
            b = bytes.fromhex(s[2:])
            n = int.from_bytes(b[32:64], "big")
            sym = b[64:64 + n].decode("utf8", "ignore")
        coins.append({"symbol": sym, "address": c, "decimals": dec, "usdPrice": None})
    return coins


# ----------------------------------------------------------------------------------- measurement --

def read_pool(pool):
    coins = pool["coins"] or coin_meta_on_chain(pool["address"])
    bals = []
    for i, c in enumerate(coins):
        bals.append((as_int(call(pool["address"], SEL["balances"] + word(i))) or 0) / 10 ** c["decimals"])
    amp = as_int(call(pool["address"], SEL["A"]))
    return coins, bals, amp


def price_impact_bp(addr, i, j, dec_in, dec_out, bal_in):
    probe = max(PROBE_FLOOR, bal_in * PROBE_FRACTION)
    dx = int(probe * 10 ** dec_in)
    for sel in ("get_dy_i128", "get_dy_u256"):
        v = as_int(call(addr, SEL[sel] + word(i) + word(j) + word(dx)))
        if v:
            return (v / 10 ** dec_out / probe - 1) * 10000
    return None


def level(excess, impact_bp):
    imp = abs(impact_bp) if impact_bp is not None else 0.0
    if excess >= EXC_L3 or imp >= IMP_L3:
        return 3
    if excess >= EXC_L2 or imp >= IMP_L2:
        return 2
    if excess >= EXC_L1 or imp >= IMP_L1:
        return 1
    return 0


def agg_level(excess):
    return 3 if excess >= EXC_L3 else 2 if excess >= EXC_L2 else 1 if excess >= EXC_L1 else 0


# ------------------------------------------------------------------------------------ postgres ---

def psql(sql, args=()):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["PGPASSWORD"] = env["DB_PASSWORD"]
    cmd = ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|",
           "-U", env.get("DB_USER") or "prop_strategy_app", "-d", env.get("DB_NAME") or "prop_strategy",
           *args]
    return subprocess.run(cmd, input=sql, text=True, capture_output=True, env=env)


def shares_7d_ago():
    r = psql("SELECT DISTINCT ON (pool_address, coin_symbol) pool_address, coin_symbol, share "
             "FROM curve_pool_composition WHERE observed_at <= now() - interval '7 days' "
             "ORDER BY pool_address, coin_symbol, observed_at DESC;")
    prev = {}
    if r.returncode == 0:
        for line in r.stdout.splitlines():
            parts = line.split("|")
            if len(parts) == 3:
                prev[(parts[0].lower(), parts[1])] = float(parts[2])
    return prev


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


def store(rows):
    if not rows:
        return "no rows"
    vals = []
    for r in rows:
        vals.append("(%s,%s,%s,%s,%s,%d,%.6f,%.6f,%.6f,%s,%.2f,%s,%s)" % (
            q(r["ts"]), q(r["pool"]), q(r["pool_name"]), q(r["coin"]), q(r["coin_addr"]), r["n"],
            r["balance"], r["share"], r["excess"],
            "NULL" if r["impact"] is None else "%.4f" % r["impact"],
            r["tvl"], "NULL" if r["a"] is None else str(int(r["a"])),
            "NULL" if r["api_price"] is None else "%.6f" % r["api_price"]))
    sql = ("INSERT INTO curve_pool_composition (observed_at, pool_address, pool_name, coin_symbol, "
           "coin_address, n_coins, balance, share, excess, marginal_impact_bp, pool_tvl_usd, pool_a, "
           "api_usd_price) VALUES " + ",".join(vals) + " ON CONFLICT DO NOTHING;")
    r = psql(sql)
    return f"stored {len(rows)} rows" if r.returncode == 0 else f"PG WRITE FAILED: {r.stderr.strip()[:200]}"


# ------------------------------------------------------------------------------------------ main --

def main():
    now = datetime.datetime.now(datetime.UTC)
    ts = now.isoformat()
    pools, source = discover()
    prev = shares_7d_ago()
    rows, pool_reports = [], []
    agg_num = {c: 0.0 for c in TRACKED}
    agg_den = {c: 0.0 for c in TRACKED}
    deepest = {c: (0.0, None, None) for c in TRACKED}   # tvl, pool name, impact
    overall = 0

    for pool in pools:
        try:
            coins, bals, amp = read_pool(pool)
        except Exception as e:  # noqa: BLE001 - one bad pool must not kill the run
            pool_reports.append((pool["name"], pool["address"], None, 0.0, [], f"read failed: {e}", 0, True))
            continue
        total = sum(bals)
        if total <= 0:
            continue
        n = len(bals)
        thin = total < MIN_TVL_FOR_LEVEL
        lines, pool_lv = [], 0
        for i, c in enumerate(coins):
            share = bals[i] / total
            excess = share - 1.0 / n
            j = (i + 1) % n
            imp = price_impact_bp(pool["address"], i, j, c["decimals"], coins[j]["decimals"], bals[i])
            p = prev.get((pool["address"].lower(), c["symbol"]))
            d7 = (share - p) * 100 if p is not None else None
            lv = level(excess, imp)
            if d7 is not None and d7 >= D7_L1:
                lv = max(lv, 1)
            pool_lv = max(pool_lv, lv)
            lines.append((c["symbol"], bals[i], share, excess, imp, d7, lv))
            rows.append({"ts": ts, "pool": pool["address"], "pool_name": pool["name"], "coin": c["symbol"],
                         "coin_addr": c["address"], "n": n, "balance": bals[i], "share": share,
                         "excess": excess, "impact": imp, "tvl": total, "a": amp, "api_price": c.get("usdPrice")})
            if c["symbol"] in TRACKED:
                agg_num[c["symbol"]] += total * excess
                agg_den[c["symbol"]] += total
                if total > deepest[c["symbol"]][0]:
                    deepest[c["symbol"]] = (total, pool["name"], imp)
        if thin:
            pool_lv = 0
        overall = max(overall, pool_lv)
        pool_reports.append((pool["name"], pool["address"], amp, total, lines, None, pool_lv, thin))

    aggregates = []
    for coin in TRACKED:
        if agg_den[coin] > 0:
            ex = agg_num[coin] / agg_den[coin]
            lv = agg_level(ex)
            overall = max(overall, lv)
            aggregates.append((coin, agg_den[coin], ex, deepest[coin][1], deepest[coin][2], lv))

    stored = store(rows)

    verdict = {0: "NORMAL - no action",
               1: "LEVEL 1 WATCH - re-read the dossier, journal it, no position change",
               2: "LEVEL 2 DE-RISK - stop opening in that asset, move own capital off-venue",
               3: "LEVEL 3 ACT - flatten own-capital positions in that asset and withdraw"}[overall]

    md = ["# Curve composition monitor", "",
          "Composition read from each pool's own on-chain state; pools discovered per",
          "`CURVE_MONITOR_PREREGISTRATION.md` A2; actions in `STABLECOIN_DEPEG_DOSSIER.md`.",
          "Stored in PostgreSQL `curve_pool_composition`. Regenerate with `bash scripts/curve-monitor.sh`.", "",
          f"**As of:** {now:%Y-%m-%dT%H:%M:%SZ}  ·  pools admitted: {len(pool_reports)} (discovery: {source})  ·  {stored}", "",
          f"## Overall: {verdict}", "",
          "## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)", "",
          "| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |",
          "|---|---:|---:|---|---:|---:|"]
    for coin, tvl, ex, dp, dimp, lv in aggregates:
        md.append(f"| {coin} | ${tvl:,.0f} | {ex:+.3f} | {dp or '—'} | "
                  f"{'n/a' if dimp is None else f'{dimp:+.1f} bp'} | {lv} |")
    md += ["", "Aggregate excess isolates the coin itself: a coin under real redemption pressure is",
           "over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.", "",
           "## Pools (deepest first)", ""]
    for name, addr, amp, tvl, lines, err, lv, thin in sorted(pool_reports, key=lambda r: -r[3]):
        note = f"  ·  _below ${MIN_TVL_FOR_LEVEL/1e6:,.0f}M TVL — informational, cannot raise the overall level_" if thin else ""
        md += [f"### {name}  (`{addr}`)", ""]
        if err:
            md += [f"_{err}_", ""]
            continue
        md += [f"A = {amp if amp is not None else 'n/a'}  ·  TVL ~${tvl:,.0f}  ·  pool level: **{lv}**{note}", "",
               "| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |",
               "|---|---:|---:|---:|---:|---:|---:|"]
        for s, bal, share, excess, imp, d7, l in lines:
            md.append(f"| {s} | {bal:,.0f} | {share:.1%} | {excess:+.1%} | "
                      f"{'n/a' if imp is None else f'{imp:+.1f} bp'} | "
                      f"{'—' if d7 is None else f'{d7:+.1f} pp'} | {l} |")
        md.append("")
    md += ["---", "",
           "**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal",
           "impact is negative — it is the cheap side. A positive impact means that coin trades at a",
           "premium. Check which side is over-weighted before acting on any pool-level alert.", "",
           "Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond",
           "it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**"]
    with open(OUT, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"level {overall}; {len(pool_reports)} pools; {stored}; wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
