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
3b. **Own-capital pre-flight, before any order** (skip for the prop account, which is manual on
   another platform):
   `-Daction=check -DcheckNotional=<total notional you intend>` on `FadeOrderApplication`.
   It must print, and you read all four:
   - `signed read OK` — the key works from THIS machine. It refused once (−2015, IP allowlist)
     three minutes before an exit on 2026-09-08. Run it again from the exit machine before the exit.
   - `position mode one-way` and `asset mode single-asset` — the order path assumes both.
   - `fee burn ON` — **house rule: fees are always paid in BNB.** If this prints OFF, switch BNB
     fee burn back on in the Binance futures UI before trading; it is worth 10% of every fee
     (taker 0.0400% → 0.0360%).
   - `balance covers ~N more weekends` — top up BNB when N drops below about 4. Running out is not
     a failure: Binance silently charges the fee in USDT and the order still fills, so the tool
     warns rather than refuses. The only loss is the discount.
4. **Log the decision** (triggered names, skips + which rule fired, sizes) in this file's
   journal section BEFORE entering — pre-registration discipline applies live.

### BNB for fees (house rule, recorded 2026-09-14 09:57 UTC)

Fees are paid in BNB on the own-capital Binance account, and BNB lives in the USDⓈ-M futures wallet
(asset mode is single-asset, so BNB there is fee currency only and is not margin collateral).

Measured on the 2026-09-13 weekend, 20 legs at $190 = $3,800 notional: entry cost **0.00188 BNB
($1.36)**, so a round trip is **≈ 0.0038 BNB ($2.74)** at a 0.0360% burn-discounted taker rate. That is
**7.2 bp of the notional per round trip**, comfortably inside the 9 bp all-in figure the ledger assumes.

Balance on 2026-09-14 09:57 UTC: **0.01229 BNB ($8.93)** — today's exit needs ~0.0019, leaving ~0.0104
BNB, about **2.8 more weekends** at this size. Top-up guide at $3,800 per weekend:

| Top up to | Cost at BNB ≈ $726 | Covers |
|---|---:|---|
| 0.05 BNB | ~$36 | ~13 weekends (a quarter) |
| 0.10 BNB | ~$73 | ~26 weekends (half a year) |
| 0.20 BNB | ~$145 | ~52 weekends (a year) |

Scale down if the basket grows: the fee is linear in notional, so doubling equity halves the weekends a
given BNB balance covers. Buy BNB on spot and transfer it to the USDⓈ-M futures wallet; the pre-flight
(routine step 3b) prints the balance and the weekends it covers before every run.

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

### Weekend 2026-09-18 → entry 2026-09-20 20:00 UTC → exit 2026-09-21 15:00 UTC — **TRADED: 6 names, +423.4 bp on spec** (own-capital +159.22 USDT; 6/6 green)

- **DECISION ROW — read from the 19:00–20:00 UTC bar close, Sun 2026-09-20, written 2026-09-20 20:01 UTC before any order.**
  Anchor Fri 2026-09-18 20:00 UTC. **6 of the 24 triggered**, in order of depth: PAYP −1.36, SNDK −1.00,
  AXTI −0.78, HOOD −0.77, MU −0.76, TSM −0.64. Nearest misses: AAOI −0.43, EWJ −0.41, AAPL −0.38.
  The set was identical at the 19:55 read and at the bar; nothing crossed in or out in the final five minutes.
  NVDA is outside the 24 and was not evaluated.
- **Skips: none.** Rule 1 (earnings before the exit) and rule 2 (weekend release or 8-K) clear on all six,
  checked 19:50–19:57 UTC. Items seen and judged not to fire, all disclosed:
  - **SNDK joins the S&P 100 effective before Monday's open (2026-09-21)** — the index change was
    **announced 2026-09-04**, fourteen days before the anchor, so it is pre-anchor information and rule 2
    does not fire. SNDK rose ~11% on Friday 09-18 on index front-running and is −1.00% since that close.
    A5 measured ran-up names as fading *better* (+204 vs +118 bp), so the run-up is not a reason to skip.
    **Recorded as a mechanical tailwind, not a rule:** index funds buy at Monday's open, which is the exit bar.
    This is the first fade leg with a known index-inclusion print inside the hold; its outcome is disclosed
    whichever way it goes and does NOT become a selection rule afterward.
  - MU — earnings **2026-09-30** (confirmed, after close), outside the window; last 8-K 2026-08-26.
  - TSM last 6-K 2026-09-10; AXTI last 8-K 2026-07-30; HOOD last 8-K 2026-07-29.
  - PAYP — no US filing over the weekend. PayPay notice board items dated 09-17→09-19 are routine
    (bill-payment maintenance 09-19→09-24, Starbucks mobile-order integration, a PayPay Card
    holiday-period notice for the 09-19→09-23 Japanese holidays, and a PayPay **Bank** deposit-rate
    change effective 11-01 — a sibling company, not the issuer). Same class as the 2026-09-07 precedent.
