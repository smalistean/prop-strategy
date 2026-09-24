#!/usr/bin/env python3
"""Publish the friends page for the weekend fade: rebuild ledger.json, then upload the page to S3.

  python3 scripts/fade-publish.py           dry run: rebuilds site/weekend-fade/ledger.json, prints what changed, uploads nothing
  python3 scripts/fade-publish.py --live    also uploads index.html and ledger.json to the bucket

Run after each Monday exit, once the journal outcome row is written. Sources, in order of authority:

  site/weekend-fade/ledger.json      frozen blocks -- the 20 backtest weekends, the capacity curve, the backtest headline.
                                     Never recomputed here; they belong to the pre-registration.
  research/fade_live_ledger.jsonl    one row per LIVE weekend: friday, entry_utc, exit_utc, names, names_list, net_bp (the
                                     spec number, from bar closes), own_notional_usd, own_equity_usd (whole own-capital book at entry), optional note, optional exclude_symbols
                                     (legs taken off-spec: kept OUT of the dollar figure and disclosed in the note).
  Binance /fapi/v1/income            own_realized_usd per live weekend = REALIZED_PNL + FUNDING_FEE over the equity perps in
                                     [entry_utc, exit_utc + 1h), minus exclude_symbols. Needs the read-only key:
                                     set -a && source .env.binance && set +a. Without it, each row's stored value is kept.

Page opens are counted from the bucket's access logs by scripts/fade-page-stats.py.
The prop account has no API and is not on the page. Nothing here places an order or moves money.
"""
import hashlib, hmac, json, os, re, subprocess, sys, time, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

REPO   = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE   = os.path.join(REPO, "site", "weekend-fade")
LEDGER = os.path.join(SITE, "ledger.json")
LIVE   = os.path.join(REPO, "research", "fade_live_ledger.jsonl")
BUCKET = "weekend-fade-499218605756"
REGION = "eu-central-1"
URL    = f"http://{BUCKET}.s3-website.{REGION}.amazonaws.com"
EQUITY = {t + "USDT" for t in ("SPY QQQ EWJ EWY COIN TSLA MSTR PLTR HOOD AAPL AMZN META INTC MU CRCL "
                               "LLY JPM QCOM TSM PAYP SNDK AAOI AXTI NOK NVDA").split()}
LIVE_FLAG = "--live" in sys.argv

def ts(s): return datetime.strptime(s, "%Y-%m-%dT%H:%MZ").replace(tzinfo=timezone.utc)

def binance_income(start, end):
    """Signed read of /fapi/v1/income for [start, end). Returns [] if no key is in the environment."""
    k, s = os.environ.get("BINANCE_API_KEY"), os.environ.get("BINANCE_SECRET_KEY")
    if not (k and s): return None
    p = {"startTime": int(start.timestamp() * 1000), "endTime": int(end.timestamp() * 1000), "limit": 1000,
         "timestamp": int(time.time() * 1000), "recvWindow": 5000}
    q = urllib.parse.urlencode(p); sig = hmac.new(s.encode(), q.encode(), hashlib.sha256).hexdigest()
    r = urllib.request.Request(f"https://fapi.binance.com/fapi/v1/income?{q}&signature={sig}", headers={"X-MBX-APIKEY": k})
    return json.load(urllib.request.urlopen(r, timeout=20))

def realized(row):
    inc = binance_income(ts(row["entry_utc"]), ts(row["exit_utc"]) + timedelta(hours=1))
    if inc is None: return None
    skip = set(row.get("exclude_symbols", []))
    return round(sum(float(i["income"]) for i in inc
                     if i.get("symbol") in EQUITY and i["symbol"] not in skip
                     and i["incomeType"] in ("REALIZED_PNL", "FUNDING_FEE")), 2)

def deployed(n, equity, h):
    """Own-capital sizing rule (Plan S): basket = basket_fraction * equity split equally, per-name <= per_name_cap_fraction * equity."""
    return min(h["basket_fraction"] * equity, n * h["per_name_cap_fraction"] * equity)

