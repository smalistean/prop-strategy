"""Spread proxy per name from the trade tape in the decision hour (19:00-20:00 UTC): median gap between consecutive opposite-side trades within 60 s."""
import zipfile, io, csv, os, json, statistics as st, sys
from datetime import datetime, timezone
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import FADE
A = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad/depth/agg"
def bar_ms(day): return int(datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp()*1000) + 19*3600000
res = {}
for t in FADE:
    for day in ("2026-08-30", "2026-09-13", "2026-09-20"):
        p = f"{A}/{t}USDT-aggTrades-{day}.zip"
        if not os.path.exists(p) or os.path.getsize(p) == 0: continue
        z = zipfile.ZipFile(p); lo, hi = bar_ms(day), bar_ms(day) + 3600000; w = []
        for r in csv.DictReader(io.TextIOWrapper(z.open(z.namelist()[0]))):
            ms = int(r["transact_time"])
            if lo <= ms < hi: w.append((ms, float(r["price"]), r["is_buyer_maker"] == "true"))
        gaps = [abs(b[1]-a[1])/a[1]*1e4 for a, b in zip(w, w[1:]) if a[2] != b[2] and b[0]-a[0] <= 60000]
        res.setdefault(t, {})[day] = {"spread_bp": st.median(gaps) if gaps else None, "pairs": len(gaps), "trades": len(w)}
summary = {}
print(f"{'name':<6}" + "".join(f"{d:>22}" for d in ("2026-08-30", "2026-09-13", "2026-09-20")) + f"{'median spread bp':>18}")
for t in FADE:
    vals = [v["spread_bp"] for v in res.get(t, {}).values() if v["spread_bp"] is not None]
    summary[t] = {"spread_bp": st.median(vals) if vals else None, "days": len(vals)}
    print(f"{t:<6}" + "".join((f"{res[t][d]['spread_bp']:>10.1f} ({res[t][d]['pairs']:>5}p)" if d in res.get(t, {}) and res[t][d]['spread_bp'] is not None else f"{'-':>22}") for d in ("2026-08-30", "2026-09-13", "2026-09-20")) + f"{(summary[t]['spread_bp'] or float('nan')):>18.1f}")
json.dump({"per_day": res, "summary": summary}, open(f"{A}/../spreads.json", "w"), indent=1)
