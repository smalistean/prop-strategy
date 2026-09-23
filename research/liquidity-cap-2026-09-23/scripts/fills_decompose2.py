"""v2: our own fills identified by quantity on the tape; reference = last trade at least 2 s before our first fill; TSM added."""
import zipfile, io, csv, statistics as st, sys, json
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from overflow2 import walk_bp, LAD, half_spread
A = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad/depth/agg"
REC = {"PAYP": (36.16, 17.4713, 17.46), "SNDK": (0.35, 1773.91, 1773.29), "AXTI": (9.07, 69.6047, 69.52), "HOOD": (5.29, 119.11, 119.0), "MU": (0.62, 1008.7958, 1008.45), "TSM": (1.45, 432.4986, 432.12)}
CLOSE_MS = 1789934400000; T0, T1 = CLOSE_MS + 4*60000 + 30000, CLOSE_MS + 5*60000 + 30000
def tape(sym, day):
    z = zipfile.ZipFile(f"{A}/{sym}-aggTrades-{day}.zip")
    return [(int(r["transact_time"]), float(r["price"]), float(r["quantity"]), r["is_buyer_maker"] == "true") for r in csv.DictReader(io.TextIOWrapper(z.open(z.namelist()[0])))]
def find_ours(rows, qty):
    """Consecutive taker-buy aggTrades within 1.5 s whose quantities sum to qty (0.5% tolerance)."""
    w = [r for r in rows if T0 <= r[0] <= T1 and not r[3]]
    for i in range(len(w)):
        acc = 0.0; grp = []
        for j in range(i, len(w)):
            if w[j][0] - w[i][0] > 1500: break
            acc += w[j][2]; grp.append(w[j])
            if abs(acc - qty) <= 0.005 * qty + 1e-9: return grp
            if acc > qty * 1.005: break
    return None
print(f"{'leg':<5}{'bar close':>10}{'our fills':>10}{'our VWAP':>10}{'rec avg':>10}{'ref px':>9}{'ref lag s':>10}{'drift bp':>9}{'fill vs ref':>12}{'vs bar':>7}{'rec':>6}{'walk':>6}{'+half-sp':>9}")
out = {}
for t, (qty, avg, barc) in REC.items():
    rows = tape(t + "USDT", "2026-09-20"); ours = find_ours(rows, qty)
    if not ours: print(f"{t:<5} our fills not found on the tape"); continue
    t0 = ours[0][0]; ref = [r for r in rows if r[0] <= t0 - 2000][-1]
    oq = sum(r[2] for r in ours); ov = sum(r[1]*r[2] for r in ours) / oq
    wk = walk_bp(LAD[f"{t}|2026-09-18|entry"]["before"]["ask"], qty * avg); hs = half_spread(t)
    out[t] = {"n_fills": len(ours), "our_vwap": ov, "ref_px": ref[1], "ref_lag_s": (t0 - ref[0]) / 1000, "drift_bp": (ref[1]/barc-1)*1e4, "fill_vs_ref_bp": (ov/ref[1]-1)*1e4, "walk_bp": wk, "half_spread_bp": hs}
    print(f"{t:<5}{barc:>10.4f}{len(ours):>10}{ov:>10.4f}{avg:>10.4f}{ref[1]:>9.2f}{(t0-ref[0])/1000:>10.1f}{(ref[1]/barc-1)*1e4:>9.1f}{(ov/ref[1]-1)*1e4:>12.1f}{(ov/barc-1)*1e4:>7.1f}{(avg/barc-1)*1e4:>6.1f}{wk:>6.1f}{wk+hs:>9.1f}")
json.dump(out, open(f"{A}/../fills_decomposed2.json", "w"), indent=1)
