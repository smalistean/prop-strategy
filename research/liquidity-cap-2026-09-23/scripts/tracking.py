"""Does a venue's perp carry Binance's weekend history? Per venue x name, all weekends both have bars: entry-deviation gap,
trigger agreement, and (over Binance-triggered events) the correlation and mean gap of the entry->exit price return."""
import json, sys, os, csv, math, statistics as st, collections
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
S = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad"
ev = json.load(open(f"{S}/binance_events.json"))
VEN = [v for v in "bybit okx mexc bitget hyperliquid gate kraken".split() if os.path.exists(f"{S}/venues/{v}_1h.csv")]
ONLY = sys.argv[1].split(",") if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else None
SINCE = next((a.split("=")[1] for a in sys.argv if a.startswith("--since=")), None)
if ONLY: VEN = [v for v in VEN if v in ONLY]
def load(v):
    cl, qv = collections.defaultdict(dict), collections.defaultdict(dict)
    with open(f"{S}/venues/{v}_1h.csv") as fh:
        for r in csv.DictReader(fh):
            t = int(r["open_utc_ms"]) // 1000; cl[r["ticker"]][t] = float(r["close"]); qv[r["ticker"]][t] = float(r["quote_volume_usd"] or 0)
    return cl, qv
def corr(x, y):
    if len(x) < 3: return float("nan")
    mx, my = st.mean(x), st.mean(y); sx = math.sqrt(sum((a-mx)**2 for a in x)); sy = math.sqrt(sum((b-my)**2 for b in y))
    return sum((a-mx)*(b-my) for a, b in zip(x, y)) / (sx*sy) if sx and sy else float("nan")
def pct(xs, q):
    xs = sorted(xs); k = (len(xs)-1)*q; lo = int(k); hi = min(lo+1, len(xs)-1); return xs[lo] + (xs[hi]-xs[lo])*(k-lo)
