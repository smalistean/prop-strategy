# Weekend fade — live spec for the challenge account

**Filed:** 2026-08-30 14:05 UTC · **Author:** Claude, from the user's session decisions
**Measurement basis:** `WEEKEND_FADE_FUNDING_PREREGISTRATION.md` (**+143.9 bp/weekend all-27,
+167.6 excl. private names, t=1.86/2.11, n=20 weekends** — restated 2026-09-08 by amendment A6 after
three August weekends were found missing from the database; originally +147.5/+175.5, n=17; mirror short measured at −70 bp and
closed — LONG ONLY). This spec freezes the live rules so weekend decisions are mechanical.
Manual execution only — the firm requires it and the house rule is stricter (no automation
touches a live account).

## Universe (24 names)

All challenge-watchlist US equity/ETF perps with an exchange-listed underlying:

SPY, QQQ, EWJ, EWY, COIN, TSLA, MSTR, PLTR, HOOD, AAPL, AMZN, META, INTC, MU, CRCL, LLY,
JPM, QCOM, TSM, PAYP, SNDK, AAOI, AXTI, NOK.

**Name check — PAYP is PayPay Corporation (SoftBank's Japanese payments app; Nasdaq ADS since
2026-03-12), not PayPal (PYPL, which Bybit lists at ~$55).** Its underlying news runs on Tokyo time;
no other venue in the monitor lists it, so its cross-venue check is Binance mark-vs-index only.

**Excluded, mechanical reasons only:**
- SPCX, OPENAI — private companies, no listed stock, no Monday open to re-anchor (prereg
  amendment A2; any future private-company perp joins this exclusion automatically).
- SAMSUNG, SKHYNIX — KRX-listed, different market hours; would need their own study.
- NVDA — measured in the study but not on the challenge watchlist.
- Metals/energy (XAU, CL, …) — measured dead (t=0.36), Globex reopens Sunday evening.
- **The other ~110 Binance equity/ETF perps (GOOGL, MSFT, IWM, GDX, XLE, …) — measured, no
  edge** (prereg extension E1, 2026-08-30): +23 bp/weekend, t=0.22, while the measured names
  earned +241 on the same weekends. The fade lives in the crypto-adjacent high-attention
  names, not in tokenized equities generally. Leveraged/inverse/vol ETPs (SOXL, TQQQ, UVXY…)
  and the crypto-underlying BITO are excluded mechanically on top of that. If the prop
  platform adds new symbols, they do NOT join this universe without a pre-registered
  measurement first.
