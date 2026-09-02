#!/usr/bin/env python3
"""Curve stablecoin-pool monitor: composition (leading depeg indicator) + wrapper NAV discount.

Discovers admitted pools (Curve API, cached), reads every pool's state from ITS OWN on-chain
contract, aggregates composition per tracked coin, measures yield-bearing wrappers (sUSDe) against
their redemption NAV, stores rows in PostgreSQL (`curve_pool_composition` V31,
`curve_wrapper_nav_discount` V32) and writes CURVE_COMPOSITION_MONITOR.md with the alert level.

Design, discovery rules, metric definitions and thresholds are frozen in
CURVE_MONITOR_PREREGISTRATION.md (amendments A1-A3). Actions per level: STABLECOIN_DEPEG_DOSSIER.md.
Read-only: eth_call requests plus one Curve-API GET; no keys, no orders.

Usage: python3 scripts/curve-composition-monitor.py   (DB_USER/DB_NAME/DB_PASSWORD from .env)
"""
import datetime
import json
import os
import subprocess
import sys
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
WRAPPERS = ("sUSDe",)                    # ERC-4626 wrappers measured against redemption NAV (A3)
EXCLUDED_COINS = {                        # A4: no live par-redemption path -> not a $1 asset; needs a DD note to add
    "FRAX": "legacy FRAX: no issuer redemption, 1:1 migration ended (FIP-430) - FRAX_LEGACY_FRXUSD_DD.md",
}
PINNED = [                               # never go blind if discovery fails
    ("3pool",     "0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7"),
]                                        # FRAX/USDe un-pinned by A4
# Discovery admission (A2/A3)
MIN_POOL_TVL = 1_000_000
PRICE_LO, PRICE_HI = 0.85, 1.03          # asymmetric: rich = yield-bearing/non-USD (out); cheap = depegging (kept)
# Alerting (original pre-registration, unchanged)
EXC_L1, EXC_L2, EXC_L3 = 0.32, 0.42, 0.52        # excess = share - 1/N, single pool AND aggregate
IMP_L1, IMP_L2, IMP_L3 = 30.0, 100.0, 300.0      # bp, adverse
D7_L1 = 10.0                                     # pp rise in a coin's share over 7 days
NAV_L1, NAV_L2, NAV_L3 = -50.0, -200.0, -500.0   # bp discount to NAV (A3)
MIN_TVL_FOR_LEVEL = 10_000_000                   # thinner pools are informational only
# A5: crvUSD PegKeepers - the Regulator's own contrary-coin test, relative gap in bp above the highest other PK pool
PEGKEEPER_REGULATOR = "0x36a04CAffc681fa179558B2Aaba30395CDdd855f"
CRVUSD_FACTORY = "0xC9332fdCB1C491Dcc683bAe86Fe3cb70360738BC"
CRVUSD = "0xf939E0A03FB07F59A73314E73794Be0E57ac1b4E"
GAP_L1, GAP_L2, GAP_L3 = 3.0, 30.0, 100.0        # L1 = Regulator worst_price_threshold, with the pool counter-heavy
SEL5 = {"peg_keepers": "0xf6235138", "aggregator": "0x245a7bfc", "price": "0xa035b1fe", "debt": "0x0dca59c1", "debt_ceiling": "0x602b62d4", "balanceOf": "0x70a08231", "totalSupply": "0x18160ddd", "price_oracle": "0x86fc88d3", "price_oracle_i": "0x68727653", "provide_allowed": "0x4476d2bb", "withdraw_allowed": "0x990ca2b0", "coins": "0xc6610657", "balances": "0x4903b0d1", "symbol": "0x95d89b41", "decimals": "0x313ce567"}
# Measurement
PROBE_FRACTION, PROBE_FLOOR = 0.001, 1_000.0     # near-marginal probe
SEL = {"balances": "0x4903b0d1", "A": "0xf446c1d0", "stored_rates": "0xfd0684b1",
       "get_dy_i128": "0x5e0d443f", "get_dy_u256": "0x556d6e9f", "convertToAssets": "0x07a2d13a"}
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


