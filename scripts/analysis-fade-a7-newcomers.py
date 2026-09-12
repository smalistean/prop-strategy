#!/usr/bin/env python3
"""Amendment A7 of WEEKEND_FADE_FUNDING_PREREGISTRATION.md: newcomer features, tests T1-T3/T2b, diagnostics, sealed shadow rows.
The A7 admission rule was withdrawn on 2026-09-09 after adversarial review; the code stays so every number in A7 reproduces.

Usage:
  python3 scripts/analysis-fade-a7-newcomers.py selfcheck            reproduce the A6 headline (orig-27 to 2026-08-21)
  python3 scripts/analysis-fade-a7-newcomers.py full                 T1 separation, E1 extension, T2 terciles, T3 newcomers
  python3 scripts/analysis-fade-a7-newcomers.py diagnostics         T2b splits at 0.191 and the critic-pass diagnostics
  python3 scripts/analysis-fade-a7-newcomers.py shadow SYM[,SYM] YYYY-MM-DD   integrity columns only; outcomes sealed to logs/a7_shadow_sealed.jsonl
Reads binance_perp_kline (1h) and binance_perp_funding_rate through psql; writes logs/a7_results.json in full mode.
Outcome definition: anchor = last US regular-session close before the weekend (15:00 NY bar, 12:00 on early closes),
entry = 19:00 UTC bar of the day before the next US session, exit = 10:00 NY bar of that session (America/New_York clock,
NYSE closure list), i.e. the frozen definition on ordinary weekends and the live spec's shifted form on holiday weekends.
Frozen A7 values live in data/fade-a7-features-2026-09-09.json; E1 membership and thresholds are constants below, not re-derived.
"""
import json, math, os, statistics as st, subprocess, sys, tempfile
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE = sys.argv[1] if len(sys.argv) > 1 else "full"
S = tempfile.mkdtemp(prefix="a7-")
def psql(sql, out):
    with open(out, "w") as f:
        subprocess.run(["psql", "-U", os.environ.get("DB_USER", "prop_strategy_app"), "-d", "prop_strategy", "-At", "-F|", "-c", sql], stdout=f, check=True)
psql("""SELECT k.symbol, extract(epoch FROM k.open_time)::bigint, k.close_price, k.quote_asset_volume
  FROM binance_perp_kline k WHERE k."interval"='1h' AND k.open_time >= '2025-12-01 00:00+00' AND k.symbol NOT LIKE '1000%'""", f"{S}/bars_all.psv")
psql("""SELECT DISTINCT symbol, extract(epoch FROM funding_time)::bigint, funding_rate
  FROM binance_perp_funding_rate WHERE funding_time >= '2025-12-01 00:00+00'""", f"{S}/funding_all.psv")
