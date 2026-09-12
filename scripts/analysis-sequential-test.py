#!/usr/bin/env python3
"""Group-sequential test for a newcomer cohort (IDEA_TESTING_PROTOCOL.md; prereg amendment A8).

  schedule                 weekend enumeration and the expected look timing
  count YYYY-MM-DD         bookkeeping: how many usable weekends exist through that Friday. Reads ONLY the
                           anchor and entry bars, computes NO outcome, prints NO statistic. Safe to run weekly.
  look YYYY-MM-DD          a scheduled look. Refuses unless a look is due at the current m. Computes outcomes,
                           the statistic, the due boundary and the futility rule, and appends to the ledger.
  boundaries               recompute the null calibration from the committed constants
  power                    reproduce the power, futility-power and timing tables in A8

Declared in WEEKEND_FADE_FUNDING_PREREGISTRATION.md amendment A8 on 2026-09-12, before any observation in the
window existed. Every constant below is part of that declaration. Changing one voids the test (protocol §5).

The split between `count` and `look` is the declaration enforced in code: between scheduled looks nothing about
outcomes is computed or displayed, so routine bookkeeping cannot become an off-schedule look.
"""
import json, math, os, random, statistics as st, subprocess, sys, tempfile
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE = sys.argv[1] if len(sys.argv) > 1 else "schedule"

# ---- declared constants (A8, 2026-09-12) -------------------------------------
COHORT_N1 = ["KOUSDT", "RDDTUSDT", "GDXUSDT", "NETUSDT", "VSTUSDT", "SHOPUSDT", "LYTEUSDT", "DJTUSDT",
             "MRNAUSDT", "TEMUSDT", "MRKUSDT", "IONQUSDT", "MARAUSDT", "PDDUSDT", "DDOGUSDT", "TEAMUSDT",
             "MDBUSDT", "ZSUSDT", "GTLBUSDT", "GPROUSDT"]
COHORT_A = ["SPYUSDT", "QQQUSDT", "EWJUSDT", "EWYUSDT", "COINUSDT", "TSLAUSDT", "MSTRUSDT", "PLTRUSDT",
            "HOODUSDT", "AAPLUSDT", "AMZNUSDT", "METAUSDT", "INTCUSDT", "MUUSDT", "CRCLUSDT", "LLYUSDT",
            "JPMUSDT", "QCOMUSDT", "TSMUSDT", "PAYPUSDT", "SNDKUSDT", "AAOIUSDT", "AXTIUSDT", "NOKUSDT"]
WINDOW_START = date(2026, 9, 18)   # first weekend whose ANCHOR bar closes after the declaration
LOOKS = [6, 9, 12]
MMAX = 12
C = 1.80
BOUNDARIES = {m: round(C / math.sqrt(m / MMAX), 2) for m in LOOKS}       # {6: 2.55, 9: 2.08, 12: 1.80}
ECON_BAR = 100.0
TAIL_BAR = -500.0
FUTILITY_Z = 1.282
CAL_BACKSTOP = date(2027, 4, 30)
TRIGGER_BP = -50.0
COST_BP = 9.0
LEDGER = os.path.join(REPO, "research", "n1_sequential_ledger.jsonl")

NY = ZoneInfo("America/New_York")
US_FULL_CLOSURES = {date(2026,1,1), date(2026,1,19), date(2026,2,16), date(2026,4,3), date(2026,5,25), date(2026,6,19),
    date(2026,7,3), date(2026,9,7), date(2026,11,26), date(2026,12,25), date(2027,1,1), date(2027,1,18),
    date(2027,2,15), date(2027,3,26), date(2027,5,31), date(2027,6,18), date(2027,7,5), date(2027,9,6),
    date(2027,11,25), date(2027,12,24)}
US_EARLY_CLOSES = {date(2026,11,27), date(2026,12,24), date(2027,11,26)}
H = 3600
def is_us_trading_day(d): return d.weekday() < 5 and d not in US_FULL_CLOSURES
def ny_bar(d, hour): return int(datetime(d.year, d.month, d.day, hour, tzinfo=NY).timestamp())
def utc_bar(d, hour): return int(datetime(d.year, d.month, d.day, hour, tzinfo=timezone.utc).timestamp())
def bars_for(fri):
    """Live shifted form, identical to WeekendFadeMonitorApplication: anchor = last US session close,
    entry = 19:00 UTC bar the evening before the next session, exit = 10:00 NY bar of that session."""
    a = fri
    while not is_us_trading_day(a): a -= timedelta(days=1)
    x = fri + timedelta(days=1)
    while not is_us_trading_day(x): x += timedelta(days=1)
    e = x - timedelta(days=1)
    return ny_bar(a, 12 if a in US_EARLY_CLOSES else 15), utc_bar(e, 19), ny_bar(x, 10)