- **Sizes: prop 15,000 / 6 = 2,500 each**, per-name cap 3,000 not binding, 1x CROSS.
  **Own-capital Binance: $631 per name × 6 = $3,786** (Plan S 0.75 × 5,045.94 / n), 3x ISOLATED.
  Same total notional as the 2026-09-13 weekend ($3,808 over 20 names); the money is spread over six.
- **Concentration, recorded because no rule covers it:** four of the six (MU, SNDK, TSM, AXTI) are
  memory/semiconductor names, and that was the losing half of the 2026-09-13 book. A six-name basket is
  not six independent bets. The spec has no concentration limit and inventing one at the bar is the same
  error as reselecting names, so all six are taken. Queued alongside the liquidity floor as an amendment
  to be pre-registered for FUTURE weekends.
- **Sizing was NOT raised.** 0.75× equity stands; 1.0× ($841/name) was considered at 19:57 UTC and declined:
  the live out-of-sample record is two weekends, and scaling a concentrated basket on the in-sample mean
  is not a decision to make at the bar.
- **Pre-flight 2026-09-20 19:49 UTC:** signed read OK, one-way, single-asset, fee burn ON (taker 0.0360%),
  no open positions, 5,045.94 USDT available. **BNB 0.010426 (~$8.03) — about 2.9 weekends, still not
  topped up; after this round trip roughly 1.8 remain.**
- **Cohort N1 (prereg A8): this is the first weekend inside the declared window** (opens 2026-09-18).
  Run `analysis-sequential-test.py count 2026-09-20` after the entry bar is stored. Nothing in N1 is traded.
- **ACTUAL FILLS (own-capital Binance, user-placed, read back 2026-09-20 20:07 UTC).** All six sent live at
  **20:04:48–20:04:55 UTC**, 4m48s after the bar closed and after the decision row above was written.
  6 of 6 FILLED, no failures, no `-2015`, no off-spec legs.

  | Leg | qty | avg fill | bar close | vs bar | notional |
  |---|---:|---:|---:|---:|---:|
  | PAYP | 36.16 | 17.4713 | 17.4600 | +6.5 bp | 631.76 |
  | SNDK | 0.35 | 1773.9100 | 1773.2900 | +3.5 bp | 620.87 |
  | AXTI | 9.07 | 69.6047 | 69.5200 | **+12.2 bp** | 631.31 |
  | HOOD | 5.29 | 119.1100 | 119.0000 | +9.2 bp | 630.09 |
  | MU | 0.62 | 1008.7958 | 1008.4500 | +3.4 bp | 625.45 |
  | TSM | 1.45 | 432.4986 | 432.1200 | +8.8 bp | 627.12 |

  **Total notional 3,766.61** against the 3,786 target — the $19 shortfall is lot-size rounding, mostly SNDK
  (0.01 step at $1,774 = $17.73 per step) and MU. Entry commission **0.001759 BNB ($1.36)**; wallet BNB
  0.010426 → **0.008668**, roughly 2.4 more weekends after this exit. USDT 5,045.94, available 3,790.59,
  margin posted ~1,255.
  **Every fill is above the bar close, mean +7.3 bp** — market buys into a tape that was recovering through
  the five minutes after 20:00. That is a real cost paid against the measured convention and is recorded, not
  netted out: the ledger entry is computed from bar closes and the own-book realized number will run about
  7 bp behind it.
  **Tool defect, not blocking:** the `sent:` lines printed `filled N @ null ($null)` — the RESULT response's
  avgPrice is not being read, so fills are only visible from the position readback. Fix before the next run.
- **Exit Mon 2026-09-21 15:00 UTC** (11:00 NY), market, all legs, both books. Run `-Daction=check` from the
  exit machine first.