import urllib.request
_info = json.loads(urllib.request.urlopen("https://fapi.binance.com/fapi/v1/exchangeInfo", timeout=30).read())
info = {x["symbol"]: {"ut": x.get("underlyingType"), "onboard": x["onboardDate"] / 1000, "status": x["status"]} for x in _info["symbols"] if x.get("underlyingType") and x["underlyingType"] != "COIN"}
H = 3600
def ts(y, m, d, h=0): return int(datetime(y, m, d, h, tzinfo=timezone.utc).timestamp())
ORIG27 = "SPYUSDT,QQQUSDT,EWJUSDT,EWYUSDT,COINUSDT,TSLAUSDT,MSTRUSDT,PLTRUSDT,HOODUSDT,AAPLUSDT,AMZNUSDT,METAUSDT,INTCUSDT,MUUSDT,CRCLUSDT,NVDAUSDT,LLYUSDT,JPMUSDT,QCOMUSDT,TSMUSDT,PAYPUSDT,SNDKUSDT,AAOIUSDT,AXTIUSDT,NOKUSDT,OPENAIUSDT,SPCXUSDT".split(",")
MEASURED25 = [s for s in ORIG27 if s not in ("OPENAIUSDT", "SPCXUSDT")]
NEWCOMERS = [t + "USDT" for t in "KO RDDT GDX NET VST SHOP LYTE SKUU SKDD RAM DJT MRNA TEM MRK IONQ MARA PDD NVDL TSLL DDOG TEAM MDB ZS GTLB GPRO".split()]
E1_EXCL = {t + "USDT" for t in "SOXL SOXS TQQQ SQQQ TZA TBT TMF UVXY BITO".split()} | {"SPCXUSD1"}
# Frozen E1 set of the 2026-09-09 20:12 UTC run (95 names); not re-derived from exchangeInfo so a listing cannot move the thresholds.
E1 = ["ADBEUSDT", "ALABUSDT", "AMATUSDT", "AMDUSDT", "APPUSDT", "ARMUSDT", "ASMLUSDT", "ASTSUSDT", "AVGOUSDT", "BABAUSDT", "BBXUSDT", "BEUSDT", "BMNRUSDT", "BNCUSDT", "BOTUSDT", "BRKBUSDT", "BSPUSDT", "BXUSDT", "CATUSDT", "CBRSUSDT", "CIENUSDT", "COHRUSDT", "COSTUSDT", "CRDOUSDT", "CRMUSDT", "CRWDUSDT", "CRWVUSDT", "CSCOUSDT", "DELLUSDT", "DISUSDT", "DKNGUSDT", "DRAMUSDT", "EBAYUSDT", "EWTUSDT", "EWZUSDT", "FLEXUSDT", "FLNCUSDT", "FWDIUSDT", "GEVUSDT", "GLWUSDT", "GMEUSDT", "GOOGLUSDT", "GSUSDT", "HDUSDT", "HIMSUSDT", "HPEUSDT", "IBMUSDT", "INTWUSDT", "IRENUSDT", "IWMUSDT", "KLACUSDT", "KORUUSDT", "KSTRUSDT", "LITEUSDT", "LRCXUSDT", "MRVLUSDT", "MSFTUSDT", "MUUUSDT", "MVLLUSDT", "NBISUSDT", "NFLXUSDT", "NOWUSDT", "NVOUSDT", "ONDSUSDT", "ORCLUSDT", "PANWUSDT", "PENGUSDT", "PYPLUSDT", "QNTXUSDT", "RIVNUSDT", "RKLBUSDT", "SHAZUSDT", "SKHYUSDT", "SMCIUSDT", "SMHUSDT", "SNOWUSDT", "SNXXUSDT", "SOFIUSDT", "SONYUSDT", "STRCUSDT", "STXXUSDT", "TERUSDT", "TTWOUSDT", "TXNUSDT", "UBERUSDT", "URNMUSDT", "USARUSDT", "VRTUSDT", "VUSDT", "WDCUSDT", "WENUSDT", "WMTUSDT", "XBIUSDT", "XLEUSDT", "ZMUSDT"]
F1_THRESHOLD_A7 = 0.191   # max(measured p25 0.170, E1 p75 0.191); rule withdrawn, value kept
F1_TERCILE_CUTS_A7 = (0.089, 0.159)
LATER_LISTINGS = sorted(k for k, v in info.items() if v["ut"] == "EQUITY" and v["status"] == "TRADING" and v["onboard"] > datetime(2026, 9, 3, 23, tzinfo=timezone.utc).timestamp() and k not in NEWCOMERS)

# ---- load
close, qvol = defaultdict(dict), defaultdict(dict)
for line in open(f"{S}/bars_all.psv"):
    sym, t, c, q = line.rstrip("\n").split("|"); t = int(t)
    close[sym][t] = float(c); qvol[sym][t] = float(q)
fund = defaultdict(list)
for line in open(f"{S}/funding_all.psv"):
    sym, t, r = line.rstrip("\n").split("|"); fund[sym].append((int(t), float(r)))
for sym in fund: fund[sym].sort()
btc = close["BTCUSDT"]

# ---- frozen outcome definition (equities): fri = Friday date
NY = ZoneInfo("America/New_York")
US_FULL_CLOSURES = {date(2026,1,1), date(2026,1,19), date(2026,2,16), date(2026,4,3), date(2026,5,25), date(2026,6,19), date(2026,7,3), date(2026,9,7),
    date(2026,11,26), date(2026,12,25), date(2027,1,1), date(2027,1,18), date(2027,2,15), date(2027,3,26), date(2027,5,31), date(2027,6,18), date(2027,7,5),
    date(2027,9,6), date(2027,11,25), date(2027,12,24)}
