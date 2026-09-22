#!/usr/bin/env python3
"""WITHDRAWN 2026-09-22 -- kept verbatim so every number in the withdrawn declaration reproduces. Do not run watch.
Weekend-fade edge-decay monitor v1 (EDGE_DECAY_MONITOR_PREREGISTRATION.md, withdrawn section).

Watches the INPUT to the strategy, not its returns. The fade exists because tokenized-stock perps drift
while the US market is shut and re-anchor when it opens. If that drift compresses -- because other traders
buy the weekend dip, or because market-making in these perps matures -- the edge shrinks. Compression is
visible in the drift itself long before it is visible in P/L.

  baseline                 print the frozen baseline weekends and their statistic
  calibrate [trials]       re-derive the alarm boundary against three nulls (reproduces C)
  power [trials]           detection probability and timing for a range of true compressions
  watch [YYYY-MM-DD]       current reading: per-weekend R, the rolling mean, and the boundary
  selfcheck                assert the frozen constants still reproduce from the database

Statistic per weekend:  R = mean|weekend drift| / mean|overnight drift|
  weekend drift  = anchor (last US session close) -> entry bar (19:00 UTC before the next session),
                   exactly the live rule's own window, so R is computed from bars the routine already reads.
  overnight drift= same names, the weekday nights of the week BEFORE this weekend (15:00 NY close ->
                   12:00 NY the next session), averaged. This is the control: it is the same dislocation
                   mechanism over a shorter closed window, in the same names, in the same market weather.
                   Dividing by it removes market-wide volatility, which is what makes R sensitive enough
                   to be useful. It uses the PRECEDING week so R exists at the decision bar, not a week later.

R falling means the weekend gap is shrinking relative to ordinary overnight gaps in the same names -- the
signature of the weekend window specifically being competed away. Both falling together is market-wide
calm and leaves R unchanged, which is the point.

An alarm does NOT stop trading. See the pre-registration: it mandates a re-measurement.
"""
import json, math, os, random, statistics as st, subprocess, sys, tempfile
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE = sys.argv[1] if len(sys.argv) > 1 else "watch"
NY = ZoneInfo("America/New_York")

# ---- declared constants (frozen 2026-09-21; changing one voids the monitor, protocol section 5) -------
UNIVERSE = [t + "USDT" for t in ("SPY QQQ EWJ EWY COIN TSLA MSTR PLTR HOOD AAPL AMZN META INTC MU CRCL "
                                 "LLY JPM QCOM TSM PAYP SNDK AAOI AXTI NOK").split()]
MIN_NAMES      = 20      # a weekend with fewer priced names does not produce an R
BASELINE_FROM  = date(2026, 5, 29)   # first weekend with a stable >=20-name universe
BASELINE_TO    = date(2026, 8, 21)   # last weekend of the pre-registered return sample
K              = 12      # rolling window, in usable weekends
C              = 0.265   # alarm boundary; see calibrate (highest c with worst-null FA <= TARGET_FA)
HORIZON        = 104     # weekly checks the boundary is calibrated over (~2 years)
TARGET_FA      = 0.10    # family-wise false-alarm rate over HORIZON
BASELINE_R = [0.408, 0.339, 0.419, 0.531, 0.207, 0.292, 0.237, 0.346, 0.315, 0.337, 0.305, 0.341]
LEDGER = os.path.join(REPO, "research", "edge_decay_ledger_v1_withdrawn.jsonl")

US_FULL_CLOSURES = {date(2026,1,1), date(2026,1,19), date(2026,2,16), date(2026,4,3), date(2026,5,25),
    date(2026,6,19), date(2026,7,3), date(2026,9,7), date(2026,11,26), date(2026,12,25), date(2027,1,1),
    date(2027,1,18), date(2027,2,15), date(2027,3,26), date(2027,5,31), date(2027,6,18), date(2027,7,5),
    date(2027,9,6), date(2027,11,25), date(2027,12,24)}
US_EARLY_CLOSES = {date(2026,11,27), date(2026,12,24), date(2027,11,26)}
HOL_PUBLISHED = {date(2026,1,16), date(2026,2,13), date(2026,4,3), date(2026,5,22), date(2026,7,3), date(2026,9,4)}

def is_us_trading_day(d): return d.weekday() < 5 and d not in US_FULL_CLOSURES
def ny_bar(d, h): return int(datetime(d.year, d.month, d.day, h, tzinfo=NY).timestamp())
def utc_bar(d, h): return int(datetime(d.year, d.month, d.day, h, tzinfo=timezone.utc).timestamp())

def bars_for(fri):
    """Live shifted form, identical to WeekendFadeMonitorApplication and to analysis-sequential-test.py."""
    a = fri
    while not is_us_trading_day(a): a -= timedelta(days=1)
    x = fri + timedelta(days=1)
    while not is_us_trading_day(x): x += timedelta(days=1)
    e = x - timedelta(days=1)
    return ny_bar(a, 12 if a in US_EARLY_CLOSES else 15), utc_bar(e, 19)