def dyn_uint_array(hexstr):
    """Decode an ABI dynamic uint256[] return (offset, length, elements)."""
    if not hexstr or len(hexstr) < 130:
        return None
    r = hexstr[2:]
    off = int(r[0:64], 16) * 2
    n = int(r[off:off + 64], 16)
    return [int(r[off + 64 + i * 64: off + 128 + i * 64], 16) for i in range(n)]


# ------------------------------------------------------------------------------------- discovery --

def _in_band(c):
    p = float(c.get("usdPrice") or 0)
    return PRICE_LO <= p <= PRICE_HI


def discover():
    """Admitted composition pools and wrapper pools per A2/A3, from the Curve API with cache fallback."""
    comp, wrap, source = [], [], "api"
    try:
        req = urllib.request.Request(CURVE_API, headers={"User-Agent": _HDRS["User-Agent"]})
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = json.load(r).get("data", {}).get("poolData", [])
        for p in raw:
            coins = p.get("coins") or []
            if p.get("isMetaPool") or p.get("isBroken") or not coins:
                continue
            if any(c.get("isBasePoolLpToken") for c in coins):
                continue
            if float(p.get("usdTotal") or 0) < MIN_POOL_TVL:
                continue
            syms = [c.get("symbol", "") for c in coins]
            if any(s in EXCLUDED_COINS for s in syms):   # A4
                continue
            entry = {"name": "/".join(syms), "address": p["address"],
                     "coins": [{"symbol": c["symbol"], "address": c["address"], "decimals": int(c["decimals"]),
                                "usdPrice": float(c.get("usdPrice") or 0)} for c in coins]}
            wrappers_here = [s for s in syms if s in WRAPPERS]
            if wrappers_here:
                # A3: exactly one wrapper, every other coin a nominal-$1 stable in band
                others = [c for c in coins if c.get("symbol") not in WRAPPERS]
                if len(wrappers_here) == 1 and others and all(_in_band(c) for c in others):
                    wrap.append(entry)
                continue
            if any(s in TRACKED for s in syms) and all(_in_band(c) for c in coins):
                comp.append(entry)
        os.makedirs(os.path.dirname(UNIVERSE_CACHE), exist_ok=True)
        with open(UNIVERSE_CACHE, "w") as f:
            json.dump({"fetched_at": datetime.datetime.now(datetime.UTC).isoformat(),
                       "pools": comp, "wrapper_pools": wrap}, f, indent=1)
    except Exception as e:  # noqa: BLE001 - discovery is best-effort; cache and pins keep us running
        print(f"discovery via API failed ({e}); using cache/pinned", file=sys.stderr)
        if os.path.exists(UNIVERSE_CACHE):
            with open(UNIVERSE_CACHE) as f:
                d = json.load(f)
            comp, wrap, source = d.get("pools", []), d.get("wrapper_pools", []), "cache"
        else:
            source = "pinned-only"
    have = {p["address"].lower() for p in comp}
    for name, addr in PINNED:
        if addr.lower() not in have:
            comp.append({"name": name, "address": addr, "coins": None})
    return comp, wrap, source


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
    bals = [(as_int(call(pool["address"], SEL["balances"] + word(i))) or 0) / 10 ** c["decimals"]
            for i, c in enumerate(coins)]
    amp = as_int(call(pool["address"], SEL["A"]))
    return coins, bals, amp


def get_dy(addr, i, j, dx_units):
    for sel in ("get_dy_i128", "get_dy_u256"):
        v = as_int(call(addr, SEL[sel] + word(i) + word(j) + word(dx_units)))
        if v:
            return v
    return None


def price_impact_bp(addr, i, j, dec_in, dec_out, bal_in):
    probe = max(PROBE_FLOOR, bal_in * PROBE_FRACTION)
    v = get_dy(addr, i, j, int(probe * 10 ** dec_in))
    return None if v is None else (v / 10 ** dec_out / probe - 1) * 10000