US_EARLY_CLOSES = {date(2026,11,27), date(2026,12,24), date(2027,11,26)}
def is_us_trading_day(d): return d.weekday() < 5 and d not in US_FULL_CLOSURES
def ny_bar(d, hour): return int(datetime(d.year, d.month, d.day, hour, tzinfo=NY).timestamp())
def bars_for(fri, live_form=False):
    """Frozen form (the pre-registered SQL): Friday's 15:00-NY bar, Sunday 19:00 UTC bar, Monday's 10:00-NY bar, whether or
    not the market was open (holiday weekends are excluded from studies, not re-anchored). Live form (the spec's shifted
    rule): anchor = last US session's close, entry = 19:00 UTC the day before the next session, exit = that session."""
    if not live_form:
        mon = fri + timedelta(days=3); sun = fri + timedelta(days=2)
        return ny_bar(fri, 15), ts(sun.year, sun.month, sun.day, 19), ny_bar(mon, 10)
    a = fri
    while not is_us_trading_day(a): a -= timedelta(days=1)
    x = fri + timedelta(days=1)
    while not is_us_trading_day(x): x += timedelta(days=1)
    e = x - timedelta(days=1)
    return ny_bar(a, 12 if a in US_EARLY_CLOSES else 15), ts(e.year, e.month, e.day, 19), ny_bar(x, 10)
def outcome(sym, fri, live_form=False):
    t_fri, t_entry, t_exit = bars_for(fri, live_form)
    c = close.get(sym, {})
    if t_fri not in c or t_entry not in c or t_exit not in c: return None
    wknd = c[t_entry] / c[t_fri] - 1
    price = c[t_exit] / c[t_entry] - 1
    entry_ts, exit_ts = t_entry + H, t_exit + H
    fsum = sum(r for (t, r) in fund.get(sym, []) if entry_ts < t <= exit_ts)
    return {"wknd_bp": wknd * 1e4, "price_bp": price * 1e4, "funding_bp": -fsum * 1e4, "net_bp": (price - fsum) * 1e4 - 9, "entry_qvol": qvol[sym].get(t_entry)}

def declustered(symbols, fridays):
    weekends, events = [], 0
    for fri in fridays:
        nets = []
        for s in symbols:
            o = outcome(s, fri)
            if o and o["wknd_bp"] <= -50: nets.append(o["net_bp"])
        if nets: weekends.append(st.mean(nets)); events += len(nets)
    n = len(weekends)
    if n < 2: return {"n_weekends": n, "events": events, "mean": None, "t": None, "series": weekends}
    m = st.mean(weekends); sd = st.stdev(weekends)
    return {"n_weekends": n, "events": events, "mean": m, "median": st.median(weekends), "t": m / (sd / math.sqrt(n)) if sd > 0 else None, "worst": min(weekends), "series": weekends}

def fridays_between(a, b, excl):
    d = a; out = []
    while d <= b:
        if d not in excl: out.append(d)
        d += timedelta(days=7)
    return out
HOL_PUBLISHED = {datetime(2026, 1, 16).date(), datetime(2026, 2, 13).date(), datetime(2026, 4, 3).date(), datetime(2026, 5, 22).date(), datetime(2026, 7, 3).date(), datetime(2026, 9, 4).date()}
def hol_by_rule(a, b):
    """Fridays whose weekend is adjacent to a full US closure (the written rule): Friday or the following Monday closed."""
    out, d = set(), a
    while d <= b:
        if d in US_FULL_CLOSURES or (d + timedelta(days=3)) in US_FULL_CLOSURES: out.add(d)
        d += timedelta(days=7)
    return out
HOL = HOL_PUBLISHED   # A6/A7 numbers reproduce with the enumerated list; see selfcheck for the rule-derived variant

