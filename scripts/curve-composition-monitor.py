#!/usr/bin/env python3
"""Curve stablecoin-pool composition monitor.

Reads composition and realised price impact from the pools' own on-chain state, appends a history
row per pool, and writes CURVE_COMPOSITION_MONITOR.md with the current alert level.

Design and thresholds are frozen in CURVE_MONITOR_PREREGISTRATION.md; see STABLECOIN_DEPEG_DOSSIER.md
for what to do at each level. Read-only: it makes eth_call requests and touches no keys.

Usage: python3 scripts/curve-composition-monitor.py [--rpc URL]
"""
import json, os, sys, csv, time, datetime, urllib.request

RPCS = [os.environ["ETH_RPC"]] if os.environ.get("ETH_RPC") else [
    "https://ethereum-rpc.publicnode.com",
    "https://eth.drpc.org",
    "https://1rpc.io/eth",
]
RPC = RPCS[0]
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HIST = os.path.join(REPO, "data", "curve-composition-history.csv")
OUT = os.path.join(REPO, "CURVE_COMPOSITION_MONITOR.md")

POOLS = [
    # 3pool covers USDT/USDC/DAI - where essentially all of our own exposure sits.
    ("3pool",     "0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7"),
    # Deepest USDe pool against another $1 asset ($34M). Pools pairing against sUSDe are
    # deliberately excluded: sUSDe accrues yield, so its share drifts for reasons unrelated to a peg.
    ("FRAX/USDe", "0x5dc1BF6f1e983C0b21EfB003c105133736fA0743"),
]
SEL = {"coins": "0xc6610657", "balances": "0x4903b0d1", "A": "0xf446c1d0",
       "decimals": "0x313ce567", "symbol": "0x95d89b41",
       "get_dy_i128": "0x5e0d443f", "get_dy_u256": "0x556d6e9f"}
# The impact probe must scale with pool depth: a fixed size larger than the pool reports a
# catastrophic "impact" for a perfectly healthy pool. Probe near-marginally instead.
PROBE_FRACTION = 0.001        # 0.1% of the source coin's balance
PROBE_FLOOR = 1_000.0         # but never a dust-sized probe
MIN_TVL_USD = 10_000_000      # below this a pool is too thin to carry a signal
# thresholds: excess = share - 1/N  (see pre-registration)
EXC_L1, EXC_L2, EXC_L3 = 0.32, 0.42, 0.52
IMP_L1, IMP_L2, IMP_L3 = 30.0, 100.0, 300.0   # bp of adverse deviation


_HDRS = {"Content-Type": "application/json",
         "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) curve-composition-monitor"}


MIN_CALL_INTERVAL = 0.35   # public endpoints serve single calls but rate-limit bursts
_next_call_at = 0.0


