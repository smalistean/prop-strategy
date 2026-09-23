"""Download Binance bookDepth archives for every (name, weekend) decision day and exit day; keep the last snapshot before each bar close."""
import json, os, sys, io, zipfile, urllib.request, time
from datetime import datetime, timezone
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
S = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad"
Z = f"{S}/depth/zips"; os.makedirs(Z, exist_ok=True)
OUT = f"{S}/depth/ladders.json"
lad = json.load(open(OUT)) if os.path.exists(OUT) else {}
def get(sym, day):
    p = f"{Z}/{sym}-{day}.zip"
    if os.path.exists(p): return p if os.path.getsize(p) > 0 else None
    url = f"https://data.binance.vision/data/futures/um/daily/bookDepth/{sym}/{sym}-bookDepth-{day}.zip"
    for i in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r: data = r.read()
            open(p, "wb").write(data); return p
        except urllib.error.HTTPError as e:
            if e.code == 404: open(p, "wb").close(); return None
            time.sleep(2 + 3 * i)
        except Exception: time.sleep(2 + 3 * i)
    return None
def snapshot(p, cutoff_epoch):
    """Last snapshot strictly before cutoff: {ts, ask:{lvl:notional}, bid:{lvl:notional}}; and the first at/after it."""
    z = zipfile.ZipFile(p); name = z.namelist()[0]
    rows = {}
    for ln in io.TextIOWrapper(z.open(name), encoding="utf-8"):
        if ln.startswith("timestamp"): continue
        ts, pct, depth, notional = ln.rstrip("\n").split(",")
        rows.setdefault(ts, {})[float(pct)] = float(notional)
    keyed = sorted((int(datetime.strptime(ts, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()), ts) for ts in rows)
    before = [k for k in keyed if k[0] < cutoff_epoch]; after = [k for k in keyed if k[0] >= cutoff_epoch]
    def pack(k):
        d = rows[k[1]]
        return {"ts": k[1], "ask": {str(l): d[l] for l in sorted(d) if l > 0}, "bid": {str(-l): d[l] for l in sorted(d) if l < 0}}
    return (pack(before[-1]) if before else None), (pack(after[0]) if after else None)
todo = []
for f in fridays():
    a, e, x = bars_for(f)
    for t in FADE:
        todo.append((t, str(f), "entry", e)); todo.append((t, str(f), "exit", x))
n = 0
for t, f, kind, bar in todo:
    key = f"{t}|{f}|{kind}"
    if key in lad: continue
    day = datetime.fromtimestamp(bar, timezone.utc).strftime("%Y-%m-%d")
    p = get(SYM[t], day)
    if p is None: lad[key] = None
    else:
        try:
            b, a2 = snapshot(p, bar + H)
            lad[key] = {"bar_open": bar, "bar_close": bar + H, "day": day, "before": b, "after": a2}
        except Exception as ex:
            lad[key] = {"error": str(ex), "day": day}
    n += 1
    if n % 25 == 0:
        json.dump(lad, open(OUT, "w")); print(f"{n}/{len(todo)} {key}", flush=True)
json.dump(lad, open(OUT, "w")); print("done", len(lad), "cells;", sum(1 for v in lad.values() if v is None), "no file")