def measure_wrapper_pool(pool):
    """A3: wrapper pool-implied price vs redemption NAV. Returns a dict or None."""
    coins, bals, _ = read_pool(pool)
    wi = next((k for k, c in enumerate(coins) if c["symbol"] in WRAPPERS), None)
    if wi is None or len(coins) < 2:
        return None
    ci = next(k for k in range(len(coins)) if k != wi)      # the (first) $1 counter-asset
    w, c = coins[wi], coins[ci]
    nav = (as_int(call(w["address"], SEL["convertToAssets"] + word(10 ** 18))) or 0) / 1e18
    if nav <= 0:
        return None
    probe = max(PROBE_FLOOR, bals[wi] * PROBE_FRACTION)
    v = get_dy(pool["address"], wi, ci, int(probe * 10 ** w["decimals"]))
    if v is None:
        return None
    implied = v / 10 ** c["decimals"] / probe
    rates = dyn_uint_array(call(pool["address"], SEL["stored_rates"]))
    stored = rates[wi] / 1e18 if rates and len(rates) > wi else None
    tvl = sum(b * (nav if k == wi else 1.0) for k, b in enumerate(bals))
    return {"pool": pool["address"], "pool_name": pool["name"], "wrapper": w["symbol"], "wrapper_addr": w["address"],
            "counter": c["symbol"], "nav": nav, "implied": implied,
            "discount_bp": (implied / nav - 1) * 10000, "stored_rate": stored, "tvl": tvl}


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


def nav_level(discount_bp):
    return 3 if discount_bp <= NAV_L3 else 2 if discount_bp <= NAV_L2 else 1 if discount_bp <= NAV_L1 else 0


# ------------------------------------------------------------------------------------ postgres ---

def psql(sql):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["PGPASSWORD"] = env["DB_PASSWORD"]
    cmd = ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|",
           "-U", env.get("DB_USER") or "prop_strategy_app", "-d", env.get("DB_NAME") or "prop_strategy"]
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


def nz(v, fmt):
    return "NULL" if v is None else fmt % v


def store_composition(rows):
    if not rows:
        return "no composition rows"
    vals = ["(%s,%s,%s,%s,%s,%d,%.6f,%.6f,%.6f,%s,%.2f,%s,%s)" % (
        q(r["ts"]), q(r["pool"]), q(r["pool_name"]), q(r["coin"]), q(r["coin_addr"]), r["n"],
        r["balance"], r["share"], r["excess"], nz(r["impact"], "%.4f"), r["tvl"],
        "NULL" if r["a"] is None else str(int(r["a"])), nz(r["api_price"], "%.6f")) for r in rows]
    sql = ("INSERT INTO curve_pool_composition (observed_at, pool_address, pool_name, coin_symbol, coin_address, "
           "n_coins, balance, share, excess, marginal_impact_bp, pool_tvl_usd, pool_a, api_usd_price) VALUES "
           + ",".join(vals) + " ON CONFLICT DO NOTHING;")
    r = psql(sql)
    return f"stored {len(rows)} composition rows" if r.returncode == 0 else f"PG WRITE FAILED: {r.stderr.strip()[:200]}"


def store_wrappers(ts, ws):
    if not ws:
        return "no wrapper rows"
    vals = ["(%s,%s,%s,%s,%s,%s,%.10f,%.10f,%.4f,%s,%.2f)" % (
        q(ts), q(w["pool"]), q(w["pool_name"]), q(w["wrapper"]), q(w["wrapper_addr"]), q(w["counter"]),
        w["nav"], w["implied"], w["discount_bp"], nz(w["stored_rate"], "%.10f"), w["tvl"]) for w in ws]
    sql = ("INSERT INTO curve_wrapper_nav_discount (observed_at, pool_address, pool_name, wrapper_symbol, "
           "wrapper_address, counter_symbol, nav, pool_implied_price, nav_discount_bp, pool_stored_rate, pool_tvl_usd) "
           "VALUES " + ",".join(vals) + " ON CONFLICT DO NOTHING;")
    r = psql(sql)
    return f"stored {len(ws)} wrapper rows" if r.returncode == 0 else f"PG WRAPPER WRITE FAILED: {r.stderr.strip()[:200]}"


# ------------------------------------------------------------------------------------------ main --

