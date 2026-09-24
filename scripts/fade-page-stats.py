#!/usr/bin/env python3
"""How many times the friends page (weekend fade) was opened, from S3 server access logs.

  python3 scripts/fade-page-stats.py              sync the logs, print opens / visitors / previews per UTC day
  python3 scripts/fade-page-stats.py --no-sync    use the logs already under logs/fade-page/
  python3 scripts/fade-page-stats.py --selfcheck  parse built-in sample lines and assert the counts

Logging was switched on 2026-09-23 14:18 UTC: site bucket weekend-fade-499218605756 -> private bucket
weekend-fade-logs-499218605756, prefix site/, date-partitioned, deleted after 90 days (the lines hold visitors' IP
addresses). Nothing before that moment was recorded. AWS delivers these logs on a best-effort basis, usually within
a few hours; a small share of records can be late or missing.

Definitions (only website GETs with status 200/304 count):
  open      a GET of ledger.json. The page fetches it with caching disabled every time it runs in a browser, so it counts
            browsers that executed the page. Link previews (Telegram, WhatsApp, ...) fetch only the HTML and are not opens.
  visitor   a distinct (IP address, user agent) pair among opens. Approximate: one household shares an IP, phones change IPs.
  preview   an HTML fetch not followed within 10 minutes by a ledger.json fetch from the same pair; grouped by user agent.
  by IP     one row per IP address: opens, previews, first and last request (UTC), and the devices seen from it.
Excluded: this machine's current public IP, any IP in FADE_STATS_EXCLUDE_IPS (comma list), and command-line tools
(curl, wget, python, aws-cli, Go, and the fade-stats-delivery-test agent used to test logging). Excluded requests are
printed per IP as requests and page opens (one open = the page + its ledger.json, two requests), never silently dropped.
"""
import collections, os, re, subprocess, sys, urllib.request
from datetime import datetime, timedelta, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL = os.path.join(REPO, "logs", "fade-page")
LOG_BUCKET, PREFIX, REGION = "weekend-fade-logs-499218605756", "site/", "eu-central-1"
TOKEN = re.compile(r'\[[^\]]*\]|"[^"]*"|\S+')
TOOLS = re.compile(r"curl|wget|python|aws-cli|aws-sdk|go-http-client|okhttp|httpie|libwww|fade-stats-delivery-test", re.I)
BOTS = [("Apple iMessage", r"Facebot Twitterbot|Applebot"), ("Telegram", r"TelegramBot"), ("WhatsApp", r"WhatsApp"),
        ("Facebook/Messenger", r"facebookexternalhit|facebookcatalog"),
        ("X/Twitter", r"Twitterbot"), ("Slack", r"Slackbot|Slack-ImgProxy"), ("Discord", r"Discordbot"),
        ("Viber", r"Viber"), ("LinkedIn", r"LinkedInBot"), ("Signal", r"Signal"),
        ("Google", r"Googlebot|Google-InspectionTool|GoogleOther"), ("Bing", r"bingbot"), ("other bot", r"bot|crawl|spider|preview|fetch")]
PREVIEW_WINDOW = timedelta(minutes=10)

def parse(line):
    t = TOKEN.findall(line)
    if len(t) < 17: return None
    try: ts = datetime.strptime(t[2].strip("[]"), "%d/%b/%Y:%H:%M:%S %z").astimezone(timezone.utc)
    except ValueError: return None
    return {"ts": ts, "ip": t[3], "op": t[6], "key": t[7], "status": t[9], "referer": t[15].strip('"'), "ua": t[16].strip('"')}

def device(ua):
    """Short label from a user agent: platform + browser, or the preview app's name."""
    fam = bot_family(ua)
    if not fam.startswith("browser"): return fam + " preview"
    plat = next((n for n, rx in (("iPhone", "iPhone"), ("iPad", "iPad"), ("Android", "Android"), ("Mac", "Macintosh"),
                                 ("Windows", "Windows"), ("Linux", "Linux")) if rx in ua), "?")
    app = next((n for n, rx in (("Telegram in-app", r"Telegram"), ("Instagram in-app", r"Instagram"), ("Facebook in-app", r"FBAN|FBAV"),
                                ("Edge", r"Edg/"), ("Opera", r"OPR/"), ("Firefox", r"Firefox|FxiOS"), ("Chrome", r"Chrome|CriOS"),
                                ("Safari", r"Safari")) if re.search(rx, ua)), "browser")
    return f"{plat} {app}"

def bot_family(ua):
    for name, rx in BOTS:
        if re.search(rx, ua, re.I): return name
    return "browser, page not run (closed early or no JavaScript)"

def own_ips():
    ips = {x.strip() for x in os.environ.get("FADE_STATS_EXCLUDE_IPS", "").split(",") if x.strip()}
    try: ips.add(urllib.request.urlopen("https://checkip.amazonaws.com", timeout=10).read().decode().strip())
    except Exception: print("warning: could not read this machine's public IP; it is not excluded")
    return ips

