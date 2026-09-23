"""Shared constants and bar arithmetic, copied from scripts/analysis-sequential-test.py (declared A8 constants)."""
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import os, subprocess
FADE = "SPY QQQ EWJ EWY COIN TSLA MSTR PLTR HOOD AAPL AMZN META INTC MU CRCL LLY JPM QCOM TSM PAYP SNDK AAOI AXTI NOK".split()
SYM = {t: t + "USDT" for t in FADE}
TRIGGER_BP = -50.0
COST_BP = 9.0
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
    """anchor = last US session close bar (open), entry = 19:00 UTC bar the evening before the next session, exit = 10:00 NY bar."""
    a = fri
    while not is_us_trading_day(a): a -= timedelta(days=1)
    x = fri + timedelta(days=1)
    while not is_us_trading_day(x): x += timedelta(days=1)
    e = x - timedelta(days=1)
    return ny_bar(a, 12 if a in US_EARLY_CLOSES else 15), utc_bar(e, 19), ny_bar(x, 10)
def fridays(a=date(2026,1,30), b=date(2026,9,18)):
    out, d = [], a
    while d <= b: out.append(d); d += timedelta(days=7)
    return out
def psql(sql):
    r = subprocess.run(["psql", "-U", os.environ.get("DB_USER", "prop_strategy_app"), "-d", "prop_strategy", "-At", "-F|", "-c", sql],
                       capture_output=True, text=True, check=True)
    return [l.split("|") for l in r.stdout.splitlines() if l]
def load_binance(symbols=None):
    symbols = symbols or list(SYM.values()); q = "','".join(symbols)
    close, qvol, fund = {}, {}, {}
    for s, t, c, v in psql(f"""SELECT symbol, extract(epoch FROM open_time)::bigint, close_price, quote_asset_volume
                               FROM binance_perp_kline WHERE "interval"='1h' AND symbol IN ('{q}')"""):
        close.setdefault(s, {})[int(t)] = float(c); qvol.setdefault(s, {})[int(t)] = float(v)
    for s, t, r in psql(f"""SELECT DISTINCT symbol, extract(epoch FROM funding_time)::bigint, funding_rate
                            FROM binance_perp_funding_rate WHERE symbol IN ('{q}')"""):
        fund.setdefault(s, []).append((int(t), float(r)))
    return close, qvol, fund
def outcome(sym, fri, close, qvol, fund):
    a, e, x = bars_for(fri); c = close.get(sym, {})
    if a not in c or e not in c: return None
    dev = (c[e] / c[a] - 1) * 1e4
    o = {"anchor": a, "entry": e, "exit": x, "dev_bp": dev, "triggered": dev <= TRIGGER_BP,
         "entry_qvol": qvol.get(sym, {}).get(e), "entry_close": c[e]}
    if x in c and fund.get(sym):
        price = c[x] / c[e] - 1; fsum = sum(r for (t, r) in fund[sym] if e + H < t <= x + H)
        o.update({"price_bp": price * 1e4, "funding_bp": -fsum * 1e4, "net_bp": (price - fsum) * 1e4 - COST_BP, "exit_close": c[x]})
    return o
def is_shifted_local(f):
    return f in US_FULL_CLOSURES or (f + timedelta(days=3)) in US_FULL_CLOSURES or f in US_EARLY_CLOSES