def main():
    doc = json.load(open(LEDGER))
    h = doc["headline"]
    E = h.setdefault("reference_equity_usd", 5000)        # the hypothetical account the backtest is shown on
    h.setdefault("basket_fraction", 0.75); h.setdefault("per_name_cap_fraction", 0.20)
    h.setdefault("liquidity_fill_fraction", 0.87)   # share of the allocation that fills at $5k (GATE_SECOND_VENUE_STUDY capacity table)
    frozen = [w for w in doc["weekends"] if w["phase"] == "backtest"]
    for w in frozen:                                       # money the rule would have made on E, from the spec bp
        w["deployed_usd"] = round(deployed(w["names"], E, h), 2)
        w["usd"] = round(w["deployed_usd"] * w["net_bp"] / 1e4, 2)
    bt = [w["usd"] for w in frozen]
    h.update({"backtest_total_usd": round(sum(bt), 2), "backtest_mean_usd": round(sum(bt) / len(bt), 2),
              "backtest_worst_usd": round(min(bt), 2), "backtest_best_usd": round(max(bt), 2)})
    live = []
    for ln in open(LIVE):
        ln = ln.strip()
        if not ln: continue
        r = json.loads(ln)
        stored = next((w for w in doc["weekends"] if w["phase"] == "live" and w["friday"] == r["friday"]), {})
        usd = realized(r)
        src = "binance"
        if usd is None:
            usd = stored.get("own_realized_usd"); src = "stored" if usd is not None else "MISSING"
        w = {"friday": r["friday"], "phase": "live", "names": r["names"], "net_bp": r["net_bp"],
             "own_notional_usd": r["own_notional_usd"], "own_equity_usd": r["own_equity_usd"], "own_realized_usd": usd,
             "usd": usd, "rule_deployed_usd": round(deployed(r["names"], r["own_equity_usd"], h), 2)}
        for k in ("names_list", "note"):
            if k in r: w[k] = r[k]
        live.append(w)
        flag = "" if stored.get("own_realized_usd") == usd else "  <- changed"
        print(f"  {r['friday']}  {r['names']:>2} names  {r['net_bp']:>+7.1f} bp  own {usd if usd is not None else '?':>8} ({src}){flag}")
    if any(w["own_realized_usd"] is None for w in live):
        print("a live weekend has no dollar figure and no stored value; source the Binance key or fill the row"); sys.exit(1)
    doc["weekends"] = frozen + live
    won = sum(1 for w in live if w["net_bp"] > 0)
    doc["headline"].update({"live_weekends": len(live), "live_won": won, "live_lost": len(live) - won})
    doc["as_of_utc"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ")
    json.dump(doc, open(LEDGER, "w"), indent=1)
    # keep the page's inline fallback identical to ledger.json, so a fetch failure never shows stale numbers
    page = os.path.join(SITE, "index.html"); html = open(page, encoding="ascii").read()
    blob = json.dumps(doc, separators=(",", ":"), ensure_ascii=True)
    html2 = re.sub(r'(<script type="application/json" id="fallback">\n).*?(\n</script>)', lambda m: m.group(1) + blob + m.group(2), html, count=1, flags=re.S)
    assert html2 != html or blob in html, "fallback block not found in index.html"
    open(page, "w", encoding="ascii").write(html2)
    print(f"\nbacktest on ${E:,}: total {h['backtest_total_usd']:+.2f}, mean {h['backtest_mean_usd']:+.2f}/weekend "
          f"({h['backtest_mean_usd']/E*100:+.2f}% of the account), worst {h['backtest_worst_usd']:+.2f}, best {h['backtest_best_usd']:+.2f}")
    print(f"ledger.json rebuilt: {len(frozen)} backtest + {len(live)} live weekends, as of {doc['as_of_utc']}")
    if not LIVE_FLAG:
        print(f"dry run - nothing uploaded. Add --live to publish to {URL}"); return
    for name, ctype in (("index.html", "text/html; charset=utf-8"), ("ledger.json", "application/json; charset=utf-8")):
        subprocess.run(["aws", "s3", "cp", os.path.join(SITE, name), f"s3://{BUCKET}/{name}", "--region", REGION,
                        "--content-type", ctype, "--cache-control", "no-cache", "--only-show-errors"], check=True)
        print(f"  uploaded {name}")
    print(f"live at {URL}")

if __name__ == "__main__":
    main()