**OUTCOME — WIN. Ledger entry, weekend 23 (fri 2026-09-18, ordinary form, 6 events): +423.4 bp.**
Read back 2026-09-21 15:03 UTC. Exit fills **15:00:11–15:00:16 UTC**, 6 of 6, no failures, no early exits.

| Leg | trigger | price | funding | **spec net** | own realized | own % |
|---|---:|---:|---:|---:|---:|---:|
| AXTI | −78 | +1401 | −3.2 | **+1389** | +86.91 | +13.77 |
| HOOD | −77 | +384 | −4.5 | **+371** | +22.99 | +3.65 |
| MU | −76 | +315 | −2.7 | **+303** | +19.84 | +3.17 |
| PAYP | −136 | +258 | −0.0 | **+249** | +14.83 | +2.35 |
| TSM | −64 | +197 | −0.6 | **+188** | +11.74 | +1.87 |
| SNDK | −100 | +53 | −2.8 | **+41** | +2.91 | +0.47 |

**6 winners of 6** — the first all-green weekend on the live record. Mean +423.4 bp, median +276.
**Fifth-best of the 23 weekends** now recorded (behind +956, +701, +467, +417).

**Own capital:** realized **+160.09 USDT**, funding **−0.87** (longs paid this weekend, the mirror of
09-13 when funding was received), net **+159.22 on 3,766.60 deployed = +4.23%**. Commission 0.003531 BNB
(**$2.79**, 7.4 bp round trip). Wallet 5,045.94 → **5,205.16 USDT**; BNB 0.008668 → **0.006895, about 1.9
weekends left — the top-up is now overdue.**

**Prop (not readable here):** at 2,500 × 6 and the same equal-weight return, roughly **+632 USDT**.
Confirm on the platform. Stage-1 allowance consumed would fall back toward ~770 of 5,000.

**AXTI carried the weekend: +1389 bp is 55% of the mean on 17% of the capital.** It is the smallest and
thinnest name in the universe. Recorded plainly: a 6-name basket whose result is one name's move is a
high-variance outcome, and this is the favourable tail of exactly the concentration flagged in the decision
row above. It is not evidence the concentration was good, any more than 09-13's semis were evidence it was bad.

**Disclosures, both declared before the outcome:**
- **SNDK's S&P 100 inclusion printed at Monday's open** (announced 09-04, pre-anchor, rule 2 did not fire).
  It was the **worst leg at +41 bp**. The predicted index-fund buying was not visible in the return; the
  flag is retired without becoming a rule in either direction.
- **BTC rose +5.89% from the entry bar to the exit**, so crypto beta plausibly contributed to HOOD and
  possibly the complex. **Correction to the live analysis at 2026-09-21 13:35 UTC: it was stated there that
  BTC had fallen 2.04% since entry. That was wrong** — a hardcoded epoch literal (1758394800) pointed at
  2025-09-20, not 2026-09-20, so a year-old series was compared. The user caught it. The claim that the
  perps were undislocated (mark − index 0.0 to 33 bp) was computed correctly and stands.

**Discretion: none taken.** An early close of AXTI was considered at 14:55 UTC while it fell from +15.47%
(14:43) to +13.30%, and declined; the full basket exited on the clock at 15:00. AXTI's own spec exit came in
at +1401 bp price, so waiting the five minutes neither helped nor hurt materially. The rule was not overridden.

**Live out-of-sample record now 3 weekends: +603, −85, +423, mean +313.7 bp.** The pre-registered headline
remains **+143.9 bp / t=1.86 on the frozen 20** and live weekends are NOT added to it.

### Weekend 2026-09-11 → entry 2026-09-13 20:00 UTC → exit 2026-09-14 15:00 UTC — **TRADED: 20 names, −85.4 bp on spec** (own-capital −31.35 USDT; first full-basket weekend)

- **Preview 2026-09-13 06:25 UTC (monitor 05:56 UTC), 14 hours before the bar.** Anchor Fri 2026-09-11 20:00 UTC. Ten universe
  names ≤ −0.50%: AXTI −1.80, AAOI −1.71, INTC −1.70, NOK −1.34, MU −1.33, SNDK −1.24, HOOD −1.23, TSM −0.69,
  MSTR −0.68, QCOM −0.63. NVDA −0.57% also prints TRIGGER but is **not in the 24** (not on the prop watchlist);
  the monitor's footer counts it and prints "$1,364 each" — **wrong for this account: 15,000 / 10 = 1,500 each**,
  10 names, 15,000 total, if all ten hold at the bar. Nearest misses JPM −0.31, QQQ −0.31. Saturday 08:25 UTC
  the picture was NOK −0.90 and AAOI exactly −0.50 with nothing else close; the widening happened overnight.