def is_shifted(f):
    return f in US_FULL_CLOSURES or (f + timedelta(days=3)) in US_FULL_CLOSURES or f in US_EARLY_CLOSES
def weekends(a, b):
    out, d = [], a
    while d <= b: out.append(d); d += timedelta(days=7)
    return out

def load(symbols):
    tmp = tempfile.mkdtemp(prefix="seq-")
    q = "','".join(symbols)
    for sql, nm in ((f"""SELECT symbol, extract(epoch FROM open_time)::bigint, close_price, quote_asset_volume
                          FROM binance_perp_kline WHERE "interval"='1h' AND symbol IN ('{q}')""", "bars"),
                    (f"""SELECT DISTINCT symbol, extract(epoch FROM funding_time)::bigint, funding_rate
                          FROM binance_perp_funding_rate WHERE symbol IN ('{q}')""", "fund")):
        with open(f"{tmp}/{nm}.psv", "w") as fh:
            subprocess.run(["psql", "-U", os.environ.get("DB_USER", "prop_strategy_app"), "-d", "prop_strategy",
                            "-At", "-F|", "-c", sql], stdout=fh, check=True)
    close, qvol, fund = {}, {}, {}
    for line in open(f"{tmp}/bars.psv"):
        s, t, c, v = line.rstrip("\n").split("|")
        close.setdefault(s, {})[int(t)] = float(c); qvol.setdefault(s, {})[int(t)] = float(v)
    for line in open(f"{tmp}/fund.psv"):
        s, t, r = line.rstrip("\n").split("|")
        fund.setdefault(s, []).append((int(t), float(r)))
    return close, qvol, fund

def triggered(sym, fri, close):
    """Trigger test only: needs the anchor and entry bars, never the exit bar, so it computes no outcome."""
    a, e, _ = bars_for(fri)
    c = close.get(sym, {})
    if a not in c or e not in c: return None
    return (c[e] / c[a] - 1) * 1e4 <= TRIGGER_BP

def outcome(sym, fri, close, qvol, fund):
    a, e, x = bars_for(fri)
    c = close.get(sym, {})
    if a not in c or e not in c or x not in c: return None
    rows = fund.get(sym, [])
    if not rows: return None
    price = c[x] / c[e] - 1
    fsum = sum(r for (t, r) in rows if e + H < t <= x + H)
    return {"wknd_bp": (c[e] / c[a] - 1) * 1e4, "price_bp": price * 1e4, "funding_bp": -fsum * 1e4,
            "net_bp": (price - fsum) * 1e4 - COST_BP, "entry_qvol": qvol.get(sym, {}).get(e)}

def freshness(close, need_exit, upto):
    """Newest stored bar across the cohort, and the bars each weekend requires."""
    newest = max((max(d) for d in close.values() if d), default=0)
    return newest

def visited_looks():
    """Looks already evaluated, read from the ledger. A missed look is not skipped: when m overshoots it,
    it is evaluated on the prefix series[:k] at its own boundary B[k], never on the full series."""
    done = set()
    if os.path.exists(LEDGER):
        for line in open(LEDGER):
            try: done |= set(json.loads(line).get("looks_evaluated") or [])
            except Exception: pass
    return done

def due_look(m):
    return [k for k in LOOKS if k <= m and k not in visited_looks()]

# ---- modes -------------------------------------------------------------------
if MODE == "schedule":
    print(f"cohort N1: {len(COHORT_N1)} names; window opens {WINDOW_START}; live shifted form; holiday weekends included")
    print(f"looks at m = {LOOKS} usable weekends; boundaries " + ", ".join(f"t>={BOUNDARIES[m]}" for m in LOOKS)
          + f"; futility mean+{FUTILITY_Z}*SE < {ECON_BAR:.0f}; backstop {CAL_BACKSTOP}")
    ws = weekends(WINDOW_START, date(2027, 5, 7))
    fm = lambda t: datetime.fromtimestamp(t, timezone.utc).strftime("%a %m-%d %H:%M")
    for i, f in enumerate(ws, 1):
        a, e, x = bars_for(f)
        print(f"  {i:2}  Fri {f}   anchor {fm(a)}  entry {fm(e)}  exit {fm(x)}" + ("   SHIFTED" if is_shifted(f) else ""))
    print(f"\nat the measured rate of 0.59 usable weekends per calendar weekend (live form, holidays included):")
    for m in LOOKS:
        i = min(int(round(m / 0.59)) - 1, len(ws) - 1)
        print(f"   m={m:>2} expected around calendar weekend {int(round(m/0.59))} -> {ws[i]}")
    sys.exit()