if MODE == "selfcheck":
    a, b = datetime(2025, 12, 12).date(), datetime(2026, 8, 21).date()
    fr = fridays_between(a, b, HOL_PUBLISHED)
    r27 = declustered(ORIG27, fr); r25 = declustered(MEASURED25, fr)
    show = lambda r: {k: (round(v, 2) if isinstance(v, float) else v) for k, v in r.items() if k != "series"}
    print("SELFCHECK orig27 to 2026-08-21 (published holiday list):", show(r27)); print("SELFCHECK measured25:", show(r25))
    rule = hol_by_rule(a, b); extra = sorted(rule - HOL_PUBLISHED); print("closure-adjacent Fridays missing from the published list:", [str(d) for d in extra])
    fr2 = fridays_between(a, b, rule)
    print("  orig27 with the rule-derived list:", show(declustered(ORIG27, fr2))); print("  measured25 with the rule-derived list:", show(declustered(MEASURED25, fr2)))
    for d in extra:
        ev = [(sym[:-4], round(outcome(sym, d)["net_bp"], 1)) for sym in ORIG27 if outcome(sym, d) and outcome(sym, d)["wknd_bp"] <= -50]
        print(f"  events on {d} under the frozen (Friday-bar) form: {ev}")
        ev2 = [(sym[:-4], round(outcome(sym, d, True)["net_bp"], 1)) for sym in ORIG27 if outcome(sym, d, True) and outcome(sym, d, True)["wknd_bp"] <= -50]
        print(f"  the same weekend under the live shifted form (anchor = last session close): {ev2}")
    fmt = lambda t: datetime.fromtimestamp(t, timezone.utc).strftime("%a %H:%M")
    w = bars_for(date(2026, 1, 30)); s_ = bars_for(date(2026, 8, 21)); h = bars_for(date(2026, 9, 4), True); j = bars_for(date(2026, 6, 19), True)
    print("bars — winter 2026-01-30:", [fmt(t) for t in w], "| summer 2026-08-21:", [fmt(t) for t in s_], "| Labor Day live form:", [fmt(t) for t in h], "| Juneteenth live form:", [fmt(t) for t in j])
    assert [fmt(t) for t in w] == ["Fri 20:00", "Sun 19:00", "Mon 15:00"] and [fmt(t) for t in s_] == ["Fri 19:00", "Sun 19:00", "Mon 14:00"]
    assert [fmt(t) for t in h] == ["Fri 19:00", "Mon 19:00", "Tue 14:00"] and [fmt(t) for t in j] == ["Thu 19:00", "Sun 19:00", "Mon 14:00"]
    assert r27["n_weekends"] == 20 and r27["events"] == 94 and abs(r27["mean"] - 143.91) < 0.01 and abs(r25["mean"] - 167.63) < 0.01
    print("SELFCHECK OK")
    sys.exit()

# ---- features
def logret(c, times):
    out = []
    for a, b in zip(times, times[1:]):
        if a in c and b in c and c[a] > 0 and c[b] > 0: out.append(math.log(c[b] / c[a]))
        else: out.append(None)
    return out
def corr(x, y):
    pairs = [(a, b) for a, b in zip(x, y) if a is not None and b is not None]
    if len(pairs) < 30: return None
    xs, ys = [p[0] for p in pairs], [p[1] for p in pairs]
    mx, my = st.mean(xs), st.mean(ys)
    sx = math.sqrt(sum((a - mx) ** 2 for a in xs)); sy = math.sqrt(sum((b - my) ** 2 for b in ys))
    if sx == 0 or sy == 0: return None
    return sum((a - mx) * (b - my) for a, b in pairs) / (sx * sy)
def features(sym, fri):
    c = close.get(sym, {}); q = qvol.get(sym, {})
    t0 = ts(fri.year, fri.month, fri.day, 20)
    wk_times = [t0 + i * H for i in range(48)]            # Fri 20:00 .. Sun 19:00
    mon = fri - timedelta(days=4); t1 = ts(mon.year, mon.month, mon.day, 0)
    wd_times = [t1 + i * H for i in range(116)]           # Mon 00:00 .. Fri 19:00
    wk_have = [t for t in wk_times if t in c]; wd_have = [t for t in wd_times if t in c]
    if len(wk_have) < 40 or len(wd_have) < 100: return None
    rw = logret(c, wk_times); rb = logret(btc, wk_times); rd = logret(c, wd_times)
    f1 = corr(rw, rb)
    wkv = [q[t] for t in wk_have]; wdv = [q[t] for t in wd_have]
    f2 = (st.mean(wkv) / st.mean(wdv)) if st.mean(wdv) > 0 else None
    rw_ = [r for r in rw if r is not None]; rd_ = [r for r in rd if r is not None]
    f3 = (st.stdev(rw_) / st.stdev(rd_)) if len(rw_) > 5 and len(rd_) > 5 and st.stdev(rd_) > 0 else None
    return {"F1": f1, "F2": f2, "F3": f3}
