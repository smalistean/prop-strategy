import json, sys, statistics as st
S = "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad"
sys.path.insert(0, "/private/tmp/claude-501/-Users-stas-workspace-other-prop-strategy/8264e0b6-3c34-4ace-8fc7-ffdb42610a75/scratchpad")
from fade_common import *
close, qvol, fund = load_binance()
ev = {}
for f in fridays():
    ev[str(f)] = {t: o for t in FADE if (o := outcome(SYM[t], f, close, qvol, fund))}
json.dump(ev, open(f"{S}/binance_events.json", "w"), indent=0, default=str)
# check against the frozen backtest block on the site + the live ledger
site = json.load(open("/Users/stas/workspace/other/prop-strategy/site/weekend-fade/ledger.json"))
ref = {w["friday"]: w for w in site["weekends"]}
print(f"{'friday':<11}{'form':<8}{'n':>3}{'mean net':>10}{'ledger n':>10}{'ledger':>9}   names")
for f in fridays():
    trig = [t for t, o in ev[str(f)].items() if o["triggered"] and "net_bp" in o]
    n = len(trig); m = st.mean(ev[str(f)][t]["net_bp"] for t in trig) if trig else float("nan")
    r = ref.get(str(f)); form = "shifted" if is_shifted_local(f) else "plain"
    flag = "" if not r else ("" if r["names"] == n and abs(r["net_bp"] - m) < 0.15 else "  <-- DIFFERS")
    print(f"{f}  {form:<8}{n:>3}{m:>10.1f}{(r['names'] if r else '-'):>10}{(r['net_bp'] if r else '-'):>9}{flag}   {' '.join(trig)}")