- **Not a crypto weekend.** BTC +0.05%, ETH −0.63% since the anchor. Hourly Binance bars show two synchronized
  down-hours across the ten with BTC flat: **Sat 19:00 UTC** (INTC −0.88, AXTI −0.46, MU −0.45, NOK −0.45 h/h;
  SKHYNIX −1.65 and SAMSUNG −1.43 in the same hour, NVDA/AMD/AVGO/SPY/QQQ unmoved) and **Sun 05:00 UTC** (SNDK
  −0.83 on $76M, MU −0.51 on $4.6M, AMD −0.40). A memory-led sector repricing during closed hours, Korean names
  leading, not perp-wide selling and not a macro shock. Eight of the ten are semiconductors/memory: one sector
  bet split ten ways. The spec has no sector cap; the de-clustered ledger counts this as one weekend either way.
  No dated release found for the Sat 19:00 UTC hour as of 2026-09-13 06:25 UTC.
- **Friday's real session (Nasdaq closes 09-10 → 09-11):** SNDK −3.50%, HOOD −0.67%, MU −0.22%, AXTI +0.11%;
  the rest up (NOK +4.80%, QCOM +2.88%, INTC +2.61%, AAOI +2.00%, MSTR +1.87%, TSM +1.22%). The weekend drop is
  not a continuation of a Friday selloff except for SNDK.
- **Skip rule 1 (earnings Fri close → Tue open) — clear, all ten.** Nasdaq earnings calendar for 09-11, 09-14,
  09-15: 14 / 16 / 18 reporters, none of the ten. (MSTR next 11-04; MU's fiscal-Q4 report is later in September,
  not in this window.)
- **Skip rule 2 (dated press release or 8-K in the window) — clear by precedent, one item to know about.**
  EDGAR `submissions` for all ten, filings dated ≥ 09-11:
  - **SNDK 8-K accepted Fri 2026-09-11 20:15:07 UTC = 16:15 ET, fifteen minutes after the anchor, so inside the
    window.** Items 1.01 / 9.01; the sole exhibit is *Amendment No. 1, dated 2026-09-09, to the credit agreement
    with JPMorgan as administrative agent* — a financing-document amendment, no press release, no guidance, no
    offering, no transaction. Same class as the MSTR 8-K (items 7.01/8.01, weekly bitcoin purchase) that the
    2026-09-07 journal judged "a routine event, not weekend hard news". **Verdict: does not fire.** If the letter
    of the rule is preferred over that precedent, SNDK is the one name that drops and the basket is nine at
    1,667 each; decide before the bar, not after.
  - QCOM: Form 144 and Form 4 accepted Fri 22:33 / 22:53 UTC — insider-sale notices, routine (MSTR Form 144
    precedent 09-04). Not hard news.
  - AAOI: 8-K accepted Thu 09-10 20:05 UTC, before the anchor: purchase of its Houston building for $26.8M
    cash (option under a February lease). Outside the window and routine.
  - AXTI, INTC, NOK, MU, HOOD, TSM, MSTR: nothing dated ≥ 09-11. TSM's 09-10 6-K is the August revenue
    release (+53% y/y), in the anchor. NOK 6-K 09-08, MSTR 8-K 09-08 (weekly BTC purchase), MU 8-K 08-26.
  - Press wires (Nasdaq news-by-symbol, all ten): commentary only over the weekend (Motley Fool, Zacks,
    Barchart); no dated company release.
- **Cross-venue sanity — pass.** Binance/Bybit/HL agree within ~30 bp on every name; the widest is QCOM
  (Binance −0.63, Bybit −0.35 at 05:56 UTC).
- **Liquidity, stated as fact, no rule exists:** in the Sat 19:00 UTC hour NOK traded $11k, TSM $24k–41k,
  QCOM $52k, AXTI $102k on Binance against a 1,500 USDT order each; SNDK traded $76M, MU $4.6M.
