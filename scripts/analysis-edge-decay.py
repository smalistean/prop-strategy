#!/usr/bin/env python3
"""Weekend-fade edge-decay RECORD (EDGE_DECAY_MONITOR_PREREGISTRATION.md: withdrawn alarm, record-only accumulator).

The 2026-09-21 declaration of a calibrated alarm on this statistic was WITHDRAWN on 2026-09-22 after adversarial
review. At 17 baseline weekends no boundary holds a 10% family-wise false-alarm rate once holiday weekends are
included and the baseline's own parameter uncertainty is propagated. The withdrawn version is kept verbatim as
scripts/analysis-edge-decay-v1-withdrawn.py so every number in the withdrawn declaration reproduces.

This script RECORDS the statistic and nothing else. It has no boundary, no alarm, computes no statistic across
weekends, and reads nothing at the decision bar. Its purpose is to accumulate a baseline BLIND -- with no boundary
in existence -- so that a future declaration (permitted at >= REOPEN_N post-declaration weekends) can be calibrated
on data collected before anyone knew where a boundary would fall.

  record [YYYY-MM-DD]   compute every weekend through that Friday not yet in the ledger; append one row per weekend.
                        REFUSES if the newest stored bar precedes the last Friday's entry bar (run the kline refresh
                        first). Idempotent: rows are keyed by Friday and never rewritten.
  show                  print the recorded series, one line per weekend. Nothing is aggregated.
  selfcheck             assert the 17 pre-declaration weekends (2026-05-29 .. 2026-09-18) reproduce the frozen values.

Statistic, per weekend, over the frozen 24-name live universe, HOLIDAY WEEKENDS INCLUDED (the live rule trades them):

  L = ln(  mean_s |weekend drift_s| / sqrt(weekend hours)   /   mean_s overnight_s  )

  weekend drift = anchor (last US session close) -> entry bar (19:00 UTC the evening before the next session): the
                  live shifted form, shared with analysis-sequential-test.py. Divided by sqrt(hours) so a three-day
                  holiday weekend is comparable to a two-day one instead of being excluded.
  overnight_s   = mean over the weekday nights of the PRECEDING week of |15:00 NY close -> endpoint| / sqrt(hours).
                  Two endpoints are recorded for every weekend and NO choice between them is made here:
                    e08  the bar opening 08:00 NY; its close (09:00 NY) is the last print before the 09:30 open.
                         A closed window: the same mechanism the weekend measures, over a shorter closure.
                    e12  the bar opening 12:00 NY; its close is 13:00 NY, 3.5 h into the session. Lower variance,
                         partly because the overnight gap has been half-reverted by then. Not a closed window.
                  The future declaration picks one and discloses why, with the accumulated series as evidence.
  ln            so that multiplicative outliers (a 3-night holiday week in the denominator) are additive and symmetric.
"""
import json, math, os, statistics as st, subprocess, sys, tempfile
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE = sys.argv[1] if len(sys.argv) > 1 else "show"
NY = ZoneInfo("America/New_York")
H = 3600

# ---- frozen constants (2026-09-22). Changing UNIVERSE, MIN_NAMES, the bar definitions or the endpoints breaks
# ---- comparability with everything already recorded; the ledger would have to be restarted, not amended.
UNIVERSE = [t + "USDT" for t in ("SPY QQQ EWJ EWY COIN TSLA MSTR PLTR HOOD AAPL AMZN META INTC MU CRCL "
                                 "LLY JPM QCOM TSM PAYP SNDK AAOI AXTI NOK").split()]
MIN_NAMES   = 20                     # fewer priced names -> no row for that weekend (universe was 8..19 before 2026-05-29)
FROZEN_FROM = date(2026, 5, 29)      # first weekend with a stable >= 20-name universe
DECLARED_ON = date(2026, 9, 18)      # last Friday before the record-only declaration; later Fridays are the blind series
REOPEN_N    = 35                     # post-declaration weekends required before any boundary may be declared
ENDPOINTS   = (8, 12)                # NY hour of the overnight endpoint bar's open; both recorded
LEDGER = os.path.join(REPO, "research", "edge_decay_ledger.jsonl")
# The 17 pre-declaration values, kept ONLY as a reproducibility check (selfcheck). They are not a baseline for any
# boundary: the withdrawn declaration showed 17 weekends cannot support one. A future boundary is calibrated on the
# post-declaration series alone.
FROZEN_L08 = [-0.555, -0.860, -0.529, -0.670, -1.873, -0.068, -1.601, -1.630, -0.960, -1.195, -1.272, -1.193, -1.113, -0.931, -0.538, -0.640, -1.521]
FROZEN_L12 = [-1.297, -1.481, -1.269, -1.233, -1.976, -0.936, -1.631, -1.842, -1.461, -1.555, -1.489, -1.588, -1.475, -1.370, -1.144, -0.466, -1.960]

