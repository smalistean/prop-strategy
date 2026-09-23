# The 10%-of-decision-bar cap, measured from the order book — declaration first

**Date:** 2026-09-23 10:09 UTC · **Author:** Claude · **Question (user, 2026-09-23 09:5x UTC, pasted list):** "Measure 10% rule; also we can
use the amount to align to 10% rule; and use another symbol for remaining part, maybe should be measured."

The Plan S rule "an order is at most 10% of the decision bar's quote volume, on each venue" was set without a
measurement (GATE_SECOND_VENUE_STUDY.md, addendum 2026-09-23 08:27 UTC calls it "the unmeasured lever"). This file
declares what is measured and what number would change the rule, before any ladder beyond the single format check
below has been read.

## What is read

- Binance USDT-M `bookDepth` daily archives (data.binance.vision, `futures/um/daily/bookDepth/{SYMBOL}/`). Each file
  holds snapshots about every 30 s with the cumulative notional resting within ±0.2%, ±1%, ±2%, ±3%, ±4%, ±5% of the
  price, both sides. Format check done on one file only: AXTIUSDT 2026-09-20 (ask side at 19:59:01 UTC seen; no cost
  computed from it).
- For every (name, weekend) of the 24 fade names from each name's listing through the weekend of 2026-09-18, live
  shifted form (holiday weekends included, timed to the next US session): the **entry ladder** = the last snapshot
  before the entry bar's close (20:00 UTC), ask side; the **exit ladder** = the last snapshot before the exit bar's
  close (11:00 America/New_York), bid side. Decision-bar quote volume from `binance_perp_kline` (1h).
- Estimated average slippage of a market order of notional Q: the ladder gives cumulative notional N at offsets
  0.2/1/2/3/4/5%; take the offset as piecewise-linear in cumulative notional (uniform density inside each band, zero
  offset at zero notional); slippage(Q) = (1/Q) ∫₀^Q offset(q) dq, in bp. Orders larger than the 5% level are marked
  "beyond ladder" and counted as failing.
- Levels: 5, 10, 15, 20, 30, 50% of the decision bar; and fixed notionals $1,000 / $3,750 / $12,500 / $20,000 — the
  per-name sizes of a $100k book at 20, 6 and 1 triggered names, and the $5k book's one-name size.
- Cross-check: the six recorded fills of weekend 2026-09-18 (mean +7.3 bp above the bar close at $631 per name)
  must sit inside the ladder's estimate for $631 on those six names, or the method is reported as wrong.

## Decision rule, declared now