def per_name(symbols, fridays, min_weekends=3):
    out = {}
    for s in symbols:
        rows = [features(s, f) for f in fridays]; rows = [r for r in rows if r]
        if len(rows) < min_weekends: out[s] = {"n": len(rows)}; continue
        out[s] = {"n": len(rows)}
        for k in ("F1", "F2", "F3"):
            vals = [r[k] for r in rows if r[k] is not None]
            out[s][k] = st.median(vals) if vals else None
    return out
def auc(pos, neg):
    pos = [v for v in pos if v is not None]; neg = [v for v in neg if v is not None]
    if not pos or not neg: return None
    s = 0.0
    for p in pos:
        for q in neg: s += 1 if p > q else (0.5 if p == q else 0)
    return s / (len(pos) * len(neg))
def welch(a, b):
    if len(a) < 2 or len(b) < 2: return None, None
    ma, mb = st.mean(a), st.mean(b); va, vb = st.variance(a) / len(a), st.variance(b) / len(b)
    if va + vb == 0: return None, None
    t = (ma - mb) / math.sqrt(va + vb)
    df = (va + vb) ** 2 / (va ** 2 / (len(a) - 1) + vb ** 2 / (len(b) - 1))
    return t, df
def betainc(a, b, x):   # regularized incomplete beta (Numerical Recipes continued fraction)
    if x <= 0: return 0.0
    if x >= 1: return 1.0
    lbeta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1 - x)
    if x > (a + 1) / (a + b + 2): return 1 - betainc(b, a, 1 - x)
    c, d = 1.0, 1 - (a + b) * x / (a + 1); d = 1 / d if abs(d) > 1e-30 else 1e30; h = d
    for m in range(1, 300):
        m2 = 2 * m
        aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2)); d = 1 + aa * d; d = 1 / (d if abs(d) > 1e-30 else 1e-30); c = 1 + aa / (c if abs(c) > 1e-30 else 1e-30); h *= d * c
        aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1)); d = 1 + aa * d; d = 1 / (d if abs(d) > 1e-30 else 1e-30); c = 1 + aa / (c if abs(c) > 1e-30 else 1e-30); de = d * c; h *= de
        if abs(de - 1) < 1e-12: break
    return math.exp(lbeta) * h / a
def t_p_two_sided(t, df):
    x = df / (df + t * t); return betainc(df / 2, 0.5, x)


if MODE == "shadow":
    syms = sys.argv[2].split(","); fri = datetime.strptime(sys.argv[3], "%Y-%m-%d").date()
    now_utc = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    sealed = open(os.path.join(REPO, "logs", "a7_shadow_sealed.jsonl"), "a")
    for sym in syms:
        o = outcome(sym, fri, True); f = features(sym, fri)
        if o is None: print(f"| {fri} | {sym} | bars missing | — | — | — | {now_utc} |"); continue
        print(f"| {fri} | {sym} | yes | {o['entry_qvol']:,.0f} | {'yes' if o['wknd_bp'] <= -50 else 'no'} | {f['F1']:+.3f} | {now_utc} |" if f else f"| {fri} | {sym} | partial | {o['entry_qvol']:,.0f} | {'yes' if o['wknd_bp'] <= -50 else 'no'} | — | {now_utc} |")
        sealed.write(json.dumps({"friday": str(fri), "symbol": sym, "recorded_utc": now_utc, **o}) + "\n")
    sys.exit()

