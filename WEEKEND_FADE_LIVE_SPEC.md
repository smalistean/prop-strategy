# Weekend fade — live spec for the challenge account

**Filed:** 2026-08-30 14:05 UTC · **Author:** Claude, from the user's session decisions
**Measurement basis:** `WEEKEND_FADE_FUNDING_PREREGISTRATION.md` (+147.5 bp/weekend all-27,
+175.5 excl. private names, t=1.82/2.10, n=17 weekends; mirror short measured at −70 bp and
closed — LONG ONLY). This spec freezes the live rules so weekend decisions are mechanical.
Manual execution only — the firm requires it and the house rule is stricter (no automation
touches a live account).

## Universe (24 names)

All challenge-watchlist US equity/ETF perps with an exchange-listed underlying:

SPY, QQQ, EWJ, EWY, COIN, TSLA, MSTR, PLTR, HOOD, AAPL, AMZN, META, INTC, MU, CRCL, LLY,
JPM, QCOM, TSM, PAYP, SNDK, AAOI, AXTI, NOK.

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

