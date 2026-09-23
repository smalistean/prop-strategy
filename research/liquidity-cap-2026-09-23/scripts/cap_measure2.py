"""v2 of cap_measure.py after review: corrupt 2026-09-04 ladders excluded, flat-band fix, tick-floored spread, extra checks."""
import json, sys, statistics as st, collections, math
from datetime import datetime, timezone
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
from overflow2 import walk_bp, LAD, ev, ladder, half_spread, allocate, run, RECORDED, EXCLUDED_WEEKENDS
S = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad"
P = [0.05, 0.10, 0.15, 0.20, 0.30, 0.50]; FIXED = [1000, 3750, 12500, 20000]; TRANCHE_BP = 48.0; BIND = 12500.0
def pct(xs, q):
    xs = sorted(xs); k = (len(xs) - 1) * q; lo = int(k); hi = min(lo + 1, len(xs) - 1); return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)
cells = []; ages = []
for f in fridays():
    for t in FADE:
        o = ev[str(f)].get(t); ask = ladder(t, str(f), "entry"); bid = ladder(t, str(f), "exit")
        if not o or ask is None or not o.get("entry_qvol"): continue
        V = o["entry_qvol"]; c = LAD[f"{t}|{f}|entry"]
        for kind in ("entry", "exit"):
            cc = LAD.get(f"{t}|{f}|{kind}")
            if cc and cc.get("before"): ages.append(cc["bar_close"] - int(datetime.strptime(cc["before"]["ts"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()))
        cells.append({"t": t, "f": str(f), "V": V, "trig": o["triggered"], "ask": ask, "bid": bid, "ts": c["before"]["ts"], "ask02": ask.get("0.2", 0.0),
                      "p": {p: walk_bp(ask, p * V) for p in P}, "fixed": {q: walk_bp(ask, q) for q in FIXED},
                      "xfixed": {q: walk_bp(bid, q) for q in FIXED} if bid else {}, "xp": {p: walk_bp(bid, p * V) for p in P} if bid else {}})
print(f"cells with a valid entry ladder: {len(cells)} (triggered {sum(1 for c in cells if c['trig'])}); names {len(set(c['t'] for c in cells))}; weekends {len(set(c['f'] for c in cells))}; excluded weekends {sorted(EXCLUDED_WEEKENDS)}; cells without a valid exit ladder: {sum(1 for c in cells if not c['bid'])}")
print(f"snapshot age before the bar close: min {min(ages)} s, median {st.median(ages):.0f} s, max {max(ages)} s (n={len(ages)})")
def show(rows, key, label, sub):
    print(f"\n== {label} ==  n / median / p75 / p90 (bp) / beyond 5% level")
    for k in key:
        vals = [r[sub][k] for r in rows if sub in r and r[sub]]; ok = [v for v in vals if v is not None]
        if not vals: continue
        lab = f"{k*100:.0f}% of bar" if isinstance(k, float) else f"${k:,}"
        print(f"  {lab:<12}{len(vals):>5}{pct(ok,.5):>8.1f}{pct(ok,.75):>7.1f}{pct(ok,.9):>7.1f}{len(vals)-len(ok):>8}")
trig = [c for c in cells if c["trig"]]
show(cells, P, "ENTRY, all cells, share of bar", "p"); show(cells, FIXED, "ENTRY, all cells, fixed size", "fixed")
show(trig, P, "ENTRY, TRIGGERED cells, share of bar", "p"); show(trig, FIXED, "ENTRY, TRIGGERED cells, fixed size", "fixed")
show(cells, P, "EXIT, all cells, share of the decision bar", "xp"); show(cells, FIXED, "EXIT, all cells, fixed size", "xfixed")
# fixed-size > 48 bp, walk only and walk + half-spread, by name
print("\n== cells costing > 48 bp at a fixed size (walk + half-spread; 'beyond' counted) ==")
for Q in (3750, 12500, 20000):
    bad = collections.Counter(c["t"] for c in cells if c["fixed"][Q] is None or c["fixed"][Q] + half_spread(c["t"]) > TRANCHE_BP)
    print(f"  ${Q:,}: {sum(bad.values())}/{len(cells)} cells: {dict(bad.most_common())}")
# declared rule
def tranche(c, a, b):
    sa, sb = c["p"][a], c["p"][b]
    return None if sa is None or sb is None else (sb * b - sa * a) / (b - a)
for label, pool in (("all weekends", cells), ("since 2026-06-01", [c for c in cells if c["f"] >= "2026-06-01"])):
    bind = [c for c in pool if 0.10 * c["V"] < BIND]
    print(f"\n== declared rule, {label}: binding cells (10% of bar < ${BIND:,.0f}) {len(bind)} of {len(pool)} ==")
    for a, b in ((0.0, 0.10), (0.10, 0.15), (0.15, 0.20), (0.20, 0.30)):
        tr = [tranche(c, a, b) if a > 0 else c["p"][b] for c in bind]; ok = [x for x in tr if x is not None]
        passing = sum(1 for x in ok if x <= TRANCHE_BP); passing5 = sum(1 for x in ok if x + 5 <= TRANCHE_BP)
        inside02 = sum(1 for c in bind if b * c["V"] <= c["ask02"])
        print(f"  {a*100:>2.0f}->{b*100:<3.0f}%  <=48 bp on {passing}/{len(tr)} = {passing/len(tr)*100:.0f}% (with +5 bp error bar {passing5/len(tr)*100:.0f}%; beyond ladder {len(tr)-len(ok)});  median {pct(ok,.5):.1f} p75 {pct(ok,.75):.1f} p90 {pct(ok,.9):.1f};  upper end of tranche inside the 0.2% level on {inside02}/{len(bind)} cells")
# bar volume vs resting depth
def rank(xs):
    o = sorted(range(len(xs)), key=lambda i: xs[i]); r = [0] * len(xs)
    for k, i in enumerate(o): r[i] = k
    return r
V = [c["V"] for c in cells]; D = [c["ask02"] for c in cells]; rv, rd = rank(V), rank(D)
mv, md = st.mean(rv), st.mean(rd); rho = sum((a-mv)*(b-md) for a, b in zip(rv, rd)) / math.sqrt(sum((a-mv)**2 for a in rv) * sum((b-md)**2 for b in rd))
ratio = [c["ask02"] / c["V"] for c in cells if c["V"] > 0]
print(f"\n== decision-bar volume vs ask notional within 0.2%: Spearman rho {rho:.2f} over {len(cells)} cells; ask-0.2 / bar ratio median {pct(ratio,.5):.2f}, p10 {pct(ratio,.1):.2f}, p90 {pct(ratio,.9):.2f} ==")
# criterion 5: cost of the dollars that overflow actually adds, per receiving leg
for E in (100_000, 200_000):
    extra = []
    for f in RECORDED:
        names = [t for t, o in ev[f].items() if o["triggered"] and "net_bp" in o]; Vd = {t: ev[f][t]["entry_qvol"] or 0 for t in names}
        base = allocate(names, Vd, E, 0.10, False); ov = allocate(names, Vd, E, 0.10, True)
        for t in names:
            q0, q1 = base[t], ov[t]
            if q1 > q0 + 1e-6:
                s0, s1 = (walk_bp(ladder(t, f, "entry"), q0) if q0 > 0 else 0.0), walk_bp(ladder(t, f, "entry"), q1)
                if ladder(t, f, "entry") is None: continue
                if s0 is None or s1 is None: extra.append((t, f, None)); continue
                extra.append((t, f, (s1 * q1 - (s0 or 0) * q0) / (q1 - q0)))
    ok = [x for _, _, x in extra if x is not None]
    print(f"\n== criterion 5 at ${E:,}: dollars added by overflow at the 10% cap, marginal cost per receiving leg: {len(extra)} legs, median {pct(ok,.5):.1f} p75 {pct(ok,.75):.1f} max {max(ok):.1f} bp; > 48 bp: {sum(1 for x in ok if x > 48)} legs; beyond ladder: {len(extra)-len(ok)} ==")
# concentration: current vs candidate at $100k
E = 100_000
for label, p, ovf in (("current 10%", 0.10, False), ("10% + overflow", 0.10, True), ("20% + overflow", 0.20, True)):
    tot, dep, alloc, rows, sl, ms = run(E, p, ovf, True)
    legs20 = 0; over10 = 0.0; deployed = 0.0
    for f in RECORDED:
        names = [t for t, o in ev[f].items() if o["triggered"] and "net_bp" in o]; Vd = {t: ev[f][t]["entry_qvol"] or 0 for t in names}
        fill = allocate(names, Vd, E, p, ovf); legs20 += sum(1 for q in fill.values() if q >= 19_999); deployed += sum(fill.values()); over10 += sum(max(0.0, q - 0.10 * Vd[t]) for t, q in fill.items())
    pnl = [r[3] for r in rows]
    print(f"  {label:<16} total ${tot:>7,.0f}  worst weekend ${min(pnl):>7,.0f}  best ${max(pnl):>7,.0f}  stdev ${st.pstdev(pnl):>6,.0f}  $20k legs {legs20:>2}  dollars above 10% of bar {over10/deployed*100:>4.0f}%  legs w/o ladder {ms}")
json.dump([{k: v for k, v in c.items() if k not in ("ask", "bid")} for c in cells], open(f"{S}/depth/cells2.json", "w"), default=str)