- **Own-capital book:** none of this is placed there; N1's window opens 2026-09-18.
- **SNDK — user decision 2026-09-13 06:45 UTC: keep it.** The precedent reading (routine 8-K does not fire rule 2) stands; the
  basket is the full ten if all hold. Open item for after the weekend: one line in rule 2 naming which 8-K
  items are hard news, so the next such case is decided by text.
- **Prop account, this weekend:** balance 48,726 USDT. The spec's figures are absolute and unchanged — basket
  15,000, per-name cap 3,000, account-protection override −1,000 floating. Ten names → **1,500 each**; nine →
  1,667; eight → 1,875. Manual execution on the platform after the bar, exit Mon 15:00 UTC at market.
- **Own-capital Binance book, this weekend (user 2026-09-13 06:45 UTC: discretionary, "without hard rules", leverage
  allowed).** Wallet 5,077 USDT, no positions; signed read OK from this machine at 06:3x UTC (IP 178.168.6.193,
  not the one refused on 09-08). `FadeOrderApplication` extended today with `-Dleverage` (1–5, default 1) and
  `-DmarginType` (ISOLATED default); sets margin mode, then leverage, then the order; signed pre-flight
  balance read before any live order. Dry-run verified for all ten at $380 and $760 per name (nothing sent).
  Every name sits at the exchange default 20x CROSS today; the tool sets 3x ISOLATED per name before buying.
  Sizes on the table, same command with a different `-DvalueUsd`:
  | option | per name | total notional | margin at 3x | worst measured weekend (−4.28%) | −8% sector Monday |
  |---|---:|---:|---:|---:|---:|
  | C — Plan S ramp (50%) | 190 | 1,900 | 633 | −81 (−1.6% of equity) | −152 (−3.0%) |
  | A — Plan S full | 380 | 3,800 | 1,267 | −163 (−3.2%) | −304 (−6.0%) |
  | B — 1.5× equity | 760 | 7,600 | 2,533 | −325 (−6.4%) | −608 (−12.0%) |
  Isolated margin caps a single-name disaster at its posted margin; it does nothing for ten names falling
  together, and eight of these ten are one sector. Both books together at A: 18,800 USDT of notional on one
  memory-led move. Liquidity check at the bar (Plan S R3): order ≤ 10% of the decision bar's quote volume; at
  $380 that needs a $3,800 bar, which NOK ($11k in the Saturday hour that moved it) clears only narrowly.