if MODE in ("count", "look"):
    upto = datetime.strptime(sys.argv[2], "%Y-%m-%d").date()
    syms = COHORT_N1 + (COHORT_A if MODE == "look" else [])
    close, qvol, fund = load(syms)
    ws = weekends(WINDOW_START, upto)
    newest = freshness(close, MODE == "look", upto)
    rows, missing_any = [], False
    for f in ws:
        a, e, x = bars_for(f)
        need = [a, e, x] if MODE == "look" else [a, e]
        if newest < max(need):
            print(f"REFUSING: the weekend of {f} needs a bar at "
                  f"{datetime.fromtimestamp(max(need), timezone.utc):%Y-%m-%d %H:%M} UTC but the newest stored bar is "
                  f"{datetime.fromtimestamp(newest, timezone.utc):%Y-%m-%d %H:%M} UTC. Run the refresh first.")
            sys.exit(1)
        miss = [s for s in COHORT_N1 if any(b not in close.get(s, {}) for b in need) or (MODE == "look" and not fund.get(s))]
        trig = [s for s in COHORT_N1 if s not in miss and triggered(s, f, close)]
        if miss: missing_any = True
        rows.append({"friday": f, "shifted": is_shifted(f), "triggered": trig, "missing": miss})
    m = sum(1 for r in rows if r["triggered"])
    print(f"cohort N1 — window {WINDOW_START} .. {upto} — {len(ws)} calendar weekends, "
          f"newest stored bar {datetime.fromtimestamp(newest, timezone.utc):%Y-%m-%d %H:%M} UTC")
    for r in rows:
        print(f"  {r['friday']}{' (shifted)' if r['shifted'] else '         '}  triggers {len(r['triggered']):>2}"
              + (f"  {[s[:-4] for s in r['triggered']]}" if r["triggered"] else "  none")
              + (f"   [{len(r['missing'])} names without bars/funding]" if r["missing"] else ""))
    print(f"\n  m = {m} usable weekends; looks are due at m = {LOOKS}")

    pending = due_look(m)
    done = sorted(visited_looks())
    if MODE == "count":
        nxt = next((k for k in LOOKS if k > m and k not in visited_looks()), None)
        print(f"  looks already evaluated: {done or 'none'}")
        print("  No outcome was computed and no statistic was shown. "
              + (f"Next look at m = {nxt}." if nxt else "No look outstanding; run `look` when the next m is reached."))
        sys.exit()
    if not pending and upto < CAL_BACKSTOP:
        nxt = next((k for k in LOOKS if k > m and k not in visited_looks()), None)
        print(f"\n  REFUSING to look: no scheduled look is outstanding at m = {m} (already evaluated: {done or 'none'})."
              + (f" The next is m = {nxt}." if nxt else " All three looks are done."))
        print("  Running `look` off-schedule would void the test (IDEA_TESTING_PROTOCOL.md §5). Use `count` for bookkeeping.")
        sys.exit(1)

    # a look IS due -> compute outcomes now, for the first time
    series, per_weekend, ev_all = [], [], []
    for r in rows:
        if not r["triggered"]: continue
        evs = [(s, outcome(s, r["friday"], close, qvol, fund)) for s in r["triggered"]]
        evs = [(s, o) for s, o in evs if o]
        if not evs: continue
        wm = st.mean(o["net_bp"] for _, o in evs)
        series.append(wm); ev_all += [o["net_bp"] for _, o in evs]
        per_weekend.append({"friday": str(r["friday"]), "shifted": r["shifted"], "events": len(evs), "mean_bp": wm,
                            "names": {s[:-4]: round(o["net_bp"], 1) for s, o in evs}})
    ctrl = []
    for r in rows:
        evs = [o for s in COHORT_A if (o := outcome(s, r["friday"], close, qvol, fund)) and o["wknd_bp"] <= TRIGGER_BP]
        if evs: ctrl.append(st.mean(o["net_bp"] for o in evs))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    print(f"\n=== SCHEDULED LOOK(S) {pending} — computed {now} ===")
    for w in per_weekend:
        print(f"  {w['friday']}{' (shifted)' if w['shifted'] else ''}  events {w['events']:>2}  mean {w['mean_bp']:+8.1f} bp  {w['names']}")
    verdict = "continue"
    if done: print(f"  looks already evaluated in earlier runs: {done}")
    for k in pending:
        s_ = series[:k]
        if len(s_) < 2: continue
        mean, sd = st.mean(s_), st.stdev(s_); se = sd / math.sqrt(k); t = mean / se if se > 0 else 0.0
        b = BOUNDARIES.get(k, round(C / math.sqrt(k / MMAX), 2))
        ew = st.mean(ev_all[:sum(w["events"] for w in per_weekend[:k])]) if ev_all else float("nan")
        print(f"\n  look m={k}: mean {mean:+.1f} bp  SD {sd:.1f}  SE {se:.1f}  t {t:.2f}  (boundary t>={b})")
        print(f"     event-weighted mean (return per unit of capital deployed) {ew:+.1f} bp; worst weekend {min(s_):+.1f}")
        if t >= b and mean >= ECON_BAR and ew >= ECON_BAR:
            print(f"     ADMIT: boundary crossed and both economic bars cleared"); verdict = f"admit at m={k}"
        elif t >= b:
            print(f"     boundary crossed but an economic bar failed (equal-weight {mean:+.0f}, event-weighted {ew:+.0f}, bar {ECON_BAR:.0f}) -> not admitted")
        else:
            print(f"     not crossed (short by {b - t:.2f} in t, {b*se - mean:+.0f} bp in mean)")
        if mean + FUTILITY_Z * se < ECON_BAR:
            print(f"     FUTILITY: mean + {FUTILITY_Z}*SE = {mean + FUTILITY_Z*se:+.1f} < {ECON_BAR:.0f} -> cohort dead, test closed")
            verdict = f"futility at m={k}"
        if min(s_) < TAIL_BAR:
            print(f"     TAIL BREACH: worst weekend {min(s_):+.0f} beyond {TAIL_BAR:+.0f} -> standing condition violated, suspend")
            verdict = f"tail breach at m={k}"
        if ctrl:
            print(f"     context only, not part of the rule: cohort A on the same window mean {st.mean(ctrl):+.1f} bp "
                  f"over {len(ctrl)} weekends; N1 minus A {mean - st.mean(ctrl):+.1f} bp")
        if verdict != "continue":
            print(f"     test is terminal at m={k}; later looks are not evaluated.")
            pending = [j for j in pending if j <= k]
            break
    os.makedirs(os.path.dirname(LEDGER), exist_ok=True)
    with open(LEDGER, "a") as fh:
        fh.write(json.dumps({"through": str(upto), "computed_utc": now, "m": m, "looks_evaluated": pending,
                             "verdict": verdict, "series": series, "per_weekend": per_weekend,
                             "control_a_mean": st.mean(ctrl) if ctrl else None,
                             "newest_bar_utc": datetime.fromtimestamp(newest, timezone.utc).isoformat(),
                             "missing_seen": missing_any}) + "\n")
    print(f"\n  appended to {os.path.relpath(LEDGER, REPO)} — commit it; the ledger is the record that no look happened off schedule.")
    sys.exit()