# ---- A5: crvUSD PegKeepers ---------------------------------------------------------------------
def _h_addr(h): return ("0x" + h[-40:]) if h and len(h) >= 42 else None
def _h_int(h): return int(h, 16) if h and h != "0x" else None
def _e_u(n): return format(n, "064x")
def _e_a(a): return a[2:].lower().rjust(64, "0")
def _h_str(h):
    try:
        b = bytes.fromhex(h[2:]); off = int.from_bytes(b[:32], "big"); ln = int.from_bytes(b[off:off + 32], "big")
        return b[off + 32:off + 32 + ln].decode(errors="replace")
    except Exception:  # noqa: BLE001
        return "?"
def _c(to, data):
    try:
        return call(to, data)
    except Exception:  # noqa: BLE001
        return None


def read_pegkeepers():
    """One row per PegKeeper registered in the Regulator: debt, capacity, LP share, the relative-gap test."""
    agg = _h_addr(_c(PEGKEEPER_REGULATOR, SEL5["aggregator"]))
    agg_price = _h_int(_c(agg, SEL5["price"])) if agg else None
    agg_price = agg_price / 1e18 if agg_price else None
    infos = []
    for i in range(20):
        r = _c(PEGKEEPER_REGULATOR, SEL5["peg_keepers"] + _e_u(i))
        if not r or r == "0x":
            break
        b = bytes.fromhex(r[2:])
        infos.append({"pk": "0x" + b[12:32].hex(), "pool": "0x" + b[44:64].hex(),
                      "inverse": int.from_bytes(b[64:96], "big"), "index": int.from_bytes(b[96:128], "big") if len(b) >= 128 else 0})
    rows = []
    for info in infos:
        pk, pool = info["pk"], info["pool"]
        coins = []
        for j in range(2):
            ca = _h_addr(_c(pool, SEL5["coins"] + _e_u(j)))
            if not ca:
                break
            sym = _h_str(_c(ca, SEL5["symbol"]) or "0x"); dec = _h_int(_c(ca, SEL5["decimals"])) or 18
            bal = (_h_int(_c(pool, SEL5["balances"] + _e_u(j))) or 0) / 10 ** dec
            coins.append((ca.lower(), sym, bal))
        if len(coins) != 2:
            continue
        ci = next((k for k, c in enumerate(coins) if c[0] == CRVUSD.lower()), None)
        if ci is None:
            continue
        counter = coins[1 - ci]; tvl = coins[0][2] + coins[1][2]
        po = _h_int(_c(pool, SEL5["price_oracle_i"] + _e_u(0)) if info["index"] else _c(pool, SEL5["price_oracle"]))
        price = (po / 1e18) if po else None
        if price and info["inverse"]:
            price = 1 / price
        debt = (_h_int(_c(pk, SEL5["debt"])) or 0) / 1e18
        ceiling = (_h_int(_c(CRVUSD_FACTORY, SEL5["debt_ceiling"] + _e_a(pk))) or 0) / 1e18
        idle = (_h_int(_c(CRVUSD, SEL5["balanceOf"] + _e_a(pk))) or 0) / 1e18
        lp = _h_int(_c(pool, SEL5["balanceOf"] + _e_a(pk))) or 0; lps = _h_int(_c(pool, SEL5["totalSupply"])) or 0
        pa = _h_int(_c(PEGKEEPER_REGULATOR, SEL5["provide_allowed"] + _e_a(pk)))
        wa = _h_int(_c(PEGKEEPER_REGULATOR, SEL5["withdraw_allowed"] + _e_a(pk)))
        def _allowed(v):
            return None if v is None else (float("inf") if v > 10 ** 40 else v / 1e18)
        rows.append({"pk": pk, "pool": pool, "name": f"{coins[0][1]}/{coins[1][1]}", "counter": counter[1],
                     "counter_share": (counter[2] / tvl) if tvl else 0.0, "tvl": tvl, "debt": debt, "ceiling": ceiling,
                     "idle": idle, "lp_share": (lp / lps) if lps else None, "price": price,
                     "provide": _allowed(pa), "withdraw": _allowed(wa), "agg": agg_price})
    for r in rows:
        others = [o["price"] for o in rows if o is not r and o["price"]]
        r["gap_bp"] = ((r["price"] - max(others)) * 1e4) if (r["price"] and others) else None
        lv = 0
        if r["gap_bp"] is not None and r["counter"] in TRACKED:
            if r["gap_bp"] >= GAP_L3: lv = 3
            elif r["gap_bp"] >= GAP_L2: lv = 2
            elif r["gap_bp"] >= GAP_L1 and r["counter_share"] > 0.5: lv = 1
        r["thin"] = r["tvl"] < MIN_TVL_FOR_LEVEL
        r["level"] = lv
    return rows