US_FULL_CLOSURES = {date(2026,1,1), date(2026,1,19), date(2026,2,16), date(2026,4,3), date(2026,5,25),
    date(2026,6,19), date(2026,7,3), date(2026,9,7), date(2026,11,26), date(2026,12,25), date(2027,1,1),
    date(2027,1,18), date(2027,2,15), date(2027,3,26), date(2027,5,31), date(2027,6,18), date(2027,7,5),
    date(2027,9,6), date(2027,11,25), date(2027,12,24)}
US_EARLY_CLOSES = {date(2026,11,27), date(2026,12,24), date(2027,11,26)}

def is_us_trading_day(d): return d.weekday() < 5 and d not in US_FULL_CLOSURES
def ny_bar(d, h): return int(datetime(d.year, d.month, d.day, h, tzinfo=NY).timestamp())
def utc_bar(d, h): return int(datetime(d.year, d.month, d.day, h, tzinfo=timezone.utc).timestamp())

def bars_for(fri):
    """Live shifted form, identical to WeekendFadeMonitorApplication and analysis-sequential-test.py."""
    a = fri
    while not is_us_trading_day(a): a -= timedelta(days=1)
    x = fri + timedelta(days=1)
    while not is_us_trading_day(x): x += timedelta(days=1)
    e = x - timedelta(days=1)
    return ny_bar(a, 12 if a in US_EARLY_CLOSES else 15), utc_bar(e, 19)

def fridays(a, b):
    """Every Friday in [a, b]. No holiday filter: the live rule trades holiday weekends, so they are recorded."""
    d = a; out = []
    while d <= b: out.append(d); d += timedelta(days=7)
    return out

# ---- data ---------------------------------------------------------------------------------------------
def load():
    S = tempfile.mkdtemp(prefix="decay-")
    out = f"{S}/bars.psv"; syms = "','".join(UNIVERSE)
    with open(out, "w") as f:
        subprocess.run(["psql", "-U", os.environ.get("DB_USER", "prop_strategy_app"), "-d", "prop_strategy",
            "-At", "-F|", "-c", f"""SELECT symbol, extract(epoch FROM open_time)::bigint, close_price
              FROM binance_perp_kline WHERE "interval"='1h' AND symbol IN ('{syms}')
              AND open_time >= '2026-05-01 00:00+00'"""], stdout=f, check=True)
    close = defaultdict(dict)
    for line in open(out):
        s, t, c = line.rstrip("\n").split("|"); close[s][int(t)] = float(c)
    return close

def weekend_row(close, fri):
    """One ledger row, or None when fewer than MIN_NAMES names price every leg."""
    a, e = bars_for(fri); wh = (e - a) / H + 1
    wk = {s: abs(close[s][e] / close[s][a] - 1) / math.sqrt(wh)
          for s in UNIVERSE if a in close.get(s, {}) and e in close.get(s, {})}
    ov = {h: defaultdict(list) for h in ENDPOINTS}
    d = fri - timedelta(days=5)
    for _ in range(6):
        nxt = d + timedelta(days=1)
        if is_us_trading_day(d) and is_us_trading_day(nxt):
            ba = ny_bar(d, 15)
            for h in ENDPOINTS:
                bb = ny_bar(nxt, h); oh = (bb - ba) / H + 1
                for s in UNIVERSE:
                    if ba in close.get(s, {}) and bb in close.get(s, {}):
                        ov[h][s].append(abs(close[s][bb] / close[s][ba] - 1) / math.sqrt(oh))
        d += timedelta(days=1)
    ovm = {h: {s: st.mean(v) for s, v in ov[h].items() if v} for h in ENDPOINTS}
    both = [s for s in wk if all(s in ovm[h] and ovm[h][s] > 0 for h in ENDPOINTS)]
    if len(both) < MIN_NAMES: return None
    num = st.mean(wk[s] for s in both)
    row = {"fri": str(fri), "n": len(both), "weekend_hours": round(wh), "holiday": not is_us_trading_day(fri) or not is_us_trading_day(fri + timedelta(days=3)),
           "wk_bp_per_sqrt_h": round(num * 1e4, 3)}
    for h in ENDPOINTS:
        den = st.mean(ovm[h][s] for s in both)
        row[f"ov{h:02d}_bp_per_sqrt_h"] = round(den * 1e4, 3)
        row[f"L{h:02d}"] = round(math.log(num / den), 3)
    return row