# ---- data ---------------------------------------------------------------------------------------------
def load():
    S = tempfile.mkdtemp(prefix="decay-")
    out = f"{S}/bars.psv"
    syms = "','".join(UNIVERSE)
    with open(out, "w") as f:
        subprocess.run(["psql", "-U", os.environ.get("DB_USER", "prop_strategy_app"), "-d", "prop_strategy",
            "-At", "-F|", "-c", f"""SELECT symbol, extract(epoch FROM open_time)::bigint, close_price
              FROM binance_perp_kline WHERE "interval"='1h' AND symbol IN ('{syms}')
              AND open_time >= '2025-12-01 00:00+00'"""], stdout=f, check=True)
    close = defaultdict(dict)
    for line in open(out):
        s, t, c = line.rstrip("\n").split("|")
        close[s][int(t)] = float(c)
    return close

def weekend_R(close, fri):
    """R for this weekend, or None when fewer than MIN_NAMES names price both legs."""
    a, e = bars_for(fri)
    wk = {s: close[s][e] / close[s][a] - 1 for s in UNIVERSE if a in close.get(s, {}) and e in close.get(s, {})}
    ov = defaultdict(list)
    d = fri - timedelta(days=5)
    for _ in range(6):
        nxt = d + timedelta(days=1)
        if is_us_trading_day(d) and is_us_trading_day(nxt):
            ba, bb = ny_bar(d, 15), ny_bar(nxt, 12)
            for s in UNIVERSE:
                if ba in close.get(s, {}) and bb in close.get(s, {}):
                    ov[s].append(abs(close[s][bb] / close[s][ba] - 1))
        d += timedelta(days=1)
    ovm = {s: st.mean(v) for s, v in ov.items() if v}
    both = [s for s in wk if s in ovm and ovm[s] > 0]
    if len(both) < MIN_NAMES: return None
    num = st.mean(abs(wk[s]) for s in both); den = st.mean(ovm[s] for s in both)
    return dict(fri=fri, n=len(both), R=num / den, wk_bp=num * 1e4, ov_bp=den * 1e4,
                trig=sum(1 for s in both if wk[s] <= -0.0050))

def fridays(a, b):
    d = a; out = []
    while d <= b:
        if d not in HOL_PUBLISHED: out.append(d)
        d += timedelta(days=7)
    return out

# ---- calibration --------------------------------------------------------------------------------------
def nulls():
    m, sd = st.mean(BASELINE_R), st.stdev(BASELINE_R)
    def boot(): return random.choice(BASELINE_R)
    def norm(): return random.gauss(m, sd)
    def t4():
        z = random.gauss(0, 1); v = sum(random.gauss(0, 1) ** 2 for _ in range(4)) / 4
        return m + (z / math.sqrt(v)) * sd / math.sqrt(2.0)
    return {"bootstrap": boot, "normal": norm, "t(4)": t4}

def false_alarm(c, draw, trials):
    hit = 0
    for _ in range(trials):
        s = [draw() for _ in range(K)]; run = sum(s)
        for _ in range(HORIZON):
            if run / K < c: hit += 1; break
            nv = draw(); run += nv - s[0]; s = s[1:] + [nv]
    return hit / trials

def detect(c, factor, draw, trials):
    hits = []
    for _ in range(trials):
        s = [draw() for _ in range(K)]; run = sum(s)
        for wkn in range(1, HORIZON + 1):
            nv = draw() * factor; run += nv - s[0]; s = s[1:] + [nv]
            if run / K < c: hits.append(wkn); break
    return len(hits) / trials, (st.median(hits) if hits else None)

# ---- modes --------------------------------------------------------------------------------------------
if MODE == "calibrate":
    trials = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
    random.seed(20260921)
    m = st.mean(BASELINE_R)
    print(f"baseline mean {m:.4f} sd {st.stdev(BASELINE_R):.4f}  K={K}  horizon={HORIZON}  target FA<={TARGET_FA}")
    print(f"\n{'c':>7}{'% of base':>11}" + "".join(f"{k:>12}" for k in nulls()) + f"{'worst':>9}")
    chosen = None
    for c in (0.230, 0.240, 0.250, 0.255, 0.260, 0.265, 0.270, 0.280):
        fa = {k: false_alarm(c, d, trials) for k, d in nulls().items()}
        w = max(fa.values())
        print(f"{c:>7.3f}{c/m*100:>10.0f}%" + "".join(f"{v:>12.3f}" for v in fa.values()) + f"{w:>9.3f}")
        if w <= TARGET_FA: chosen = c        # highest passing c: a higher boundary fires sooner
    print(f"\nhighest c whose WORST null stays within the target: {chosen}  (declared C = {C})")
    print("protocol section 3: keep the worst of the three nulls; a recomputation may only tighten C.")