def store_pegkeepers(ts, rows):
    if not rows:
        return "stored 0 pegkeeper rows"
    def f(v, fmt="%.6f"):
        return "NULL" if v is None or v == float("inf") else (fmt % v)
    vals = ", ".join(
        f"('{ts}', '{r['pk']}', '{r['pool']}', '{r['name']}', '{r['counter']}', {f(r['counter_share'])}, {f(r['tvl'], '%.2f')}, "
        f"{f(r['debt'])}, {f(r['ceiling'])}, {f(r['idle'])}, {f(r['lp_share'])}, {f(r['price'], '%.12f')}, {f(r['gap_bp'], '%.4f')}, "
        f"{f(r['provide'])}, {f(r['withdraw'])}, {f(r['agg'], '%.12f')}, {r['level']})" for r in rows)
    sql = ("INSERT INTO curve_pegkeeper_state (observed_at, peg_keeper, pool_address, pool_name, counter_symbol, counter_share, "
           "pool_tvl_usd, debt, debt_ceiling, idle_crvusd, lp_share, oracle_price, gap_bp, provide_allowed, withdraw_allowed, "
           f"aggregate_price, alert_level) VALUES {vals} ON CONFLICT DO NOTHING")
    try:
        subprocess.run(["psql", "-X", "-U", "prop_strategy_app", "-d", "prop_strategy", "-qAtc", sql], check=True, capture_output=True)
        return f"stored {len(rows)} pegkeeper rows"
    except Exception as e:  # noqa: BLE001
        return f"pegkeeper store FAILED: {e}"