summary = {}
for v in VEN:
    cl, qv = load(v)
    rows = []   # per (name, weekend) with all three bars on both sides
    for f in fridays():
        if SINCE and str(f) < SINCE: continue
        a, e, x = bars_for(f)
        for t in FADE:
            o = ev[str(f)].get(t); c = cl.get(t, {})
            if not o or "net_bp" not in o or a not in c or e not in c or x not in c: continue
            dev_v = (c[e]/c[a]-1)*1e4; ret_v = (c[x]/c[e]-1)*1e4
            rows.append({"t": t, "f": str(f), "dev_b": o["dev_bp"], "dev_v": dev_v, "ret_b": o["price_bp"], "ret_v": ret_v,
                         "trig_b": o["triggered"], "trig_v": dev_v <= TRIGGER_BP, "qv_v": qv[t].get(e, 0.0), "qv_b": o["entry_qvol"] or 0.0,
                         "zero_bar": qv[t].get(e, 0.0) == 0})
    if not rows: print(f"{v}: no comparable rows"); continue
    gap_dev = [r["dev_v"]-r["dev_b"] for r in rows]; gap_ret = [r["ret_v"]-r["ret_b"] for r in rows]
    trig_b = [r for r in rows if r["trig_b"]]; agree = sum(1 for r in trig_b if r["trig_v"])
    fp = sum(1 for r in rows if r["trig_v"] and not r["trig_b"])
    ev_rows = trig_b
    share = st.median(r["qv_v"]/(r["qv_v"]+r["qv_b"]) for r in rows if r["qv_v"]+r["qv_b"] > 0)
    summary[v] = {"rows": len(rows), "names": len(set(r["t"] for r in rows)), "weekends": len(set(r["f"] for r in rows)),
                  "entry_gap_median_abs_bp": st.median(abs(g) for g in gap_dev), "entry_gap_p90_abs_bp": pct([abs(g) for g in gap_dev], .9),
                  "entry_gap_mean_bp": st.mean(gap_dev), "trigger_agree": f"{agree}/{len(trig_b)}", "venue_only_triggers": fp,
                  "events": len(ev_rows), "ret_corr": corr([r["ret_b"] for r in ev_rows], [r["ret_v"] for r in ev_rows]),
                  "ret_gap_mean_bp": st.mean(r["ret_v"]-r["ret_b"] for r in ev_rows) if ev_rows else float("nan"),
                  "ret_gap_median_abs_bp": st.median(abs(r["ret_v"]-r["ret_b"]) for r in ev_rows) if ev_rows else float("nan"),
                  "zero_volume_entry_bars": sum(1 for r in rows if r["zero_bar"]), "median_share_of_binance_plus_venue": share}
    # funding over the hold, if the venue published it: sum of settlements in (entry close, exit close], long pays positive
    fp_ = f"{S}/venues/{v}_funding.csv"
    if os.path.exists(fp_):
        fr = collections.defaultdict(list)
        with open(fp_) as fh:
            for r in csv.DictReader(fh):
                try: fr[r["ticker"]].append((int(r["funding_time_ms"]) // 1000, float(r["rate"])))
                except Exception: pass
        fb, fv, nf = [], [], 0
        for r in ev_rows:
            a, e, x = bars_for(date.fromisoformat(r["f"]))
            rates = [q for (tt, q) in fr.get(r["t"], []) if e + H < tt <= x + H]
            if rates or fr.get(r["t"]):
                fv.append(-sum(rates) * 1e4); fb.append(ev[r["f"]][r["t"]]["funding_bp"]); nf += 1
        summary[v]["funding_events"] = nf
        summary[v]["funding_mean_bp_venue"] = st.mean(fv) if fv else float("nan")
        summary[v]["funding_mean_bp_binance_same_events"] = st.mean(fb) if fb else float("nan")
    json.dump(rows, open(f"{S}/venues/{v}_tracking_rows.json", "w"))
    # per-name detail for the thin names
    byn = collections.defaultdict(list)
    for r in rows: byn[r["t"]].append(r)
    summary[v]["per_name"] = {t: {"n": len(rs), "entry_gap_med_abs": st.median(abs(r["dev_v"]-r["dev_b"]) for r in rs),
                                  "events": sum(1 for r in rs if r["trig_b"]), "agree": sum(1 for r in rs if r["trig_b"] and r["trig_v"]),
                                  "ret_gap_med_abs": st.median([abs(r["ret_v"]-r["ret_b"]) for r in rs if r["trig_b"]] or [float("nan")]),
                                  "share": st.median(r["qv_v"]/(r["qv_v"]+r["qv_b"]) for r in rs if r["qv_v"]+r["qv_b"] > 0)} for t, rs in byn.items()}
json.dump(summary, open(f"{S}/venues/tracking_summary{'_since' + SINCE if SINCE else ''}.json", "w"), indent=1, default=str)
print(f"window: {'all weekends 2026-01-30..2026-09-18' if not SINCE else 'weekends since ' + SINCE}")
print(f"{'venue':<12}{'rows':>6}{'names':>6}{'wknds':>6}{'|entry gap| med':>16}{'p90':>7}{'mean':>7}{'trig agree':>12}{'venue-only':>11}{'events':>7}{'ret corr':>9}{'ret gap mean':>13}{'|ret gap| med':>14}{'0-vol bars':>11}{'med share':>10}")
for v, s in summary.items():
    print(f"{v:<12}{s['rows']:>6}{s['names']:>6}{s['weekends']:>6}{s['entry_gap_median_abs_bp']:>16.1f}{s['entry_gap_p90_abs_bp']:>7.1f}{s['entry_gap_mean_bp']:>7.1f}{s['trigger_agree']:>12}{s['venue_only_triggers']:>11}{s['events']:>7}{s['ret_corr']:>9.3f}{s['ret_gap_mean_bp']:>13.1f}{s['ret_gap_median_abs_bp']:>14.1f}{s['zero_volume_entry_bars']:>11}{s['median_share_of_binance_plus_venue']*100:>9.0f}%")
print("\n== funding over the hold (bp, long pays positive; mean over Binance-triggered events the venue has a rate for) ==")
for v, s_ in summary.items():
    if "funding_events" in s_: print(f"  {v:<12} events {s_['funding_events']:>3}  venue {s_['funding_mean_bp_venue']:>+6.1f}   Binance on the same events {s_['funding_mean_bp_binance_same_events']:>+6.1f}")
print("\n== thin names: |entry gap| median bp (n) and trigger agreement, by venue ==")
thin = "PAYP EWJ JPM LLY QCOM NOK AXTI AAOI".split()
print(f"  {'name':<6}" + "".join(f"{v:>22}" for v in summary))
for t in thin:
    print(f"  {t:<6}" + "".join((f"{s['per_name'][t]['entry_gap_med_abs']:>8.1f} ({s['per_name'][t]['n']:>2}) {s['per_name'][t]['agree']}/{s['per_name'][t]['events']}".rjust(22) if t in s["per_name"] else f"{'-':>22}") for s in summary.values()))

print("\n== does volume share predict tracking? (venue, name) pairs bucketed by the venue's median share of Binance+venue decision-bar volume ==")
pairs = [(s_["per_name"][t]["share"], s_["per_name"][t]["entry_gap_med_abs"], v, t) for v, s_ in summary.items() for t in s_["per_name"] if s_["per_name"][t]["n"] >= 8]
for lo, hi in ((0, .02), (.02, .05), (.05, .10), (.10, .20), (.20, 1.01)):
    b = [g for sh, g, v, t in pairs if lo <= sh < hi]
    if b: print(f"  share {lo*100:>3.0f}-{hi*100:<3.0f}%  pairs {len(b):>3}  median |entry gap| {st.median(b):>5.1f} bp   p75 {pct(b,.75):>5.1f}")