if MODE in ("boundaries", "power"):
    g = {"__name__": "prelude", "__file__": os.path.join(REPO, "scripts", "analysis-fade-a7-newcomers.py")}
    sys.argv = ["x", "noop"]
    exec(compile(open(g["__file__"]).read().split('if MODE == "selfcheck":')[0], "prelude", "exec"), g)
    rng = random.Random(20260912)
    # The null is built on the definition A8 declares: LIVE shifted form, holiday weekends INCLUDED.
    FR = g["fridays_between"](date(2026, 4, 3), date(2026, 8, 28), set())
    E1, oc = g["E1"], g["outcome"]
    mat = {s: {f: o["net_bp"] for f in FR if (o := oc(s, f, True)) and o["wknd_bp"] <= TRIGGER_BP} for s in E1}
    pool, usable = [], []
    for _ in range(3000):
        sub = rng.sample(E1, 20); n = 0
        for f in FR:
            v = [mat[s][f] for s in sub if f in mat[s]]
            if v: pool.append(st.mean(v)); n += 1
        usable.append(n / len(FR))
    mu = st.mean(pool); EMP = [x - mu for x in pool]; SD = st.stdev(EMP); RATE = st.mean(usable)
    NORM = [rng.gauss(0, SD) for _ in range(200000)]
    T4 = [rng.gauss(0,1) / math.sqrt(sum(rng.gauss(0,1)**2 for _ in range(4))/4) * SD / math.sqrt(2) for _ in range(100000)]
    tst = lambda xs: (st.mean(xs)/(st.stdev(xs)/math.sqrt(len(xs)))) if len(xs) > 1 and st.stdev(xs) > 0 else None
    def paths(p, trials, mean=0.0, econ=False):
        first = {m: 0 for m in LOOKS}; fut = {m: 0 for m in LOOKS}
        for _ in range(trials):
            xs = [rng.choice(p) + mean for _ in range(MMAX)]
            for m in LOOKS:
                s_ = xs[:m]; t = tst(s_)
                if t is None: continue
                if t >= BOUNDARIES[m] and (not econ or st.mean(s_) >= ECON_BAR): first[m] += 1; break
                if econ and st.mean(s_) + FUTILITY_Z * st.stdev(s_) / math.sqrt(m) < ECON_BAR: fut[m] += 1; break
        return first, fut
    if MODE == "boundaries":
        print("Null: centred weekend means of random 20-name cohorts drawn from the E1 population, on the LIVE")
        print("shifted form with holiday weekends included — the same definition the test computes.\n")
        print(f"  cohort weekend-series SD {SD:.0f} bp; usable weekends {RATE:.3f} per calendar weekend")
        print(f"  boundaries in force: " + ", ".join(f"m={m}: t>={BOUNDARIES[m]}" for m in LOOKS))
        for nm, p in (("empirical", EMP), ("normal", NORM), ("t(4)", T4)):
            f, _ = paths(p, 50000)
            print(f"  family-wise one-sided alpha, {nm:10}: {sum(f.values())/50000:.4f}  (efficacy only; ceiling 0.075, SE ~0.001)")
        f, _ = paths(EMP, 50000, econ=True)
        print(f"  full rule (economic bar + futility stopping), empirical: {sum(f.values())/50000:.4f}")
        print(f"\n  sample SD at an m=6 crossing is SELECTED to be small; the +100 bp bar is the operative guard there:")
        sds, means = [], []
        for _ in range(60000):
            xs = [rng.choice(EMP) for _ in range(6)]; t = tst(xs)
            if t is not None and t >= BOUNDARIES[6]: sds.append(st.stdev(xs)); means.append(st.mean(xs))
        if sds:
            print(f"     unconditional SD of 6 draws: median {st.median([st.stdev([rng.choice(EMP) for _ in range(6)]) for _ in range(20000)]):.0f} bp")
            print(f"     conditional on crossing: SD median {st.median(sds):.0f} bp, crossing mean median {st.median(means):.0f} bp, "
                  f"share below the +100 bar {sum(1 for x in means if x < ECON_BAR)/len(means):.3f}")
        sys.exit()
    print("Power (empirical null shifted to a true weekend mean; economic bar applied, futility active):\n")
    print(f"  {'true mean':>9} | {'admitted':>8} | " + " ".join(f"{'m='+str(m):>7}" for m in LOOKS) + " | {:>9} | {:>8}".format("futility", "A7 single"))
    for mu_ in (0, 50, 100, 150, 200, 250, 300):
        f, fut = paths(EMP, 20000, mean=mu_, econ=True)
        single = sum(1 for _ in range(20000) if (xs := [rng.choice(EMP) + mu_ for _ in range(12)]) and (t := tst(xs)) and t >= 1.5 and st.mean(xs) >= ECON_BAR) / 20000
        print(f"  {mu_:>9} | {sum(f.values())/20000:>8.3f} | " + " ".join(f"{f[m]/20000:>7.3f}" for m in LOOKS)
              + f" | {sum(fut.values())/20000:>9.3f} | {single:>8.3f}")
    print(f"\nusable-weekend rate {RATE:.3f}; cohort SD {SD:.0f} bp; at that SD the boundaries need means of "
          + ", ".join(f"{BOUNDARIES[m]*SD/math.sqrt(m):.0f}" for m in LOOKS) + " bp at m = " + str(LOOKS))
    sys.exit()
print(__doc__)