def call(to, data):
    """eth_call, throttled, with RPC failover.

    Two lessons are baked in: public endpoints reject a default urllib user agent (403), and they
    rate-limit bursts even when single calls succeed - so requests are spaced rather than looped.
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
        for rpc in ([RPC] + [r for r in RPCS if r != RPC]):
            try:
                req = urllib.request.Request(rpc, payload, _HDRS)
                with urllib.request.urlopen(req, timeout=30) as r:
                    out = json.load(r)
                if "result" in out:
                    RPC = rpc          # stick with the endpoint that works
                    return out["result"]
                err = out.get("error") or {}
                # A revert is a definitive answer from the chain (e.g. coins(i) past the last
                # coin), not a transport failure - do not retry it against other endpoints.
                if "execution reverted" in str(err.get("message", "")):
                    RPC = rpc
                    return None
                last = err
            except Exception as e:  # noqa: BLE001 - transport failure: try the next RPC
                last = e
        time.sleep(2.0 * (attempt + 1))   # rate-limited: back off before retrying
    raise RuntimeError(f"all RPCs failed after retries: {last}")


def word(i):
    return format(i, "064x")


def as_int(hexstr):
    return int(hexstr, 16) if hexstr and hexstr != "0x" else None


def as_str(hexstr):
    if not hexstr or len(hexstr) <= 2:
        return "?"
    b = bytes.fromhex(hexstr[2:])
    if len(b) >= 64:                      # dynamic string
        n = int.from_bytes(b[32:64], "big")
        return b[64:64 + n].decode("utf8", "ignore")
    return b.rstrip(b"\x00").decode("utf8", "ignore")


def read_pool(addr):
    coins, decs, syms, bals = [], [], [], []
    for i in range(8):
        r = call(addr, SEL["coins"] + word(i))
        if not r or r == "0x":
            break
        c = "0x" + r[-40:]
        if int(c, 16) == 0:
            break
        coins.append(c)
        decs.append(as_int(call(c, SEL["decimals"])) or 18)
        syms.append(as_str(call(c, SEL["symbol"])))
        bals.append((as_int(call(addr, SEL["balances"] + word(i))) or 0) / 10 ** decs[-1])
    amp = as_int(call(addr, SEL["A"]))
    return coins, syms, decs, bals, amp


def price_impact_bp(addr, i, j, dec_in, dec_out, bal_in):
    """Near-marginal price deviation in bp, using the pool's own get_dy.

    Probe size is a fraction of the pool's own balance so the reading is comparable across pools
    of very different depth (a fixed probe bigger than the pool reports a fake collapse).
    """
    probe = max(PROBE_FLOOR, bal_in * PROBE_FRACTION)
    dx = int(probe * 10 ** dec_in)
    for sel_name in ("get_dy_i128", "get_dy_u256"):
        r = call(addr, SEL[sel_name] + word(i) + word(j) + word(dx))
        v = as_int(r)
        if v:
            dy = v / 10 ** dec_out
            return (dy / probe - 1) * 10000
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


def load_history():
    if not os.path.exists(HIST):
        return []
    with open(HIST) as f:
        return list(csv.DictReader(f))


def main():
    now = datetime.datetime.now(datetime.UTC)
    hist = load_history()
    rows, report = [], []
    worst = 0

    for name, addr in POOLS:
        coins, syms, decs, bals, amp = read_pool(addr)
        total = sum(bals)
        if total <= 0:
            report.append((name, addr, amp, [], "pool read failed or empty", 0, 0.0, True))
            continue
        n = len(bals)
        thin = total < MIN_TVL_USD
        lines, pool_worst = [], 0
        for i, s in enumerate(syms):
            share = bals[i] / total
            excess = share - 1.0 / n
            j = (i + 1) % n
            imp = price_impact_bp(addr, i, j, decs[i], decs[j], bals[i])
            # 7-day delta from history
            prev = None
            cutoff = now - datetime.timedelta(days=7)
            for h in hist:
                if h["pool"] == name and h["coin"] == s:
                    try:
                        t = datetime.datetime.fromisoformat(h["ts"])
                    except ValueError:
                        continue
                    if t <= cutoff and (prev is None or t > prev[0]):
                        prev = (t, float(h["share"]))
            d7 = (share - prev[1]) * 100 if prev else None
            lv = level(excess, imp)
            if d7 is not None and d7 >= 10.0:
                lv = max(lv, 1)
            pool_worst = max(pool_worst, lv)
            lines.append((s, bals[i], share, excess, imp, d7, lv))
            rows.append({"ts": now.isoformat(), "pool": name, "coin": s,
                         "balance": f"{bals[i]:.2f}", "share": f"{share:.6f}",
                         "impact_bp": "" if imp is None else f"{imp:.2f}"})
        if thin:
            pool_worst = 0          # informational only; too shallow to mean anything
        worst = max(worst, pool_worst)
        report.append((name, addr, amp, lines, None, pool_worst, total, thin))

    os.makedirs(os.path.dirname(HIST), exist_ok=True)
    new = not os.path.exists(HIST)
    with open(HIST, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["ts", "pool", "coin", "balance", "share", "impact_bp"])
        if new:
            w.writeheader()
        w.writerows(rows)

    verdict = {0: "NORMAL - no action", 1: "LEVEL 1 WATCH - re-read the dossier, journal it, no position change",
               2: "LEVEL 2 DE-RISK - stop opening in that asset, move own capital off-venue",
               3: "LEVEL 3 ACT - flatten own-capital positions in that asset and withdraw"}[worst]

    md = ["# Curve composition monitor", "",
          "Snapshot read from each pool's own on-chain state. Thresholds frozen in",
          "`CURVE_MONITOR_PREREGISTRATION.md`; actions in `STABLECOIN_DEPEG_DOSSIER.md`.",
          "Regenerate with `bash scripts/curve-monitor.sh`.", "",
          f"**As of:** {now:%Y-%m-%dT%H:%M:%SZ}  |  history rows: {len(hist) + len(rows)}", "",
          f"## Overall: {verdict}", ""]
    for name, addr, amp, lines, err, lv, tvl, thin in report:
        md += [f"### {name}  ({addr})", ""]
        if err:
            md += [f"_{err}_", ""]
            continue
        note = (f"  ·  _below ${MIN_TVL_USD/1e6:,.0f}M TVL — informational only, "
                "cannot raise the overall level_") if thin else ""
        md += [f"A = {amp if amp is not None else 'n/a'}  ·  TVL ~${tvl:,.0f}  ·  pool level: **{lv}**{note}", "",
               "| Coin | Balance | Share | Excess over balanced | marginal impact | 7d share change | Level |",
               "|---|---:|---:|---:|---:|---:|---:|"]
        for s, bal, share, excess, imp, d7, l in lines:
            md.append(f"| {s} | {bal:,.0f} | {share:.1%} | {excess:+.1%} | "
                      f"{'n/a' if imp is None else f'{imp:+.1f} bp'} | "
                      f"{'—' if d7 is None else f'{d7:+.1f} pp'} | {l} |")
        md.append("")
    md += ["---", "",
           "**Reading it:** a coin that is *over*-weighted is the one being sold into the pool, and",
           "its marginal impact is negative — it is the cheap side. A positive impact means that coin",
           "trades at a premium. An alert names a *pool* dislocation, not necessarily a problem with",
           "the coin we hold: check which side is over-weighted before acting.", "",
           "Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical",
           "beyond it, so a share drift is visible days before any price chart moves. A persistent",
           "level (3pool has sat near 53% USDT for years) is not a warning; **change is.**"]
    with open(OUT, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"wrote {OUT} (level {worst}) and appended {len(rows)} history rows")
    return 0


if __name__ == "__main__":
    sys.exit(main())