if MODE == "diagnostics":
    R = json.load(open(os.path.join(REPO, "data", "fade-a7-features-2026-09-09.json"))); pm, pe = R["per_name_measured"], R["per_name_e1"]
    THR = 0.191
    FR = fridays_between(datetime(2026, 4, 3).date(), datetime(2026, 8, 28).date(), HOL)
    FR_A6 = fridays_between(datetime(2025, 12, 12).date(), datetime(2026, 8, 21).date(), HOL)
    def show(lbl, r): print(f"  {lbl:28} weekends {r['n_weekends']:2} events {r['events']:3} mean {r['mean']:+7.1f} t {r['t'] if r['t'] is None else round(r['t'],2)}")
    print("=== T2b(i): E1 names split at the operative threshold 0.191 (Apr 10 - Aug 28) ===")
    e_hi = [s for s in pe if pe[s].get("F1") is not None and pe[s]["F1"] >= THR]; e_lo = [s for s in pe if pe[s].get("F1") is not None and pe[s]["F1"] < THR]
    rh, rl = declustered(e_hi, FR), declustered(e_lo, FR); show(f"E1 F1>=0.191 ({len(e_hi)} names)", rh); show(f"E1 F1<0.191 ({len(e_lo)} names)", rl)
    t, df = welch(rh["series"], rl["series"]); p2 = t_p_two_sided(t, df); print(f"  gap {rh['mean']-rl['mean']:+.1f} bp  Welch t {t:.2f} df {df:.1f}  two-sided p {p2:.3f}  one-sided p {p2/2 if t>0 else 1-p2/2:.3f}")
    print("=== T2b(ii): cohort A (measured 25) split at 0.191 on the A6 sample (Dec 12 - Aug 21) ===")
    a_hi = [s for s in pm if pm[s]["F1"] >= THR]; a_lo = [s for s in pm if pm[s]["F1"] < THR]
    rh, rl = declustered(a_hi, FR_A6), declustered(a_lo, FR_A6); show(f"A F1>=0.191 ({len(a_hi)} names)", rh); show(f"A F1<0.191 ({len(a_lo)} names)", rl)
    t, df = welch(rh["series"], rl["series"]); p2 = t_p_two_sided(t, df); print(f"  gap {rh['mean']-rl['mean']:+.1f} bp  Welch t {t:.2f} df {df:.1f}  two-sided p {p2:.3f}")
    print("  A above:", [s[:-4] for s in a_hi]); print("  A below:", [s[:-4] for s in a_lo])

    print("\n=== Diagnostics (post-hoc, requested by critics) ===")
    ALL = MEASURED25 + E1
    # per-weekend feature rows
    rows = {}
    for s in ALL:
        for f in FR:
            ft = features(s, f)
            if ft and ft["F1"] is not None: rows[(s, f)] = ft
    # weekday-hours F1 and absolute weekend volume per name
    def wd_f1(sym, fri):
        c = close.get(sym, {}); mon = fri - timedelta(days=4); t1 = ts(mon.year, mon.month, mon.day, 0)
        times = [t1 + i * H for i in range(116)]
        if len([t for t in times if t in c]) < 100: return None
        return corr(logret(c, times), logret(btc, times))
    def abs_wk_vol(sym, fri):
        q = qvol.get(sym, {}); t0 = ts(fri.year, fri.month, fri.day, 20); times = [t0 + i * H for i in range(48)]
        have = [q[t] for t in times if t in q]
        return st.mean(have) if len(have) >= 40 else None
    def per_name_fn(fn, syms):
        out = {}
        for s in syms:
            v = [fn(s, f) for f in FR]; v = [x for x in v if x is not None]
            if len(v) >= 3: out[s] = st.median(v)
        return out
    wdF1 = per_name_fn(wd_f1, ALL); vol = per_name_fn(abs_wk_vol, ALL)
    print(f"  AUC measured vs E1 — F1 weekday hours: {auc([wdF1[s] for s in MEASURED25 if s in wdF1], [wdF1[s] for s in E1 if s in wdF1]):.3f}  (medians {st.median([wdF1[s] for s in MEASURED25 if s in wdF1]):.3f} vs {st.median([wdF1[s] for s in E1 if s in wdF1]):.3f})")
    print(f"  AUC measured vs E1 — abs weekend quote vol/h: {auc([vol[s] for s in MEASURED25 if s in vol], [vol[s] for s in E1 if s in vol]):.3f}  (medians ${st.median([vol[s] for s in MEASURED25 if s in vol]):,.0f} vs ${st.median([vol[s] for s in E1 if s in vol]):,.0f})")
    # per-weekend cross-sectional median F1 and share above threshold
    print("  per-weekend cross-sectional F1 (all names with a value): median / share >= 0.191")
    xs = {}
    for f in FR:
        v = [rows[(s, f)]["F1"] for s in ALL if (s, f) in rows]
        if v: xs[f] = (st.median(v), sum(1 for x in v if x >= THR) / len(v), len(v))
    print("   ", "  ".join(f"{f.strftime('%m-%d')}:{m:.2f}/{sh:.0%}" for f, (m, sh, n) in xs.items()))
    print(f"  within-name weekly SD of F1 (median over names with >=5 weekends): {st.median([st.stdev([rows[(s,f)]['F1'] for f in FR if (s,f) in rows]) for s in ALL if len([1 for f in FR if (s,f) in rows]) >= 5]):.3f}")
    # split-half agreement at 0.191: first 4 weekends vs rest, names with >= 8 weekends
    agree = tot = 0
    for s in ALL:
        fs = [f for f in FR if (s, f) in rows]
        if len(fs) < 8: continue
        a = st.median([rows[(s, f)]["F1"] for f in fs[:4]]); b = st.median([rows[(s, f)]["F1"] for f in fs[4:]])
        tot += 1; agree += ((a >= THR) == (b >= THR))
    print(f"  split-half (first 4 vs rest) eligibility agreement at 0.191: {agree}/{tot} = {agree/tot:.0%}")
    # RDDT rank among measured-25 on its weekends
    FR3 = fridays_between(datetime(2026, 8, 7).date(), datetime(2026, 9, 4).date(), set())
    print("  RDDT per-weekend F1 and its percentile rank among the measured-25 on the same weekend:")
    for f in FR3:
        r = features("RDDTUSDT", f)
        if not r or r["F1"] is None: continue
        peers = [features(s, f) for s in MEASURED25]; peers = [p["F1"] for p in peers if p and p["F1"] is not None]
        rank = sum(1 for p in peers if p < r["F1"]) / len(peers)
        print(f"    {f}: F1 {r['F1']:+.3f}  rank {rank:.2f} of {len(peers)}  (measured median that weekend {st.median(peers):.3f})")
    # within-E1 terciles by absolute weekend volume
    ranked = sorted([s for s in E1 if s in vol], key=lambda s: vol[s]); n = len(ranked)
    for lbl, grp in zip(("vol bottom", "vol middle", "vol top"), (ranked[:n//3], ranked[n//3:2*n//3], ranked[2*n//3:])): show(f"E1 {lbl}", declustered(grp, FR))
    # rank-normalised F1 within E1 -> outcome terciles (post-hoc)
    rk = {}
    for s in E1:
        vals = []
        for f in FR:
            if (s, f) not in rows: continue
            peers = [rows[(m, f)]["F1"] for m in MEASURED25 if (m, f) in rows]
            vals.append(sum(1 for p in peers if p < rows[(s, f)]["F1"]) / len(peers))
        if len(vals) >= 3: rk[s] = st.median(vals)
    ranked = sorted(rk, key=lambda s: rk[s]); n = len(ranked)
    for lbl, grp in zip(("rankF1 bottom", "rankF1 middle", "rankF1 top"), (ranked[:n//3], ranked[n//3:2*n//3], ranked[2*n//3:])): show(f"E1 {lbl}", declustered(grp, FR))
    # RDDT decision bars
    print("  RDDT Sunday 19:00 UTC decision-bar quote volume:", {f.strftime('%m-%d'): round(qvol['RDDTUSDT'].get(ts((f+timedelta(days=2)).year,(f+timedelta(days=2)).month,(f+timedelta(days=2)).day,19), float('nan'))) for f in FR3})
    # disaster placement
    for s in ("LITEUSDT","APPUSDT","BOTUSDT","GOOGLUSDT"): print(f"  {s[:-4]} F1 {pe[s]['F1']:.3f}")
    sys.exit()

FR = fridays_between(datetime(2026, 4, 3).date(), datetime(2026, 8, 28).date(), HOL)
print("sample fridays:", [f.isoformat() for f in FR])
pm = per_name(MEASURED25, FR); pe = per_name(E1, FR)
e1_ok = [s for s in E1 if "F1" in pe[s]]; m_ok = [s for s in MEASURED25 if "F1" in pm[s]]
print(f"\nT1 — measured25 with >=3 weekends: {len(m_ok)}; E1 names with >=3 weekends: {len(e1_ok)} (of {len(E1)})")
T1 = {}
for k in ("F1", "F2", "F3"):
    mv = [pm[s][k] for s in m_ok]; ev = [pe[s][k] for s in e1_ok]
    T1[k] = {"measured_median": st.median([v for v in mv if v is not None]), "e1_median": st.median([v for v in ev if v is not None]), "auc": auc(mv, ev),
             "measured_p25": sorted(v for v in mv if v is not None)[len([v for v in mv if v is not None]) // 4], "e1_p75": sorted(v for v in ev if v is not None)[3 * len([v for v in ev if v is not None]) // 4]}
    print(f"  {k}: measured median {T1[k]['measured_median']:.3f}  E1 median {T1[k]['e1_median']:.3f}  AUC {T1[k]['auc']:.3f}  | thresholds: measured p25 {T1[k]['measured_p25']:.3f}, E1 p75 {T1[k]['e1_p75']:.3f}")

print("\nE1 extended (all E1 names, Apr 10 - Aug 28):", {k: (round(v, 1) if isinstance(v, float) else v) for k, v in declustered(e1_ok, FR).items() if k != "series"})
print("measured25 same period:", {k: (round(v, 1) if isinstance(v, float) else v) for k, v in declustered(MEASURED25, FR).items() if k != "series"})

print("\nT2 — E1 terciles by feature (frozen event definition, Apr 10 - Aug 28)")
T2 = {}
for k in ("F1", "F2", "F3"):
    ranked = sorted([s for s in e1_ok if pe[s][k] is not None], key=lambda s: pe[s][k])
    n = len(ranked); terc = [ranked[: n // 3], ranked[n // 3: 2 * n // 3], ranked[2 * n // 3:]]
    res = [declustered(t, FR) for t in terc]
    t_stat, df = welch(res[2]["series"], res[0]["series"])
    p = t_p_two_sided(t_stat, df) if t_stat is not None else None
    T2[k] = {"bottom": res[0], "middle": res[1], "top": res[2], "top_minus_bottom_t": t_stat, "df": df, "p": p,
             "cut_low": pe[terc[0][-1]][k], "cut_high": pe[terc[2][0]][k], "top_names": terc[2], "bottom_names": terc[0]}
    for lab, r in zip(("bottom", "middle", "top"), res):
        print(f"  {k} {lab:6}: weekends {r['n_weekends']:2} events {r['events']:3} mean {r['mean']:+7.1f} median {r.get('median', float('nan')):+7.1f} t {r['t'] if r['t'] is None else round(r['t'], 2)} worst {r.get('worst')}")
    print(f"  {k} top-bottom Welch t {t_stat:.2f} df {df:.1f} p {p:.3f}  | tercile cuts {T2[k]['cut_low']:.3f} / {T2[k]['cut_high']:.3f}")
ps = sorted([(T2[k]["p"], k) for k in T2 if T2[k]["p"] is not None])
holm = {}
for i, (p, k) in enumerate(ps): holm[k] = min(1.0, p * (3 - i))
print("  Holm-adjusted p:", {k: round(v, 3) for k, v in holm.items()})

print("\nT3 — newcomers (features only; weekends Aug 7 .. Sep 4, Sep 4 included for features and labelled)")
FR3 = fridays_between(datetime(2026, 8, 7).date(), datetime(2026, 9, 4).date(), set())
pn = per_name(NEWCOMERS + LATER_LISTINGS, FR3, min_weekends=1)
for s in NEWCOMERS + LATER_LISTINGS:
    r = pn[s]; print(f"  {s:10} weekends {r['n']}  " + "  ".join(f"{k} {r[k]:.3f}" if r.get(k) is not None else f"{k} -" for k in ("F1", "F2", "F3")))
json.dump({"T1": T1, "T2": {k: {kk: vv for kk, vv in v.items() if kk not in ('bottom','middle','top')} | {"tercile_stats": {lab: {kk: vv for kk, vv in v[lab].items() if kk != 'series'} for lab in ('bottom','middle','top')}} for k, v in T2.items()}, "holm": holm,
           "per_name_measured": pm, "per_name_e1": pe, "per_name_new": pn, "fridays": [f.isoformat() for f in FR]}, open(os.path.join(REPO, "logs", "a7_results.json"), "w"), indent=1, default=str)
print("\nwritten logs/a7_results.json")