Cells that matter: (name, weekend) where 10% of the decision bar is below $12,500 (a $100k book's six-name size), so the
cap binds on a book of that size. On those cells, entry side:

1. **Raise to 15% or 20%** only if the *marginal tranche* (10→15%, or 15→20%) costs ≤ 48 bp average slippage on at
   least 75% of the cells. 48 bp is one third of the pre-registered mean (+143.9 bp/weekend): the tranche must keep
   two thirds of its expected edge on three cells out of four. The higher level is taken only if the tranche below it
   also passes.
2. **Lower to 5%** if the 0→10% tranche itself costs > 48 bp on more than 25% of the cells.
3. Otherwise the cap stays at 10%.
4. The exit side is measured and reported at the same sizes but does not gate: the exit is inside US hours on a bar
   the rule does not cap.
5. **Overflow** ("use another symbol for the remaining part"): the unfilled part of a capped name is redistributed
   equally to the triggered names that still have room under their own cap and under the 20%-of-equity per-name
   cap, repeated until nothing moves. Measured on the 23 recorded weekends at $50k/$100k/$200k: dollars deployed,
   result in dollars with each leg charged its own ladder slippage at its actual size. It is adopted only if the
   redistributed dollars fill within the same 48 bp tranche cost; the dollar comparison between variants is reported
   but is **not** the criterion, because the pre-registration is equal-weight and assigns no per-name expected return.

## Limits, stated before the numbers

- The ladder is 0–30 s before the order and static: refills during the sweep are ignored (cost overstated), and
  other participants' orders in the same minute are ignored (cost understated). Live fills so far were 4–5 minutes
  after the close at n=6; the ladder is read at the close.
- Resolution: the 0.2%→1% band is 80 bp wide; inside it the cost is interpolated, not observed.
- The measured period is one listing cycle (Jan–Sep 2026); depth on thin names changes with market-maker programs
  and can move faster than this measurement is repeated.

## Scope notes added 2026-09-23 10:11 UTC, before any ladder was computed (the transcript shows the append at 10:11:13–10:11:40 UTC and the first ladder computation at 10:15:08 UTC; the file first carried "10:24", a stamp written from memory, which was wrong)

- The weekend set is **26**, not the 23 written above: the 20 frozen backtest weekends, the 3 holiday-shifted weekends
  the backtest excluded but the live rule trades (2026-04-03, 2026-06-19, 2026-07-03), and the 3 live weekends. The
  frozen block was measured on 27 names (the 24 plus NVDA, SPCX, OPENAI; recomputed here and reproduced to 0.1 bp on all
  20); this measurement uses the 24 names the live spec trades, so nine frozen weekends have one or two fewer legs here.
- Overflow receivers are the *triggered* names with room, never an untriggered name.
- The fills cross-check uses the six recorded entry fills of 2026-09-18 (PAYP +6.5, SNDK +3.5, AXTI +12.2, HOOD +9.2,
  MU +3.4 bp above the bar close, TSM from the same table), at $620–632 each, sent 4m48s after the close.

## Result (2026-09-23 10:51 UTC, replacing the 10:20 UTC draft after adversarial review) — the cap moves to 20% by the declared rule; the bar is a weak yardstick

**Data read:** 1,632 (name, weekend, side) cells requested; 1,238 archive files exist (the rest predate each name's listing).
The two archive days of the Labor Day weekend (2026-09-07 entry, 2026-09-08 exit) are **excluded as corrupt**: their ask
ladder holds one value on all six levels for the whole day (CRCL $1,027 at 0.2% and at 5%, against a $3–5M bid side); they
produced every "beyond the 5% level" cell in the first draft. A ladder side is used only if its six levels are non-decreasing
and not all equal. That leaves **586 cells with a valid entry ladder** (24 names, 33 weekends, 115 triggered), every one with
a valid exit ladder. An independent scan of all 1,238 ladders (band average ask ≤ band average bid = crossed) found crossed
books only on that weekend, 12 entry and 13 exit ladders; excluding just those instead of the whole weekend moves every row
below by at most 0.2 bp. Snapshots are 26–88 s before the bar close: 1,200 of 1,238 are 26–30 s old; the 18 entry ladders
of weekend 2026-04-10 are 60 s old and the 20 exit ladders of weekend 2026-05-22 are 88 s old (archive gap 14:58:32 to
15:01:08 UTC on 2026-05-26; no triggered name that weekend). Scripts: `overflow2.py`, `cap_measure2.py`,
`fills_decompose2.py`, `spreads.py`, `depth_fetch.py`; raw ladders `depth/ladders.json`; all in the session scratchpad.

### The cross-check failed as declared; what replaced the method's claim

Declared: the ladder's estimate for $631 must contain the six recorded fills. It does not: the ladder (walk only) gives
0.0–6.1 bp, the journal records +3.4 to +12.2 bp above the bar close. Our own fills were located on the trade tape by
quantity (all six found; tape VWAP equals the journal's average fill to the fourth decimal). Reference price = the last trade
at least 2 s before our first fill.

| leg | fills | drift, bar close → reference (bp) | reference age (s) | fill vs reference (bp) | ladder walk | walk + half-spread |
|---|---:|---:|---:|---:|---:|---:|
| PAYP | 3 | −5.7 | 166 | +12.2 | 6.1 | 9.2 |
| SNDK | 1 | +3.4 | 2 | +0.1 | 0.0 | 0.0 |
| AXTI | 3 | +5.8 | 35 | +6.4 | 0.7 | 1.6 |
| HOOD | 1 | +10.1 | 8 | −0.8 | 0.1 | 0.6 |
| MU | 6 | +3.1 | 3 | +0.4 | 0.0 | 0.1 |
| TSM | 4 | +6.9 | 54 | +1.8 | 0.2 | 0.3 |

Most of the recorded number was the 4m48s delay (−5.7 to +10.1 bp of drift), which the ladder never measured. Against the
reference trade, four legs are within 1.5 bp of walk + half-spread; PAYP and AXTI are +3.0 and +4.8 bp above it, and both
had a stale reference (no trade for 35–166 s). Measured instead from the ask at the moment of the first fill (the review's
refuters, from the same tape), the ladder misses the walk itself by at most 1.4 bp (PAYP +0.35, AXTI +1.39, TSM +1.20, MU
+0.25, SNDK 0, HOOD −0.06); the rest of the residual is the spread crossed from a bid print, and PAYP's "−5.7 bp drift" is
one tick of bid-ask bounce, not a move. **Ruling, as the declaration requires: the method as declared is wrong** —
it misses the spread and, on thin names, a few bp more. **What is used instead:** cost = ladder walk + half of
max(tape spread, one tick), on entry and on exit, and every tranche verdict below is re-checked with a **+5 bp error bar**
(the largest residual). The delay is a separate, avoidable cost and is not in the model.

Spread proxy (median gap between consecutive opposite-side trades within 60 s in the decision hour, 2–3 Sundays; floored at
one tick, $0.01 on every name): half-spread 0.1–0.6 bp on 19 names; EWJ 0.5, AXTI 0.9, LLY 1.2, PAYP 3.1 (tick-floored),
NOK 4.7.

### Entry-side book walk (bp, average over the order), walk only

| order | all cells (n=586) median · p75 · p90 | triggered cells (n=115) median · p75 · p90 |
|---|---|---|
| 5% of decision bar | 0.8 · 1.9 · 4.3 | 0.9 · 2.0 · 3.9 |
| **10% (current cap)** | **1.5 · 3.8 · 8.7** | **1.8 · 4.1 · 7.8** |
| 15% | 2.3 · 5.6 · 13.0 | 2.7 · 6.1 · 11.8 |
| 20% | 3.0 · 7.5 · 17.3 | 3.6 · 8.1 · 15.0 |
| 30% | 4.5 · 11.3 · 22.0 | 5.5 · 11.9 · 18.6 |
| 50% | 7.4 · 17.2 · 33.2 (3 beyond the ladder) | 9.1 · 17.5 · 26.2 |
| $3,750 (20 names at $100k) | 0.8 · 2.5 · 8.3 | 1.5 · 3.5 · 16.3 |
| $12,500 (6 names at $100k) | 2.7 · 8.4 · 19.9 | 5.0 · 11.6 · 35.0 |
| $20,000 (1 name at $100k) | 4.3 · 13.0 · 26.7 | 8.0 · 16.3 · 49.1 |

The traded population costs about the same as all cells in share-of-bar terms and **about twice as much at a fixed dollar
size**: triggered names are the thinner names that weekend. Exit side (bid ladder at the exit bar close): 10% of bar 1.0 ·
2.5 · 5.3; $20,000 3.3 · 8.1 · 15.4; nothing beyond the ladder.

### The declared rule, applied

Cells where the cap binds for a $100k book (10% of bar < $12,500): **370 of 586**. Marginal tranche cost, entry, walk only;
"+5 bp" = the same test with the error bar added to every cell; "inside 0.2%" = cells where the whole order up to the
tranche's upper end sits within the first ladder level, so its cost is bounded by 20 bp regardless of the interpolation:

| tranche | ≤ 48 bp on | with +5 bp | median · p75 · p90 | inside 0.2% |
|---|---:|---:|---|---:|
| 0 → 10% | 370/370 = 100% | 100% | 0.9 · 2.6 · 5.0 | 356 |
| 10 → 15% | 368/370 = 99% | 99% | 2.3 · 6.5 · 12.5 | 351 |
| 15 → 20% | 365/370 = 99% | 99% | 3.2 · 9.1 · 17.5 | 333 |
| 20 → 30% (not a declared option) | 365/370 = 99% | 98% | 4.6 · 13.0 · 22.0 | 302 |

Since 2026-06-01 only (207 binding cells of 358): 100% / 100% / 99% / 99%, medians 0.7 / 1.8 / 2.6 / 3.7 bp. With the
half-spread added to every tranche (the review's recomputation on the draft's 380 cells): 100% / 99% / 99% / 99%, medians
1.5 / 2.9 / 3.8 / 5.2, one new failing cell (LLY 2026-06-05, 48.2 bp). Rule 1 passes
for 15% and for 20%, with the error bar, and with the 20 bp bound in place of the interpolation on nine cells in ten; rule 2
does not fire. **By the declaration, the cap becomes 20% of the decision bar.** The interpolation matters only on the 17–37
cells per tranche where the order leaves the 0.2% level; those set the p90.

### What the fixed-size rows say that the share-of-bar rows hide

Bar volume and resting depth are rank-correlated (Spearman 0.70 over 586 cells) but the ratio of ask notional within 0.2% to
the bar spans a 40-fold range (median 0.66, p10 0.11, p90 4.2). Per name, median over its weekends, entry walk in bp:

| name | median bar $ | at 10% of bar | at 20% | at $3,750 | at $12,500 | at $20,000 |
|---|---:|---:|---:|---:|---:|---:|
| PAYP | 2,617 | 1.5 | 3.1 | 20.0 | **47.1** | **68.9** |
| JPM | 3,629 | 0.7 | 1.4 | 9.8 | 25.4 | 35.5 |
| EWJ | 5,447 | 0.5 | 1.1 | 3.8 | 12.4 | 18.8 |
| LLY | 5,659 | 0.3 | 0.7 | 4.3 | 13.3 | 17.3 |
| NOK | 21,483 | 1.7 | 3.3 | 2.7 | 9.0 | 13.9 |
| AXTI | 22,482 | 2.8 | 5.6 | 5.1 | 15.1 | 19.9 |
| QCOM | 35,610 | 2.7 | 5.3 | 3.7 | 11.9 | 16.6 |
| MU | 1,475,297 | 6.7 | 13.3 | 0.2 | 0.5 | 0.8 |
| SNDK | 2,243,159 | 8.7 | 17.9 | 0.2 | 0.7 | 0.9 |

Ten percent of PAYP's bar is $260 and costs 1.5 bp; ten percent of SNDK's bar is $224k and costs 8.7 bp. Cells costing more
than 48 bp (walk + half-spread) at a fixed size: $3,750 none; $12,500 16 of 586 (PAYP 13, EWJ 2, SNDK 1); $20,000 32 of 586
(PAYP 23, EWJ 5, SNDK 1, QCOM 1, LLY 1, AXTI 1). A book-based cap (order ≤ the notional resting within the first ladder level,
for instance) would express the constraint directly; it is **not** declared here and would need its own declaration before
it is measured against outcomes.

### Overflow ("use another symbol for the remaining part")

Twenty-six weekends, 24-name universe, each leg charged walk + half-spread on entry and exit at its own size (115 of 116
legs had valid ladders; the Labor Day PAYP leg is charged nothing). Dollars are what the recorded weekends would have paid
on a book of that size under each variant; the pre-registration assigns no per-name expected return, so the differences
between rows are the liquidity effect plus which names happened to win, not a forecast.

| book | cap | overflow | deployed / allocation | total, 26 weekends | per weekend |
|---|---:|---|---:|---:|---:|
| $100k | 10% | no (current) | 47% | $9,649 | $371 |
| $100k | 10% | **yes** | 55% | $11,877 | $457 |
| $100k | 20% | no | 58% | $10,992 | $423 |
| $100k | 20% | yes | 64% | $12,005 | $462 |
| $100k | 30% | no | 62% | $11,417 | $439 |
| $50k | 10% | no | 58% | $5,642 | $217 |
| $50k | 20% | yes | 71% | $6,677 | $257 |
| $200k | 10% | no | 35% | $15,888 | $611 |
| $200k | 20% | yes | 55% | $23,017 | $885 |

Overflow at the current cap deploys **less** than a 30% cap without it (55% vs 62% of the allocation) and yields more
dollars on these weekends, because the redistributed money lands in the deep names (walk ≈ 0) instead of deeper in the thin
books. **Criterion 5**, checked on the receiving legs themselves: the dollars overflow adds at the 10% cap cost a marginal
1.9 bp median, 6.2 bp p75, 24.2 bp max over 50 receiving legs at $100k (27 legs, max 35.2 bp at $200k); none above 48 bp, so
overflow is **admissible**. What it changes in risk, at $100k over the 26 weekends: legs at the $20,000 per-name cap go
from 3 (current) to 10 (overflow at 10%) and 15 (20% + overflow); worst weekend −$1,454 → −$1,782 → −$2,114; weekend
standard deviation $1,041 → $1,254 → $1,286; under 20% + overflow 19% of deployed dollars sit above 10% of the receiving
name's bar. The per-name maximum stays 20% of equity. It changes Plan S sizing (WEEKEND_FADE_LIVE_SPEC.md, own-capital
rule) and `FadeOrderApplication`'s per-name sizing; neither is edited by this file.

### Limits (in addition to those declared)

- The archive's percentage levels are measured from the mid (1,359 of 3,433,531 snapshots have both ±0.2% bands empty
  with ±1% filled and none has one side empty; a Binance collaborator says the same on binance-public-data issue #447,
  2026-05-12), so the
  interpolated walk starts at the mid and the added half-spread restores the best-quote start; the model is right to within
  about a half-spread, at most 1.4 bp on all names but NOK (4.7 bp).
- The 0.2% level holds nearly every order in this study: the tranche costs are interpolated inside that band, and only
  the 20 bp bound is observed. The p90 values come from the minority of cells that leave the band.
- 2–3 Sundays of tape per name for the spread; PAYP's proxy rests on 2–5 trade pairs per Sunday and is tick-floored.
- Bars of February–April 2026 were $1–11k on names that now print $30–250k; the fixed-size rows mix both regimes, the
  since-June tranche rows above do not, and the verdict is the same in both.
- Nothing here measures the permanent impact of a larger order on the exit price, the reaction of other fade traders to a
  visible 20%-of-bar print at 20:00, or the archive collector's health on other days (one weekend of 34 was corrupt and
  was caught only because it produced impossible cells).

### Review record

Three critics (code, data/definitions, decision logic) raised 28 findings; three refuters each, majority decides; the run
is `wf_ba5f70dd-2dc` in the session's workflow directory. The first draft's numbers were reproduced by the critics and the
defects were real: corrupt Labor Day ladders, market-wide taker VWAP used as "our fills", zero spread on PAYP and COIN, a
flat-band bug in the walk integral, a "deploys more" sentence contradicted by its own table, a concentration claim without
the counts, the "within 1 bp" claim false at fixed sizes, wrong >48 bp counts, a guessed timestamp, and the wrong band named in
the resolution caveat. Every number above comes from the corrected scripts.