- **Adversarial review of the order tool and the sizing, 2026-09-13 06:53 UTC — not blocking, all findings acted on.**
  Code path (verified by the critic with its own dry runs and against Binance's API behaviour): margin-type
  change succeeds on a flat symbol, −4046 handling is correct, margin type before leverage is the required
  order, the balance regex cannot cross assets, no sell-to-open path exists, all ten quantities round to
  $368–380. What was missing was confirmation, not correctness, and is now added: orders return the fill
  (`newOrderRespType=RESULT`, printing status / filled qty / average price); every live run ends by listing
  the account's actual open positions; a name already held is refused so a re-run after a timeout cannot
  double it; failures are tallied on stdout with a non-zero exit; the pre-flight also checks one-way position
  mode and single-asset margin mode; `-Daction=check` does the signed reads with no order, for the Monday
  14:45 UTC exit pre-flight from whichever machine will run the exit. Symbols are de-duplicated and a
  malformed `-Dleverage` now refuses instead of silently running 1x.
  Risk (critic's numbers, from the single-name tail because eight of ten are one sector): worst measured
  single name −12.8% applied to the whole basket is **−$505 at A (10% of equity), −$1,011 at B (20%)**; a −15%
  semis morning −$570 / −$1,140; no exit before 15:00 UTC. Isolated 3x is a margin-efficiency choice, not a
  risk control: liquidation 31.6–32.9% below entry (bracket maintenance rates 0.65–2.5%), unreachable at the
  measured MAE. Fees ≈ $3.42 round trip at A against $10 of BNB; funding on the ten currently 0 to +0.069%
  per 8h in the long's favour. **Recommendation: A ($380 × 10) or C ($190, the Plan S ramp); B rejected** —
  at B the −8%-of-equity flatten line is a −5.3% basket move, inside the measured distribution and below the
  prop book's line, so the two books would exit on different rules for one signal.
  **Monday's one number:** the equal-weight average of the ten names' moves from their 20:00 UTC fills, read at
  13:30 UTC (cash open) and 15:00 UTC. Lines: −4.3% worst measured weekend; **−6.7% = the prop override
  (−1,000 on 15,000), used for both books, own-book dollars −$254 at A**; not −8% of equity.
  If A is chosen it is a declared override of the Plan S ramp (weekend 2 of 4 would be $190) and is logged
  as such; the ten-name fill log (order id, fill price, time) is kept either way — this is the first live run
  of the margin-type path.
  **Before 20:00 UTC, user action in the Binance UI:** confirm the API key's IP allowlist contains this
  machine's current IP (178.168.6.193 at 06:3x UTC) or is unrestricted; the last own-capital failure was at
  the exit, and tonight there are ten legs to close, not two.
- **DECISION ROW — read from the 19:00–20:00 UTC bar close, Sun 2026-09-13, written 2026-09-13 20:04 UTC before any order.**
  Anchor Fri 2026-09-11 20:00 UTC. **20 of the 24 triggered**, in order of depth: AXTI −4.18, SNDK −4.10,
  MU −3.72, INTC −3.65, HOOD −3.16, NOK −2.69, AAOI −2.65, PLTR −1.43, TSM −1.41, QCOM −1.38, EWY −1.19,
  COIN −1.15, AMZN −1.08, TSLA −0.87, MSTR −0.85, QQQ −0.85, META −0.81, EWJ −0.78, SPY −0.55, JPM −0.50.
  Not triggered: AAPL −0.40, CRCL −0.14, LLY −0.01, PAYP +0.32. NVDA −1.35 triggered but is outside the 24.
  **Skips: none** — rule 1 (earnings before the exit) clear on all 20; rule 2 (weekend release or 8-K) clear
  on all 20, re-checked 19:39 UTC across EDGAR and the press wires. Items seen and judged not to fire, all
  disclosed: SNDK 8-K 09-11 20:15 UTC (credit-agreement amendment, precedent of the MSTR weekly-purchase
  8-K), AMZN 424B5 debt shelf takedown, JPM ~90 routine 424B2 structured-note supplements, META Form 4,
  QCOM Forms 144/4. The four ETFs (SPY, QQQ, EWJ, EWY) have no company filings.
  **Sizes: prop 15,000 / 20 = 1,500... no — 750 each** (15,000 basket, 20 names, per-name cap 3,000 not
  binding). Balance 48,726, so 1,274 of the 5,000 Stage-1 allowance is used and 3,726 remains; at 15,000 the
  self-imposed −1,000 flatten is a −6.7% basket move against a −4.28% worst measured weekend.
  **A 1,000-per-name (20,000) basket was considered and rejected at 2026-09-13 20:04 UTC:** it would put the −1,000 stop at
  a −5.0% move, 0.7 points beyond the worst measured weekend, and the spec's own scaling rule (recompute
  against remaining allowance) points to 13,000–14,600, not 20,000. Raising the basket is a dated amendment,
  not a decision at the bar.
  **Own-capital Binance: $190 per name × 20 = $3,808** (Plan S 0.75 × 5,077 / n), 3x ISOLATED, liquidation
  ~32% below entry. Pre-flight at 2026-09-13 20:04 UTC: signed read OK, one-way mode, single-asset margin, no open
  positions, 5,077.29 USDT available. Declared override of the Plan S ramp (weekend 2 of 4 would be $95).
  **Leverage/margin mode:** prop 1x CROSS (at 1x liquidation is unreachable either way; cross is simpler
  across 20 legs). Binance 3x ISOLATED — that is where isolated does work, capping a single name at its
  ~$63 of posted margin.
  **Liquidity note, no rule exists:** JPM's decision bar traded $1,442, so a $190 order is 13% of it, above
  Plan S's 10% guide; EWJ $2,171. Both are taken anyway — the spec has no liquidity floor and inventing one
  at the bar is the same error as reselecting names. Recorded for the floor amendment already on the list.
  **A deepest-N selection was asked about and measured before the bar and NOT applied** (in-sample, same 20
  weekends as the headline): deepest-10 −1.1 bp vs the frozen rule; deepest-half +75 bp at paired t 1.54,
  short of the declared t ≥ 2; the real signal is that the −71..−50 bp quintile is the only losing bucket
  (−21 bp against +165..+226 for everything deeper). Queued as a trigger-threshold pre-registration for
  FUTURE weekends. Tonight's −0.50% line stands, so JPM and SPY are in.
  **Exit Mon 2026-09-14 15:00 UTC** (11:00 NY), market, all legs, both books. Run
  `-Daction=check` at 14:45 UTC from the exit machine first.

**OUTCOME — LOSS. Ledger entry, weekend 22 (fri 2026-09-11, ordinary form, 20 events): −85.4 bp.**
Read back 2026-09-14 15:09 UTC from Binance klines and the account's own income records.

| Leg | trigger | price | funding | **spec net** |
|---|---:|---:|---:|---:|
| COIN | −115 | +781 | −1.3 | **+770** |
| HOOD | −316 | +475 | −0.5 | **+465** |
| MSTR | −85 | +404 | +4.0 | **+399** |
| PLTR | −143 | +306 | −0.0 | **+297** |
| META | −81 | +186 | −0.0 | **+177** |
| SPY | −55 | −19 | −0.0 | −28 |
| QQQ | −85 | −41 | −0.9 | −51 |
| EWJ | −78 | −57 | +0.2 | −66 |
| AMZN | −108 | −66 | −0.0 | −75 |
| QCOM | −138 | −85 | +11.2 | −83 |
| JPM | −50 | −96 | −0.0 | −105 |
| TSLA | −87 | −96 | −0.9 | −106 |
| SNDK | −410 | −143 | −4.5 | −157 |
| TSM | −141 | −177 | +6.2 | −180 |
| MU | −372 | −254 | −1.8 | −265 |
| INTC | −365 | −273 | +1.9 | −280 |
| AXTI | −418 | −414 | +8.0 | −415 |
| EWY | −119 | −543 | +2.4 | −550 |
| AAOI | −265 | −580 | +9.2 | −580 |
| NOK | −269 | −884 | +15.1 | **−878** |

**5 winners of 20.** Every winner is crypto-adjacent (COIN, HOOD, MSTR, PLTR) or META; bitcoin rose
through the Monday session and carried them. Everything anchored to the actual equity tape lost. The
two country ETFs (EWY −550, EWJ −66) and the semis (AXTI, INTC, MU, TSM) did not re-anchor upward at
all — they continued down into the US open. NOK lost 8.8% on its own.

**Own-capital Binance, realized:** entry fills 20:06:01–20:06:27 UTC (all after the bar), exit fills
15:00:48–15:00:58 UTC, 20/20 both ways, no off-spec legs, no failures. Realized P&L **−32.238 USDT**,
funding **+0.889 USDT**, net **−31.349 USDT** on 3,767 deployed = **−0.83%**. Commission 0.003744 BNB
(**$2.71**, 7.2 bp round trip) — under the 9 bp the ledger assumes. Wallet 5,077.29 → 5,045.94 USDT;
BNB 0.012290 → 0.010426. Realized −0.83% against the spec's −0.854% is a +2 bp execution difference
across 40 market orders; the fills tracked the bars.

**Prop account (not readable here):** at 750 × 20 = 15,000 and the spec's −85.4 bp, roughly **−128 USDT**.
Confirm against the platform. That is 5% of the 2,500 daily-loss limit and 2.6% of the 5,000 total;
no limit is threatened. Stage-1 allowance consumed rises from 1,274 to ~1,402 of 5,000.

**Where it sits.** −85.4 bp is the **second-mildest of the eight losing weekends** now on record
(−428, −287, −244, −208, −156, −94, **−85**, −70). Live out-of-sample record is **2 observations:
+603 bp (weekend 21) and −85 bp (weekend 22)**, mean +259. That is not evidence of anything; two
observations against a sample standard deviation of 347 bp carry no information.
Pooled with the 20 pre-registered weekends the series reads n=22, mean +154.4 bp, t=2.08 — recorded
for bookkeeping only. **The pre-registered headline remains +143.9 bp / t=1.86 on the frozen 20.**
Live weekends are not added to it; mixing in-sample and out-of-sample observations into one t is
exactly the error A8 exists to prevent.

**Nothing here changes a rule.** No name is reclassified, no threshold moves, and the −71..−50 bp
quintile finding stays queued as a pre-registration for future weekends rather than being applied
after a losing weekend. JPM (−50, the shallowest trigger) returned −105 and SPY (−55) returned −28,
which is consistent with that finding but is one weekend and is not evidence for it.


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