def read_local():
    rows = []
    for root, _, files in os.walk(LOCAL):
        for f in files:
            with open(os.path.join(root, f), encoding="utf-8", errors="replace") as fh:
                rows += [r for r in map(parse, fh) if r]
    return rows

def report(rows, exclude):
    kept = []
    dropped = collections.defaultdict(lambda: [0, 0])                                   # reason -> [requests, page opens]
    dropped_ip = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))  # ip -> reason -> [requests, page opens]
    for r in rows:
        if r["op"] != "WEBSITE.GET.OBJECT" or r["status"] not in ("200", "304"): continue
        if r["key"] not in ("index.html", "ledger.json"): continue
        reason = "tool" if TOOLS.search(r["ua"]) else "own IP" if r["ip"] in exclude else None
        if reason:
            is_open = int(r["key"] == "ledger.json")
            for c in (dropped[reason], dropped_ip[r["ip"]][reason]): c[0] += 1; c[1] += is_open
            continue
        kept.append(r)
    kept.sort(key=lambda r: r["ts"])
    opens = [r for r in kept if r["key"] == "ledger.json"]
    html = [r for r in kept if r["key"] == "index.html"]
    ledger_by_pair = collections.defaultdict(list)
    for r in opens: ledger_by_pair[(r["ip"], r["ua"])].append(r["ts"])
    previews = [r for r in html if not any(r["ts"] <= t <= r["ts"] + PREVIEW_WINDOW for t in ledger_by_pair[(r["ip"], r["ua"])])]
    days = sorted({r["ts"].date() for r in kept})
    out = {"opens": len(opens), "visitors": len({(r["ip"], r["ua"]) for r in opens}), "previews": len(previews),
           "dropped": {k: tuple(v) for k, v in dropped.items()}, "days": {}}
    for d in days:
        o = [r for r in opens if r["ts"].date() == d]; p = [r for r in previews if r["ts"].date() == d]
        out["days"][str(d)] = (len(o), len({(r["ip"], r["ua"]) for r in o}), len(p))
    out["preview_families"] = collections.Counter(bot_family(r["ua"]) for r in previews)
    out["referers"] = collections.Counter(r["referer"] for r in html if r["referer"] not in ("-", ""))
    by_ip = {}
    for kind, rs in (("opens", opens), ("previews", previews)):
        for r in rs:
            e = by_ip.setdefault(r["ip"], {"opens": 0, "previews": 0, "first": r["ts"], "last": r["ts"], "devices": collections.Counter()})
            e[kind] += 1; e["first"] = min(e["first"], r["ts"]); e["last"] = max(e["last"], r["ts"]); e["devices"][device(r["ua"])] += 1
    out["by_ip"] = dict(sorted(by_ip.items(), key=lambda kv: (-kv[1]["opens"], -kv[1]["previews"], kv[1]["first"])))
    out["excluded_by_ip"] = {ip: {k: tuple(v) for k, v in c.items()} for ip, c in dropped_ip.items()}
    return out

def describe(reason, v):
    req, opens = v
    words = f"{req} request{'s' if req != 1 else ''}"
    if reason == "own IP": return f"own IP, {words}, {opens} page open{'s' if opens != 1 else ''}"
    return f"test/command-line tool, {words}"

def show(out, span):
    print(f"\nfriends page, {span}")
    print(f"  opens {out['opens']}   visitors {out['visitors']}   link previews / HTML-only fetches {out['previews']}")
    if out["dropped"]: print("  excluded: " + "; ".join(describe(k, v) for k, v in out["dropped"].items()))
    if out["days"]:
        print(f"\n  {'UTC day':<12}{'opens':>7}{'visitors':>10}{'previews':>10}")
        for d, (o, v, p) in out["days"].items(): print(f"  {d:<12}{o:>7}{v:>10}{p:>10}")
    if out["preview_families"]:
        print("\n  previews by source: " + ", ".join(f"{k} {v}" for k, v in out["preview_families"].most_common()))
    if out["referers"]:
        print("  referers of the HTML: " + ", ".join(f"{k} {v}" for k, v in out["referers"].most_common(8)))
    if out["by_ip"]:
        print(f"\n  {'IP address':<40}{'opens':>6}{'previews':>10}   {'first (UTC)':<17}{'last (UTC)':<17}devices")
        for ip, e in out["by_ip"].items():
            dev = ", ".join(f"{d} x{n}" if n > 1 else d for d, n in e["devices"].most_common())
            print(f"  {ip:<40}{e['opens']:>6}{e['previews']:>10}   {e['first']:%m-%d %H:%M}      {e['last']:%m-%d %H:%M}      {dev}")
    if out["excluded_by_ip"]:
        print("\n  excluded, not counted above:")
        for ip, c in out["excluded_by_ip"].items():
            print(f"    {ip}: " + "; ".join(describe(k, v) for k, v in c.items()))