def read_ledger():
    if not os.path.exists(LEDGER): return {}
    rows = {}
    for ln in open(LEDGER):
        ln = ln.strip()
        if ln: r = json.loads(ln); rows[r["fri"]] = r
    return rows

# ---- modes --------------------------------------------------------------------------------------------
if MODE == "selfcheck":
    close = load()
    got08, got12 = [], []
    for f in fridays(FROZEN_FROM, DECLARED_ON):
        r = weekend_row(close, f)
        if r: got08.append(r["L08"]); got12.append(r["L12"])
    ok = got08 == FROZEN_L08 and got12 == FROZEN_L12
    print(f"pre-declaration weekends reproduce: {ok}  (n={len(got08)})")
    if not ok:
        print(f"  L08 recomputed {got08}\n  L08 frozen     {FROZEN_L08}\n  L12 recomputed {got12}\n  L12 frozen     {FROZEN_L12}")
    sys.exit(0 if ok else 1)

elif MODE == "record":
    upto = datetime.strptime(sys.argv[2], "%Y-%m-%d").date() if len(sys.argv) > 2 else date.today()
    close = load()
    newest = max(t for s in close for t in close[s])
    todo = [f for f in fridays(FROZEN_FROM, upto) if f.weekday() == 4]
    last = todo[-1]
    _, e_last = bars_for(last)
    if newest < e_last:
        print(f"REFUSING: the weekend of {last} needs the entry bar at "
              f"{datetime.fromtimestamp(e_last, timezone.utc):%Y-%m-%d %H:%M UTC} but the newest stored bar is "
              f"{datetime.fromtimestamp(newest, timezone.utc):%Y-%m-%d %H:%M UTC}. Run the kline refresh first.")
        sys.exit(1)
    have = read_ledger(); added = 0; skipped = []
    os.makedirs(os.path.dirname(LEDGER), exist_ok=True)
    with open(LEDGER, "a") as fh:
        for f in todo:
            if str(f) in have: continue
            r = weekend_row(close, f)
            if r is None: skipped.append(str(f)); continue
            r["recorded_utc"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
            r["newest_bar_utc"] = datetime.fromtimestamp(newest, timezone.utc).isoformat(timespec="minutes")
            r["blind"] = f > DECLARED_ON
            fh.write(json.dumps(r) + "\n"); added += 1
    total = len(read_ledger()); blind = sum(1 for r in read_ledger().values() if r.get("blind"))
    print(f"recorded {added} new weekend(s); ledger holds {total} (blind post-declaration: {blind}/{REOPEN_N} needed to re-open)")
    if skipped: print(f"  no row (fewer than {MIN_NAMES} names priced): {', '.join(skipped)}")

elif MODE == "show":
    rows = sorted(read_ledger().values(), key=lambda r: r["fri"])
    if not rows: print("ledger is empty; run: record"); sys.exit(0)
    print(f"{'friday':<12}{'n':>3}{'hrs':>5}{'hol':>5}{'wk':>8}{'ov08':>8}{'ov12':>8}{'L08':>8}{'L12':>8}   blind")
    for r in rows:
        print(f"{r['fri']:<12}{r['n']:>3}{r['weekend_hours']:>5}{'yes' if r['holiday'] else '':>5}{r['wk_bp_per_sqrt_h']:>8.1f}"
              f"{r['ov08_bp_per_sqrt_h']:>8.1f}{r['ov12_bp_per_sqrt_h']:>8.1f}{r['L08']:>8.3f}{r['L12']:>8.3f}   {'*' if r.get('blind') else ''}")
    blind = sum(1 for r in rows if r.get("blind"))
    print(f"\n{len(rows)} weekends recorded, {blind} blind (post-declaration). A boundary may be declared at {REOPEN_N} blind weekends.")
    print("No aggregate is computed here by design.")
else:
    print(__doc__); sys.exit(2)
