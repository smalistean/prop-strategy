"""Sizing variants on the recorded weekends: cap level x overflow on/off x book size. Slippage from ladders.json when present."""
import json, sys, os, math, collections
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
S = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad"
ev = json.load(open(f"{S}/binance_events.json"))
LAD = json.load(open(f"{S}/depth/ladders.json")) if os.path.exists(f"{S}/depth/ladders.json") else {}
LEVELS = [0.2, 1.0, 2.0, 3.0, 4.0, 5.0]
def walk_bp(ladder, Q):
    """Average offset (bp) of a market order of notional Q against a cumulative-notional ladder {pct: notional}; None if beyond 5%."""
    if not ladder or Q <= 0: return None
    pts = [(0.0, 0.0)] + [(float(p), ladder[str(p)]) for p in LEVELS if str(p) in ladder]
    area, q0, p0 = 0.0, 0.0, 0.0
    for p1, q1 in pts[1:]:
        if q1 <= q0: p0 = p1; continue     # a band adding no notional still advances the offset
        if Q <= q1:
            pq = p0 + (p1 - p0) * (Q - q0) / (q1 - q0)
            area += (p0 + pq) / 2 * (Q - q0); return area / Q * 100
        area += (p0 + p1) / 2 * (q1 - q0); q0, p0 = q1, p1
    return None
SPREAD = json.load(open(f"{S}/depth/spreads.json"))["summary"] if os.path.exists(f"{S}/depth/spreads.json") else {}
TICK = json.load(open(f"{S}/depth/ticks.json")) if os.path.exists(f"{S}/depth/ticks.json") else {}
REFPX = {}   # median entry close per name, for the tick in bp
for _f, _names in ev.items():
    for _t, _o in _names.items():
        if _o.get("entry_close"): REFPX.setdefault(_t, []).append(_o["entry_close"])
import statistics as _st
def tick_bp(t): return TICK.get(t, 0.01) / _st.median(REFPX[t]) * 1e4 if REFPX.get(t) else 0.0
def half_spread(t):
    """Half of max(tape-measured spread, one tick). Unmeasured names get one tick."""
    v = SPREAD.get(t, {}).get("spread_bp")
    return max(v if v is not None else 0.0, tick_bp(t)) / 2
EXCLUDED_WEEKENDS = {"2026-09-04"}   # archive days 2026-09-07/08: ask ladder frozen at one value on all six levels all day (review finding, reproduced)
def valid_ladder(side):
    """A side is usable only if its six cumulative levels are non-decreasing and not all identical."""
    if not side: return False
    v = [side[str(p)] for p in LEVELS if str(p) in side]
    return len(v) >= 2 and all(a <= b for a, b in zip(v, v[1:])) and len(set(v)) > 1
def ladder(t, f, kind):
    if f in EXCLUDED_WEEKENDS: return None
    c = LAD.get(f"{t}|{f}|{kind}")
    if not c or not c.get("before"): return None
    side = c["before"]["ask" if kind == "entry" else "bid"]
    return side if valid_ladder(side) else None
def slip(t, f, kind, Q, with_spread=True):
    """Book walk from the ladder plus half the tape-measured spread (the ladder's levels do not carry the spread)."""
    side = ladder(t, f, kind)
    if side is None: return None
    w = walk_bp(side, Q)
    return None if w is None else w + (half_spread(t) if with_spread else 0.0)
RECORDED = [str(x) for x in fridays() if any(o["triggered"] and "net_bp" in o for o in ev[str(x)].values())]
def allocate(names, V, E, p, overflow):
    n = len(names); target = min(0.75 * E / n, 0.20 * E); budget = n * target
    mx = {t: min(p * V[t], 0.20 * E) for t in names}
    if not overflow: return {t: min(target, mx[t]) for t in names}
    fill = {t: 0.0 for t in names}; left = budget; open_ = set(names)
    while left > 1e-9 and open_:
        share = left / len(open_); moved = 0.0
        for t in list(open_):
            take = min(share, mx[t] - fill[t]); fill[t] += take; moved += take
            if mx[t] - fill[t] < 1e-9: open_.discard(t)
        left -= moved
        if moved < 1e-9: break
    return fill
def run(E, p, overflow, use_slip):
    tot = dep = alloc = 0.0; rows = []; slipped = missing = 0
    for f in RECORDED:
        names = [t for t, o in ev[f].items() if o["triggered"] and "net_bp" in o]
        V = {t: ev[f][t]["entry_qvol"] or 0.0 for t in names}
        fill = allocate(names, V, E, p, overflow)
        n = len(names); alloc += n * min(0.75 * E / n, 0.20 * E)
        pnl = 0.0
        for t in names:
            q = fill[t]; r = ev[f][t]["net_bp"]
            if use_slip and q > 0:
                se, sx = slip(t, f, "entry", q), slip(t, f, "exit", q)
                if se is None or sx is None: missing += 1
                else: r -= (se + sx); slipped += 1
            pnl += q * r / 1e4
        tot += pnl; dep += sum(fill.values()); rows.append((f, n, sum(fill.values()), pnl))
    return tot, dep, alloc, rows, slipped, missing
if __name__ == "__main__":
    use_slip = "--slip" in sys.argv
    print(f"recorded weekends: {len(RECORDED)} (24-name universe; ladders of {sorted(EXCLUDED_WEEKENDS)} excluded as corrupt), slippage {'ON' if use_slip else 'OFF'}")
    for E in (50_000, 100_000, 200_000):
        print(f"\n== ${E:,.0f} book ==   {'cap':>5} {'overflow':>9} {'deployed':>11} {'of alloc':>9} {'total $':>10} {'per wknd':>9} {'legs w/ ladder':>15}")
        for p in (0.05, 0.10, 0.15, 0.20, 0.30):
            for ov in (False, True):
                tot, dep, alloc, rows, sl, ms = run(E, p, ov, use_slip)
                print(f"{'':30}{p*100:>4.0f}% {('yes' if ov else 'no'):>9} {dep:>11,.0f} {dep/alloc*100:>8.0f}% {tot:>10,.0f} {tot/len(rows):>9,.0f} {sl:>7}/{sl+ms:<7}")
    E = 100_000
    print(f"\n== per weekend, $100k, cap 10%: current vs overflow ==")
    a = run(E, 0.10, False, use_slip)[3]; b = run(E, 0.10, True, use_slip)[3]
    for (f, n, d1, p1), (_, _, d2, p2) in zip(a, b):
        print(f"  {f} {n:>2} names   current ${d1:>8,.0f} -> {p1:>+8,.0f}   overflow ${d2:>8,.0f} -> {p2:>+8,.0f}   moved ${d2-d1:>7,.0f}")