SAMPLE = [
 # a friend on a phone: HTML then ledger.json 1 s later
 'o weekend-fade-499218605756 [24/Sep/2026:08:00:01 +0000] 203.0.113.7 - A1 WEBSITE.GET.OBJECT index.html "GET / HTTP/1.1" 200 - 90000 90000 20 19 "-" "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1" - h1 - - - host - - -',
 'o weekend-fade-499218605756 [24/Sep/2026:08:00:02 +0000] 203.0.113.7 - A2 WEBSITE.GET.OBJECT ledger.json "GET /ledger.json HTTP/1.1" 200 - 9000 9000 10 9 "http://x/" "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1" - h2 - - - host - - -',
 # the same friend reopens later
 'o weekend-fade-499218605756 [24/Sep/2026:20:00:02 +0000] 203.0.113.7 - A3 WEBSITE.GET.OBJECT ledger.json "GET /ledger.json HTTP/1.1" 200 - 9000 9000 10 9 "http://x/" "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1" - h3 - - - host - - -',
 # Telegram and iMessage link previews
 'o weekend-fade-499218605756 [24/Sep/2026:08:11:00 +0000] 17.58.1.1 - A9 WEBSITE.GET.OBJECT index.html "GET / HTTP/1.1" 200 - 90000 90000 20 19 "-" "Mozilla/5.0 (Macintosh) facebookexternalhit/1.1 Facebot Twitterbot/1.0" - h9 - - - host - - -',
 'o weekend-fade-499218605756 [24/Sep/2026:08:10:00 +0000] 149.154.161.1 - A4 WEBSITE.GET.OBJECT index.html "GET / HTTP/1.1" 200 - 90000 90000 20 19 "-" "TelegramBot (like TwitterBot)" - h4 - - - host - - -',
 # curl check and own IP, both excluded; a 404 and an upload, both ignored
 'o weekend-fade-499218605756 [24/Sep/2026:09:00:00 +0000] 198.51.100.9 - A5 WEBSITE.GET.OBJECT index.html "GET / HTTP/1.1" 200 - 90000 90000 20 19 "-" "curl/8.7.1" - h5 - - - host - - -',
 'o weekend-fade-499218605756 [25/Sep/2026:09:00:00 +0000] 192.0.2.50 - A6 WEBSITE.GET.OBJECT ledger.json "GET /ledger.json HTTP/1.1" 200 - 9000 9000 10 9 "-" "Mozilla/5.0 (Macintosh) Chrome/140" - h6 - - - host - - -',
 'o weekend-fade-499218605756 [25/Sep/2026:09:01:00 +0000] 203.0.113.8 - A7 WEBSITE.GET.OBJECT favicon.ico "GET /favicon.ico HTTP/1.1" 404 NoSuchKey 300 - 5 - "-" "Mozilla/5.0" - h7 - - - host - - -',
 'o weekend-fade-499218605756 [25/Sep/2026:09:02:00 +0000] 192.0.2.50 arn:aws:iam::1:root A8 REST.PUT.OBJECT ledger.json "PUT /ledger.json HTTP/1.1" 200 - - 9000 50 20 "-" "aws-cli/2.0" - h8 SigV4 - - host TLSv1.3 - -',
]

if __name__ == "__main__":
    if "--selfcheck" in sys.argv:
        out = report([parse(l) for l in SAMPLE], {"192.0.2.50"})
        assert (out["opens"], out["visitors"], out["previews"]) == (2, 1, 2), out
        assert out["dropped"] == {"tool": (1, 0), "own IP": (1, 1)}, out["dropped"]
        assert out["preview_families"] == {"Telegram": 1, "Apple iMessage": 1}, out["preview_families"]
        assert out["days"] == {"2026-09-24": (2, 1, 2)}, out["days"]
        assert list(out["by_ip"]) == ["203.0.113.7", "149.154.161.1", "17.58.1.1"], list(out["by_ip"])
        e = out["by_ip"]["203.0.113.7"]; assert (e["opens"], e["previews"], dict(e["devices"])) == (2, 0, {"iPhone Safari": 2}), e
        assert out["by_ip"]["149.154.161.1"]["devices"] == {"Telegram preview": 1}, out["by_ip"]["149.154.161.1"]
        assert out["excluded_by_ip"] == {"198.51.100.9": {"tool": (1, 0)}, "192.0.2.50": {"own IP": (1, 1)}}, out["excluded_by_ip"]
        show(out, "selfcheck sample"); print("\nselfcheck passed"); sys.exit(0)
    if "--no-sync" not in sys.argv:
        os.makedirs(LOCAL, exist_ok=True)
        subprocess.run(["aws", "s3", "sync", f"s3://{LOG_BUCKET}/{PREFIX}", LOCAL, "--region", REGION, "--only-show-errors"], check=True)
    rows = read_local()
    if not rows:
        print(f"no log lines yet under {LOCAL} (logging on since 2026-09-23 14:18 UTC; delivery can take hours)"); sys.exit(0)
    span = f"{min(r['ts'] for r in rows):%Y-%m-%d %H:%M} to {max(r['ts'] for r in rows):%Y-%m-%d %H:%M} UTC, {len(rows)} log lines"
    show(report(rows, own_ips()), span)