- **New listings (prereg A7, 2026-09-09 20:29 UTC):** every Binance EQUITY perp is now collected daily from its
  listing date. A characteristic-based shortcut (weekend BTC co-movement, ≥ 3 weekends) was declared,
  tested and withdrawn the same day after adversarial review: inside this universe the low-co-movement
  names earned the most (+208 vs +76 bp), so the feature ranks nothing. Newcomers join only as a pooled
  cohort measured on the frozen definition. **Superseded 2026-09-12 07:51 UTC by prereg A8:** cohort N1 (20 names) is a
  group-sequential test — looks at 6, 9 and 12 usable weekends, boundaries t ≥ 2.55 / 2.08 / 1.80, both an
  equal-weight and an event-weighted +100 bp economic bar, futility if the 90% upper bound falls below +100 bp,
  backstop 2027-04-30. Same false-positive rate as the single February test it replaces; window opens
  2026-09-18, first look expected around 2026-11-20. An admitted cohort could only trade the **own-capital
  book** (no N1 name is on the prop platform's 36-symbol list) and **a crossing authorises nothing until a
  dollar size is filed**. Nothing enters this universe before then. Standing rules: `IDEA_TESTING_PROTOCOL.md`.

## The rule (frozen — no discretion at execution time)

- **Anchor:** Friday 16:00 America/New_York close (the monitor computes it).
- **Trigger:** perp ≤ **−0.50%** from anchor at decision time.
- **Entry:** Sunday **20:00 UTC**, long only, at market, every triggered name that passes the
  skip rules below.
- **Exit:** Monday **11:00 America/New_York** (close of the first full hour after the open),
  at market, all positions, no exceptions — no "letting winners run".
- **No stops in between — measured, not assumed (prereg amendment A3):** the MEDIAN event
  trades −2% below entry at some point before the Monday-open snap; a −2% stop flips the
  whole ledger from +121 to −23 bp/weekend, a −3% stop to ≈0, and a −5% stop changes nothing
  (7 hits in 85 events, noise-level difference, optimistic fills). Losers are closed at the
  timed exit like everything else; the +147.5 average already contains them. Position size is
  the risk control, not exits.
- **Account-protection override (not a trading stop):** if the combined floating loss of the
  fade positions ever reaches **−1,000 USDT** (the plan's own daily-stop figure), flatten
  everything and stop trading: the market is outside the measured distribution and the
  challenge account outranks the strategy. Absolute USDT on purpose — a percent-of-basket
  rule scales wrongly on small baskets (on a 1-name, 3,000-USDT weekend, −10% is −300, inside
  ordinary single-name vol, and would realize losses the measured hold recovers). At −1,000:
  a full 15,000 basket trips at −6.7% (1.5× the worst weekend ever measured, never approached
  in-sample), and a 1-name basket effectively never trips (worst measured single name −12.8%
  = −384) — the per-name cap is the control there. Corrected 2026-08-30 14:30 UTC after the
  user's question about 1-name baskets. This override firing even once = re-open the study
  before the next weekend.
- **Timing is defined relative to the next US session, not to weekday names (user decision
  2026-09-06 19:29 UTC).** Anchor = the last US regular-session close (16:00 NY). Trigger and entry = **20:00 UTC on the
  evening before the next US regular session**. Exit = **11:00 NY on that reopen day**. On an ordinary
  weekend that is Sunday → Monday. When Monday is a full US market holiday it is **Monday 20:00 UTC →
  Tuesday 11:00 NY**; when Friday is a holiday the anchor is Thursday's close. A holiday weekend is
  therefore the same trade with the same 19-hour hold — no separate rule, no skip. Disclosure: the
  study measured +147.5 bp on weekends with an ordinary Monday and pre-listed holiday weekends as
  exclusions; the shifted form has no triggered observation yet (prereg A4). It will accumulate them by
  being traded. Next dates where the shift applies: 2026-09-07 (Labor, Mon), 2026-12-25 (Christmas,
  Fri → anchor Thu 12-24), 2027-01-01 (New Year, Fri), 2027-01-18 (MLK, Mon), 2027-02-15 (Presidents,
  Mon). Early closes (13:00 NY on 2026-11-27 and 2026-12-24): the anchor is that day's actual close.
- **No averaging down, no moving the exit, no "it will recover after lunch":** the exit time
  is the strategy. Holding a loser past 11:00 New York is an unmeasured discretionary long.

## Sizing (from the challenge's own limits)

- **Basket notional: 15,000 USDT** per triggered weekend, split equally across triggered
  names, **per-name cap 3,000 USDT**.
- Rationale: worst measured weekend −4.3% → −645 on the basket; a 1.4×-worse weekend (−6%)
  → −900, inside the self-imposed 1,000/day stop (firm limit 2,500). Worst single name
  −12.8% → −384 at the cap. Twenty consecutive worst-case weekends are needed to breach the
  remaining 4,223 total allowance.
- These numbers scale with the account's remaining allowance — recompute after any loss.

## Sunday routine (≤ 15 min, before 20:00 UTC)

1. **Run the monitor** (also 30–60 min before entry for the final picture):
   `JAVA_HOME=/opt/homebrew/opt/openjdk@25 /opt/homebrew/opt/openjdk@25/bin/java -DskipVenues=hl -cp target/classes com.smalistean.propstrategy.live.WeekendFadeMonitorApplication`
   (Bybit column stays as the cross-venue check; add HL back — drop `-DskipVenues=hl` — only
   for an occasional deep scan or when Binance and Bybit disagree.)
2. **Mechanical skip rules** (the only permitted overrides, decidable by anyone):
   - **Earnings inside the window:** the company has a scheduled earnings release between
     Friday close and Tuesday open → skip the name.
   - **Weekend hard news:** a dated company press release or SEC 8-K over the weekend → skip
     the name.
   - Nothing else skips a name. "Feels scary" is not a rule. The backtest earned its numbers
     holding every news loser at capped size.
3. **Cross-venue sanity:** Binance/Bybit/HL columns should agree within a few bp. A large
   divergence on one venue = do not trust that name's price this weekend; investigate first.
4. **Log the decision** (triggered names, skips + which rule fired, sizes) in this file's
   journal section BEFORE entering — pre-registration discipline applies live.

## Reading the monitor

- **vs BTC column:** a name far below the basket median while BTC is flat/up is the NEWS
  profile (AXTI 2026-07-10, −12.8%) — that is what skip rule 2 exists to catch.
- **Funding 0.0000% is normal, not a data error.** 49–79% of ALL funding prints on these
  perps are exactly zero (verified in `binance_perp_funding_rate`, deduped): Binance runs
  them with a 0% interest-rate component, so funding only prints when the perp visibly
  dislocates from its own index. The index itself moves with the tokenized complex on
  weekends (live check 2026-08-30: AXTI mark 58.22 vs index 58.16 while −0.87% from Friday —
  premium ≈ +0.09%, funding 0). The measured +26 bp funding tailwind comes from the minority
  of prints where the perp falls faster than its index — it is a bonus when it appears, never
  a requirement.

## Standing constraints

- Long only (mirror short measured −70 bp/weekend, prereg amendment A1).
- Re-measure monthly: append new weekends to the ledger; if the de-clustered mean degrades
  toward zero or the funding sign flips persistently, stop and re-open the study.
- Stop-condition from the challenge plan applies: live drawdown 1,500 USDT total → stop
  trading, re-measure, no "trading back".

## Journal

(append per weekend: date UTC · triggered · skipped+rule · sizes · entry/exit fills · net)

### Weekend 2026-09-04 → entry **Mon 2026-09-07 20:00 UTC** → exit Tue 2026-09-08 11:00 NY — **TRADED: PAYP, +603 bp on spec** (first observation of the shifted form)

- **Monday 2026-09-07 is Labor Day** (NYSE/Nasdaq closed, reopen Tuesday 09-08). At 17:30 UTC this was
  first logged as a skip, on the prereg's pre-listed holiday exclusion. **Overridden by the user at
  2026-09-06 19:29 UTC: holiday weekends are the same trade, timed to the next US session.** So this
  weekend's decision bar is **Monday 2026-09-07 20:00 UTC**, exit Tuesday 09-08 11:00 NY (15:00 UTC).
  Sunday's readings below are previews, exactly as a Saturday reading is on an ordinary weekend. It is
  the first live trade of the shifted form (prereg A4: no prior triggered observation); its outcome is
  the first data point and goes in this journal either way.
- **Sunday preview (17:27 UTC):** PAYP **−1.35% = the only name past the line**; next EWY −0.42%, META
  −0.27%; the other 21 names up, HOOD +2.29%, AXTI +1.91%, SNDK +1.79%. BTC −0.06%, ETH +1.47%. If it
  holds through Monday 20:00 UTC this is a **1-name, 3,000-USDT basket in PAYP** — subject to the
  checks below, which are open items to resolve before the bar, not reasons it was skipped:
- **PAYP: items to clear before Monday 20:00 UTC:**
  - **News profile.** −1.35% against a +0.5%-ish basket median with BTC flat is the pattern skip
    rule 2 exists to catch (the AXTI 2026-07-10 shape). The whole move happened **Sunday 09:00→14:00
    UTC** (+0.06% → −1.35%), not Friday; Saturday had already recovered to +0.18%.
  - **Identity, corrected 2026-09-06 19:36 UTC: PAYP is PayPay Corporation (NASDAQ: PAYP) — SoftBank's
    Japanese mobile-payments company, Nasdaq ADS since 2026-03-12 (IPO at $16), Binance perp since
    2026-03-23 — not PayPal.** Two earlier bullets in this entry (a PayPal buyout story dated 08-28, a
    PayPal earnings date) were about the wrong company and are struck. Verified from Pyth's feed
    metadata ("PAYPAY CORP / US DOLLAR" for PAYP vs "PAYPAL HOLDINGS INC" for PYPL), Binance's listing
    notice, and the price levels ($16.78 vs PayPal's $55).
  - **Japan-time news risk specific to this name:** PayPay's news flow (SoftBank, Japanese regulators,
    Japanese press) runs on Tokyo hours. Monday 2026-09-07 is a US holiday but an ordinary business
    day in Japan — a full news day for the underlying while its ADS market is shut. Skip rule 2 applies
    to anything dated 09-05 through the 09-07 Tokyo session.
  - **Perp is not dislocated:** mark 16.80 vs index 16.805, funding 0.0000%. The index itself moved,
    on four external feeds (dxFeed/Kaiko/Pyth/Massive), so it is a real repricing, not a thin-perp
    artifact — on tiny volume (~$11k in the 14:00 UTC bar).
  - **Cross-venue check: unavailable for this name.** Bybit's `PYPLUSDT` ($55) is PayPal; neither Bybit
    nor any Hyperliquid HIP-3 dex lists PayPay. No alias exists to teach the monitor. Substitute check
    at the decision bar: Binance mark vs index must agree within a few bp (19:33 UTC: mark 16.7848,
    index 16.7890, +2.5 bp — the perp sits on its four-feed index, so the move is in the index, not a
    thin-perp artefact).
  - **Skip rule 1 (earnings, PayPay) — clear.** Last report 2026-07-30 (Q1 FY2026, call 08-04); next
    estimated 2026-10-30 (Yahoo). Nothing inside the 09-04 → 09-08 window.
  - **Skip rule 2 (hard news, PayPay) — clear as of 2026-09-06 19:37 UTC, re-check before the bar.** Yahoo's PAYP
    headline list has nothing dated 09-04 to 09-06 (latest: a Zacks analyst note ~08-29). **The run-up
    was Wed 09-02 +5.13% and Thu 09-03 +7.41% close-to-close (15.01 → 16.95), with no dated release
    found for either; Friday was +0.47% (16.95 → 17.03).** The weekend −1.47% has already erased Friday
    and is −1.00% vs Thursday's close — a give-back of a two-day, unexplained +13% run rather than a
    plain weekend drift. Whether that is fade-shaped or a post-spike reversion is not something the rule
    distinguishes;
    the rule reads the 20:00 UTC bar. Tokyo's Monday 09-07 session (00:00–06:00 UTC) is a live news
    window for this name while Nasdaq is shut: check PayPay IR (ir.paypay.ne.jp) and SoftBank news for
    anything dated 09-05 → 09-07 before 20:00 UTC Monday.
- **Monday preview (2026-09-07 06:06 UTC, 14h before the bar):** **two triggers** — PAYP **−2.41%** (deepened
  steadily overnight: −1.35 → −1.76 → −2.23 → −2.58 → −2.41; a 47k-USDT volume burst in the 06:00 UTC
  bar, i.e. Tokyo's close) and **META −0.75%** (new; Bybit agrees at −0.75%). Everything else flat to
  up; BTC −0.04%, ETH +2.00%. PAYP mark 16.62 vs index 16.585 = perp **+21 bp rich** to its index —
  the index is falling faster than the perp, so the move is in the underlying feeds, not perp selling.
  If both hold at 20:00 UTC: basket 15,000 / 2 = 7,500 → capped at **3,000 each, 6,000 total**.
  Own-capital tool dry-run verified for `PAYP,META` (nothing sent).
  **Skip rules at 2026-09-07 06:06 UTC, both names clear:** EDGAR shows no Meta filing since 2026-08-20 (a Form 4)
  and no PayPay filing since 2026-08-07 (6-K) — no 8-K/6-K dated 09-04 → 09-07. PayPay's own notice
  board: latest items 09-03, routine (card-settlement feature, a prefectural campaign end-date, a
  30M-user coupon milestone); no outage. SoftBank's PayPay news feed: nothing in September. Meta:
  no dated weekend release; next earnings late October. Final re-check of both boards at ~19:30 UTC.
  **Anchor validated against the stocks' official closes (2026-09-07 06:28 UTC, Nasdaq historical):** META closed
  Friday at **$616.77** vs perp anchor 616.83 (+1 bp); PAYP closed **$17.04** vs perp anchor 17.03 (−6 bp).
  Binance's index at the 20:00 UTC bar was 616.68 / 17.03. So the −0.7% and −2.4% are dislocations from
  the actual stock closes, not a perp premium being given back. (Bybit's TradFi CFD row shows META at
  614.76 — an extended-hours CFD quote, not the closing print; the CFD follows market hours, which is
  why it stops on 09-04. The `METAUSDT` perpetual is the instrument the monitor reads.)
- **2026-09-07 16:54 UTC, 3h before the bar — basket in flux:** PAYP **−2.23%** (steady around −2% all
  afternoon, mark on index). **META out at −0.40%**: it was −0.75% at the 16:00 bar close and bounced
  ~35 bp in the following hour with the perp +12 bp over its index (perp buying ahead of the feeds).
  **MSTR in at −1.09%** (Bybit −1.08%), but its vs-BTC column reads −0.12%: BTC is −0.98% since Friday
  and MSTR simply moved with it — crypto beta, not a stock dislocation. The rule is mechanical and the
  study's sample contained MSTR/COIN weekends like this, so it counts if it holds; it is noted so the
  outcome can be read for what it was. Funding on MSTR −0.045%/8h and CRCL −0.057%/8h: negative =
  shorts pay longs, a tailwind if held. Whoever is past −0.50% at the 20:00 UTC bar close is the
  basket; 15,000 / n, capped 3,000 each.
  **MSTR skip rules (2026-09-07 16:55 UTC) — clear.** EDGAR: no filing dated 09-05 → 09-07; last were a Form 144
  (insider sale notice) on 09-04 and two 8-Ks (items 7.01/8.01) on 08-31 and 09-01 — the 4,603-BTC
  purchase for $369.7M announced the previous Monday, already in the anchor. Earnings 2026-11-04.
  Known pattern to expect inside the hold: Strategy announces its weekly bitcoin buy by 8-K on Monday
  mornings; with EDGAR shut for Labor Day that would land Tuesday ~12:00 UTC, before the 15:00 UTC
  exit. It is a routine weekly event, not weekend hard news, and the study's MSTR weekends contained it.
- **2026-09-07 19:00 UTC, 1h before the bar — three past the line:** PAYP **−2.23%** (unchanged for six hours,
  mark on index); MSTR **−0.78%** (was −1.41% at 16:00, recovering as BTC came back from −0.98% to
  −0.76%; vs-BTC −0.02% — it is bitcoin); META **−0.52%** (Bybit −0.54%), back on the line after the
  17:00 bounce, 2 bp inside. Final EDGAR pass: no filing dated 09-05 → 09-07 for any of the three.
  Sizing if all three hold: 15,000 / 3 = 5,000 → capped **3,000 each, 9,000 total**; if two, 6,000.
  Own-capital dry run re-verified for `PAYP,MSTR,META`. The basket is whoever is ≤ −0.50% on the
  monitor read at 20:00 UTC; decision entry follows that read.
  **PayPay notice board, final pass (2026-09-07 19:01 UTC): one item dated today, judged NOT hard news.**
  2026/9/7, category セキュリティ: 「公式アプリストア以外からの不審なアプリのダウンロードにご注意ください」
  (https://paypay.ne.jp/notice/20260907/s-01/) — a consumer advisory against sideloading apps from SMS /
  email / social-ad links; says malware-infected phones "can" be remotely operated and points to the
  standing compensation-program page. No victim count, no loss figure, no compensation announcement,
  no regulator, no outage, no PayPay-system incident. Generic user-safety notice of the kind PayPay posts
  routinely; skip rule 2 (dated press release / 8-K disclosing a company event) does not fire. Other
  September items (9/1 terms revision, 9/2 shinkin-bank charge partnership, 9/3 features/campaigns)
  predate the anchor. PAYP stays eligible.

**DECISION AT THE BAR (close of the 19:00–20:00 UTC bar, Mon 2026-09-07):**

| Name | anchor | entry bar close | drift | verdict |
|---|---:|---:|---:|---|
| **PAYP** | 17.03 | **16.68** | **−2.06%** | **TRIGGER — the basket** |
| META | 616.83 | 614.31 | −0.41% | out (was −0.75% at 16:00, bounced) |
| MSTR | 142.69 | 142.34 | −0.25% | out (rode BTC back up from −1.41%) |

Both marginal names cleared the line in the final hour, exactly the coin-flip flagged at 19:31 UTC.
**Basket = PAYP alone, 3,000 USDT** (1 name → per-name cap, not 15,000). Long only, 1x, market.
Exit **Tue 2026-09-08 11:00 New York = 15:00 UTC**, at market, no stops in between.

**ACTUAL FILLS (own-capital Binance account, user-placed, read back from trade history 2026-09-08 05:05 UTC):**

| Leg | filled | qty | avg price | vs entry bar | on spec? |
|---|---|---:|---:|---|---|
| PAYPUSDT | 09-07 19:34:40Z | 17.99 | **16.6513** | bar close 16.68 | **yes** — triggered at −2.06% |
| MSTRUSDT | 09-07 19:35:01Z | 2.10 | **142.19** | bar close 142.34 | **no** — closed −0.25% at the bar, above the −0.50% line |

Two deviations from the spec, both recorded rather than argued:
1. **Entry was 19:34–19:35 UTC, ~25 min before the 20:00 bar.** PAYP at −2.23% then, −2.06% at the bar —
   inside the line either way, so the trigger decision is unaffected; the fill price differs from the
   measured convention by the intervening drift (16.6513 vs 16.68 = +17 bp better than the bar close).
2. **MSTR was entered on the 19:31 reading (−0.52%) and cleared the line by the bar (−0.25%).** It is an
   unmeasured discretionary long, not part of the fade ledger. It is exited on the same clock (early
   exit is itself discretion) but its P/L is booked SEPARATELY below so it cannot contaminate the
   +147.5 bp series.

Sizing note: own-capital legs are ~$300 each (the tool's default), not the 3,000 USDT prop-account
figure — the prop challenge is a separate platform and is not represented in these fills.

**OUTCOME (exit bar open 14:00 UTC, close 15:00 UTC = Tue 11:00 NY; fills read back 2026-09-08 17:22 UTC):**

| Leg | entry bar → exit bar (spec) | funding | **spec net** | own fill → own exit | realized |
|---|---|---:|---:|---|---:|
| **PAYP (on spec)** | 16.68 → **17.70** = +612 bp | 0.0 (two zero prints) | **+603 bp** | 16.6513 → 17.6792 @ 15:00:55Z | **+$18.49** |
| MSTR (off spec) | 142.34 → 137.84 = −316 bp | 0.0 | −325 bp | 142.19 → 137.88 @ 15:00:51Z | −$9.05 |

Fees $0.0006 total. PayPay reopened Tuesday well above where the perp had drifted: the re-anchoring
paid +6%. MSTR, which never triggered at the bar, moved with bitcoin and lost 3%; it is booked here
and NOT in the ledger. **Ledger entry, weekend 21 (fri 2026-09-04, shifted form, 1 event): +603 bp** —
at spec sizing 3,000 × 6.03% = +$180.9. The prop-account position, if taken, is on the other platform
and not visible here.
Operational note: at 14:57 UTC the Binance API key refused this machine (−2015, IP 94.139.159.105 not
allow-listed); the user closed manually in the app. By 2026-09-08 17:22 UTC signed reads worked again.
Recorded 2026-09-08 05:04 UTC.
- Own-capital book unchanged (ONG narrow pair only). First recorded 2026-09-06 17:30 UTC; rewritten
  2026-09-06 19:29 UTC after the user's decision.
- Own-capital book flat since 2026-09-09 18:44 UTC: the ONG pair was closed (whole-life −4.73 USD;
  `PROJECT_STATUS.md`). The Binance futures wallet now holds only USDT (1,814) and fee BNB, so no XVF
  leg shares margin with a fade leg. Hyperliquid and Bybit balances (≈ $3,260 together) are to be moved
  to Binance by the user; not yet moved as of 2026-09-09 18:53 UTC.

### Weekend 2026-08-28 → entry 2026-08-30 20:00 UTC → exit 2026-08-31 15:00 UTC — **no trigger, no trade**

- **Live:** AXTI read −0.90% at 14:00 UTC and −0.17% at 18:34 UTC on Sunday; at the 20:00 UTC decision
  the user observed the trigger gone. **Confirmed from the pinned bars** (Binance 1h closes at Fri 19:00 /
  Sun 19:00 / Mon 14:00 open-time bars): AXTI −11.9 bp at the official entry bar; **no name ≤ −50 bp**
  (worst: AAOI −33.8, JPM −29.9, EWJ −18.8). Green crypto weekend (BTC ≈ +2%); 21 of 24 names were UP
  into the entry bar (HOOD +309, MSTR +301, CRCL +263).
- **Control-cut record (unconditional, all 24 names held entry→exit):** mean **−36.5 bp**, median −42.6 bp.
  Buying "everything" would have lost; the trigger correctly sat out. This weekend enters the ledger as a
  no-event observation only.
- **Not a missed trade:** AXTI printed +471 bp entry→exit, but the pre-registered rule reads the 20:00 UTC
  bar, not the afternoon; the 14:00 reading was a preview. One weekend proves nothing about the entry
  time in either direction — it is exactly why the time is frozen instead of eyeballed.
- Own-capital book unchanged (ONG narrow pair only). Recorded 2026-09-02 06:20 UTC.

