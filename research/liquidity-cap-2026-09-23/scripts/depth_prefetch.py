import os, sys, urllib.request, time, concurrent.futures as cf
from datetime import datetime, timezone
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
Z = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad/depth/zips"
jobs = set()
for f in fridays():
    a, e, x = bars_for(f)
    for t in FADE:
        for bar in (e, x): jobs.add((SYM[t], datetime.fromtimestamp(bar, timezone.utc).strftime("%Y-%m-%d")))
def one(j):
    sym, day = j; p = f"{Z}/{sym}-{day}.zip"
    if os.path.exists(p): return "have"
    url = f"https://data.binance.vision/data/futures/um/daily/bookDepth/{sym}/{sym}-bookDepth-{day}.zip"
    for i in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r: data = r.read()
            open(p + ".part", "wb").write(data); os.replace(p + ".part", p); return "ok"
        except urllib.error.HTTPError as e:
            if e.code == 404: open(p + ".part", "wb").close(); os.replace(p + ".part", p); return "404"
            time.sleep(2 + 3 * i)
        except Exception: time.sleep(2 + 3 * i)
    return "fail"
with cf.ThreadPoolExecutor(8) as ex:
    res = list(ex.map(one, sorted(jobs)))
import collections; print(collections.Counter(res), len(jobs))