elif MODE == "power":
    trials = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
    random.seed(20260921)
    d = nulls()["bootstrap"]
    print(f"C={C}  K={K}  horizon={HORIZON} weekly checks after the window fills\n")
    print(f"{'true compression':>18}{'P(detect)':>12}{'median weekends':>18}")
    for f in (0.95, 0.90, 0.85, 0.80, 0.70, 0.60, 0.50):
        p, md = detect(C, f, d, trials)
        print(f"{(1-f)*100:>17.0f}%{p:>12.2f}{(md if md else '-'):>18}")

elif MODE == "baseline":
    close = load()
    print(f"frozen baseline {BASELINE_FROM} .. {BASELINE_TO}, universe {len(UNIVERSE)} names, gate >={MIN_NAMES}\n")
    print(f"{'friday':<12}{'n':>4}{'wknd bp':>10}{'ovnite bp':>11}{'R':>8}{'trig':>6}")
    live = []
    for f in fridays(BASELINE_FROM, BASELINE_TO):
        r = weekend_R(close, f)
        if not r: continue
        live.append(round(r["R"], 3))
        print(f"{str(f):<12}{r['n']:>4}{r['wk_bp']:>10.0f}{r['ov_bp']:>11.0f}{r['R']:>8.3f}{r['trig']:>6}")
    print(f"\nrecomputed: {live}")
    print(f"frozen    : {BASELINE_R}")
    print("MATCH" if live == BASELINE_R else "*** MISMATCH -- the frozen constants no longer reproduce ***")

elif MODE in ("watch", "selfcheck"):
    upto = date.today()
    if MODE == "watch" and len(sys.argv) > 2: upto = datetime.strptime(sys.argv[2], "%Y-%m-%d").date()
    close = load()
    newest = max(t for s in close for t in close[s])
    rows = [r for f in fridays(BASELINE_FROM, upto) if (r := weekend_R(close, f))]
    if MODE == "selfcheck":
        base = [round(r["R"], 3) for r in rows if r["fri"] <= BASELINE_TO]
        ok = base == BASELINE_R
        print(f"baseline reproduces: {ok}")
        if not ok: print(f"  recomputed {base}\n  frozen     {BASELINE_R}")
        sys.exit(0 if ok else 1)
    post = [r for r in rows if r["fri"] > BASELINE_TO]
    print(f"edge-decay monitor  --  newest stored bar "
          f"{datetime.fromtimestamp(newest, timezone.utc):%Y-%m-%d %H:%M UTC}")
    print(f"baseline mean R {st.mean(BASELINE_R):.3f} (n={len(BASELINE_R)}), boundary C={C} "
          f"({C/st.mean(BASELINE_R)*100:.0f}% of baseline), rolling K={K}\n")
    print(f"{'friday':<12}{'n':>4}{'wknd bp':>10}{'ovnite bp':>11}{'R':>8}{'trig':>6}   rolling{K}")
    for r in rows:
        idx = rows.index(r)
        win = rows[max(0, idx - K + 1):idx + 1]
        roll = st.mean(x["R"] for x in win) if len(win) == K else None
        mark = "" if r["fri"] <= BASELINE_TO else " *"
        rl = f"{roll:.3f}" + ("  ALARM" if roll is not None and roll < C else "") if roll is not None else "-"
        print(f"{str(r['fri']):<12}{r['n']:>4}{r['wk_bp']:>10.0f}{r['ov_bp']:>11.0f}{r['R']:>8.3f}"
              f"{r['trig']:>6}{mark:>3}{rl:>12}")
    print(f"\n{len(post)} weekend(s) since the baseline closed. * = post-baseline.")
    if len(rows) >= K:
        roll = st.mean(x["R"] for x in rows[-K:])
        if roll < C:
            print(f"ALARM: rolling mean {roll:.3f} < C={C}. Per the pre-registration this does NOT stop "
                  f"trading; it mandates a re-measurement of the edge on post-alarm weekends only.")
        else:
            print(f"No alarm: rolling mean {roll:.3f} >= C={C} "
                  f"(would need a further {(1-C/roll)*100:.0f}% fall to fire).")
    else:
        print(f"Rolling window not full: {len(rows)}/{K} usable weekends. No alarm is possible yet.")
    os.makedirs(os.path.dirname(LEDGER), exist_ok=True)
    with open(LEDGER, "a") as fh:
        fh.write(json.dumps({"run_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                             "upto": str(upto), "n_weekends": len(rows),
                             "rolling": round(st.mean(x["R"] for x in rows[-K:]), 4) if len(rows) >= K else None,
                             "C": C}) + "\n")
else:
    print(__doc__); sys.exit(2)