def pegkeeper_section(rows):
    if not rows:
        return ["## crvUSD PegKeepers (A5)", "", "_read failed_", ""]
    agg = rows[0]["agg"]
    md = ["## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)", "",
          f"Aggregate crvUSD price **{agg:.5f}** -> PegKeepers may {'PROVIDE (a counter-coin inflow into these pools is damped)' if agg and agg >= 1 else 'only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free)'}.", "",
          "| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |",
          "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for r in rows:
        note = " _(thin)_" if r["thin"] else ""
        pa = "n/a" if r["provide"] is None else ("unlimited" if r["provide"] == float("inf") else f"{r['provide']:,.0f}")
        md.append(f"| {r['name']}{note} | {r['counter']} | {r['counter_share']*100:.1f}% | ${r['tvl']:,.0f} | {r['debt']:,.0f} | {r['ceiling']:,.0f} | "
                  f"{'n/a' if r['lp_share'] is None else f'{r['lp_share']*100:.1f}%'} | {'n/a' if r['price'] is None else f'{r['price']:.5f}'} | "
                  f"{'n/a' if r['gap_bp'] is None else f'{r['gap_bp']:+.1f} bp'} | {pa} | {r['level']} |")
    md += ["", f"Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks",
           f"`provide` above +{GAP_L1:.0f} bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.",
           f"Levels (tracked counter-coins only, pools >= ${MIN_TVL_FOR_LEVEL/1e6:,.0f}M): 1 at >= +{GAP_L1:.0f} bp with the pool counter-heavy, 2 at >= +{GAP_L2:.0f} bp, 3 at >= +{GAP_L3:.0f} bp.", ""]
    return md


def _insert_pegkeeper_section(md, rows):
    i = next((k for k, s in enumerate(md) if str(s).startswith("## Pools")), len(md))
    return md[:i] + pegkeeper_section(rows) + md[i:]


def main():
    now = datetime.datetime.now(datetime.UTC)
    ts = now.isoformat()
    comp_pools, wrap_pools, source = discover()
    prev = shares_7d_ago()
    rows, pool_reports = [], []
    agg_num = {c: 0.0 for c in TRACKED}
    agg_den = {c: 0.0 for c in TRACKED}
    deepest = {c: (0.0, None, None) for c in TRACKED}
    overall = 0

    for pool in comp_pools:
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
                         "coin_addr": c["address"], "n": n, "balance": bals[i], "share": share, "excess": excess,
                         "impact": imp, "tvl": total, "a": amp, "api_price": c.get("usdPrice")})
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

    uncovered = [c for c in TRACKED if agg_den[c] <= 0]   # A4: say so instead of going quiet

    wrappers = []
    for pool in wrap_pools:
        try:
            m = measure_wrapper_pool(pool)
        except Exception as e:  # noqa: BLE001
            print(f"wrapper pool {pool['name']} failed: {e}", file=sys.stderr)
            continue
        if not m:
            continue
        lv = nav_level(m["discount_bp"])
        m["level"] = lv
        m["thin"] = m["tvl"] < MIN_TVL_FOR_LEVEL
        if not m["thin"]:
            overall = max(overall, lv)
        wrappers.append(m)

    pegkeepers = []
    try:
        pegkeepers = read_pegkeepers()
    except Exception as e:  # noqa: BLE001
        print(f"pegkeeper read failed: {e}", file=sys.stderr)
    for r in pegkeepers:
        if not r["thin"]:
            overall = max(overall, r["level"])

    stored = store_composition(rows)
    stored_w = store_wrappers(ts, wrappers)
    stored_pk = store_pegkeepers(ts, pegkeepers)

    verdict = {0: "NORMAL - no action",
               1: "LEVEL 1 WATCH - re-read the dossier, journal it, no position change",
               2: "LEVEL 2 DE-RISK - stop opening in that asset, move own capital off-venue",
               3: "LEVEL 3 ACT - flatten own-capital positions in that asset and withdraw"}[overall]

    md = ["# Curve composition monitor", "",
          "Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per",
          "`CURVE_MONITOR_PREREGISTRATION.md` (A2-A5); actions in `STABLECOIN_DEPEG_DOSSIER.md`.",
          "Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.",
          "Regenerate with `bash scripts/curve-monitor.sh`.", "",
          f"**As of:** {now:%Y-%m-%dT%H:%M:%SZ}  ·  composition pools: {len(pool_reports)}, wrapper pools: {len(wrappers)} "
          f"(discovery: {source})  ·  {stored}; {stored_w}; {stored_pk}", "",
          f"## Overall: {verdict}", "",
          *([f"**Coverage gap (A4):** no admitted composition pool holds {', '.join(uncovered)} - every pool with it "
             f"is below the ${MIN_POOL_TVL/1e6:,.0f}M admission or contains an excluded coin ({', '.join(EXCLUDED_COINS)}). "
             f"Monitored through the wrapper NAV metric and the API price band only.", ""] if uncovered else []),
          "## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)", "",
          "| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |",
          "|---|---:|---:|---|---:|---:|"]
    for coin, tvl, ex, dp, dimp, lv in aggregates:
        md.append(f"| {coin} | ${tvl:,.0f} | {ex:+.3f} | {dp or '—'} | "
                  f"{'n/a' if dimp is None else f'{dimp:+.1f} bp'} | {lv} |")
    md += ["", "Aggregate excess isolates the coin itself: a coin under real redemption pressure is",
           "over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.", "",
           "## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)", ""]
    if wrappers:
        md += ["| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |",
               "|---|---|---:|---:|---:|---:|---:|"]
        for w in sorted(wrappers, key=lambda x: -x["tvl"]):
            note = " _(thin, informational)_" if w["thin"] else ""
            md.append(f"| {w['pool_name']} | {w['wrapper']} | {w['nav']:.4f} {w['counter']}≈USDe | {w['implied']:.4f} "
                      f"{w['counter']} | **{w['discount_bp']:+.1f} bp** | ${w['tvl']:,.0f} | {w['level']}{note} |")
        md += ["", "Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/",
               "duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.", ""]
    else:
        md += ["_No wrapper pool admitted this run._", ""]
    md += ["## Pools (deepest first)", ""]
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
    md = _insert_pegkeeper_section(md, pegkeepers)
    with open(OUT, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"level {overall}; {len(pool_reports)} composition pools; {len(wrappers)} wrapper pools; {stored}; {stored_w}; wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
