# XVF calculations — review package

**Revised 2026-08-21.** The first version of this document contained real errors: two backtests it
relied on had a same-day signal/outcome leakage bug and a pair-type misclassification bug, and one
live comparison in it used inconsistent denominators (one-leg vs two-leg notional). All three were
found by an independent review
([XVF_CALCULATIONS_INDEPENDENT_REVIEW.md](XVF_CALCULATIONS_INDEPENDENT_REVIEW.md)), then
independently re-verified here rather than taken on trust — each corrected query was re-run and its
result is what's quoted below, not the reviewer's claim standing alone. Two production constants
changed as a direct result; see section 1 and 2's "what changed in production."

Written for a second AI (or human) to independently check the reasoning, not just read a summary
of conclusions. Every number below has a script it came from; every script is in this repo and was
re-run standalone to confirm it still produces the quoted result before being listed here. Scope is
XVF only — this repo holds several unrelated strategies (Apollo, Gerchik, Carry, XSMOM); none of
that is in scope for this doc.

Companion reading, not duplicated here: [XVF_STRATEGY.md](XVF_STRATEGY.md) (what the strategy is),
[XVF_V1_SCOPE.md](XVF_V1_SCOPE.md) (why these three venues, what v1 does and doesn't cover),
[XVF_LIVE_FINDINGS.md](XVF_LIVE_FINDINGS.md) (live-execution incidents, including the real
per-venue fee measurements used in section 4), [XVF_IMPLEMENTATION.md](XVF_IMPLEMENTATION.md).

## How to reproduce everything below

```bash
psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-lookback-cadence.sql
psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-freshness-discount.sql
psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-freshness-window.sql   # needs the previous one's `elig` table, run in the same psql invocation or reload it
psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-capital-simulation-export.sql
CANDIDATES_CSV=/tmp/candidates_fresh.csv FUNDING_CSV=/tmp/funding_daily_fresh.csv \
  SIM_START=2025-08-21 SIM_END=2026-08-20 python3 scripts/xvf-capital-simulation.py 1500 1500 1500
```

Each script's own header comment has its runtime and its exact expected output, so a mismatch is
immediately visible without needing this file. The lookback-cadence script is now materially
slower than it first was (~34 min for one correlated subquery step in the run that produced the
numbers quoted below) - its own header explains why and gives real numbers, not a guess.

---

## 1. Ranking: pair-type-specific lookback (reverted - no surviving evidence)

**Original claim:** CEX-CEX candidates should be ranked off a 3-day trailing signal rather than the
shared 7-day one, because CEX-CEX gaps close within days and a 7-day window dilutes the signal.

**What actually happened:** the backtest behind this had two bugs, both found by the independent
review and both confirmed here by re-running the corrected query, not by inspection alone.

1. **Pair-type misclassification.** The original query labeled a base's row CEX-DEX whenever
   Hyperliquid appeared *anywhere* in that base's available venues that day
   (`'hyperliquid' = ANY(array_agg(venue))`), not whether Hyperliquid was one of the two venues
   actually selected as the pair. A Binance-Bybit pair could be mislabeled CEX-DEX purely because a
   Hyperliquid quote also existed for that base.
2. **Same-day leakage.** The "realized forward" window shared its first day with the trailing
   signal window - a large print on day D could both make a candidate look attractive under the
   3-day signal specifically (which is more reactive to one big day than a smoothed 7-day average)
   and then get recounted as part of what it "realized."

**Script:** [scripts/analysis-lookback-cadence.sql](scripts/analysis-lookback-cadence.sql) (now the
corrected version; its header keeps the original buggy result alongside the corrected one for
comparison).

**Original (buggy) result:** 61.3% realized for a 3-day-lookback CEX-CEX selection vs 50.4% for
7-day (n=2,573 vs 2,546) - a large, clean-looking 3-day advantage.

**Corrected result** (2024-01-08 to 2026-08-10, both bugs fixed, re-run to produce this exact
table):

| signal used to rank | pair type | realized (annualized) | n |
|---|---|---:|---:|
| 3-day lookback | CEX-CEX | 18.5% | 9,974 |
| 7-day lookback | CEX-CEX | **19.5%** | 8,740 |
| 3-day lookback | CEX-DEX | 23.5% | 8,431 |
| 7-day lookback | CEX-DEX | 22.9% | 7,547 |

**The finding reverses.** 7-day is marginally *better* than 3-day for CEX-CEX once both bugs are
fixed - the opposite of what shipped. There is no surviving evidence for a shorter CEX-CEX lookback.

**What changed in production:**
[XvfConfig.java](src/main/java/com/smalistean/propstrategy/xvf/XvfConfig.java) —
`LOOKBACK_DAYS_CEX_CEX` reverted from `3` to `= LOOKBACK_DAYS` (both 7 now). The pair-type-aware
*architecture* (dual trailing sums, lookback chosen per pairing rather than per leg in
`XvfSignalEngine.bestCrossVenuePair`) was kept - that part of the engineering is sound regardless
of which value each constant holds, and having it in place means a future, properly-verified value
can be dropped in without another architecture change.

---

## 2. Ranking: discount for candidates that aren't freshly eligible (kept, recalibrated)

**Original claim:** a candidate's signal is well-calibrated only on its first eligible day; a second
consecutive eligible day reads roughly double what it actually realizes, flat past that.

**What actually happened:** the same same-day leakage bug (section 1, bug 2) was present in this
backtest too - the forward window shared day D with the trailing signal, which specifically
inflated the "first day eligible" bucket, since that's exactly the day a big print both qualifies a
candidate and gets recounted as its own outcome.

**Script:** [scripts/analysis-freshness-discount.sql](scripts/analysis-freshness-discount.sql) (now
the corrected version - forward window starts at D+1, no shared day).

**Original (buggy) result:**

| pair type | streak | calibration |
|---|---|---:|
| CEX-CEX | 1st day (fresh) | 99% |
| CEX-CEX | 2nd day+ (stale) | 46% |
| CEX-DEX | 1st day (fresh) | 90% |
| CEX-DEX | 2nd day+ (stale) | 51% |

**Corrected result** (2024-01-02 to 2026-08-10, re-run to produce this exact table):

| pair type | streak position | realized (annualized) | signal (annualized) | calibration | n |
|---|---|---:|---:|---:|---:|
| CEX-CEX | 1st day eligible | 16.4% | 38.1% | **43%** | 8,421 |
| CEX-CEX | 2nd day | 15.2% | 52.6% | 29% | 6,426 |
| CEX-CEX | 3rd-5th day | 18.1% | 65.6% | 28% | 9,659 |
| CEX-CEX | 6th+ day | 25.5% | 82.4% | 31% | 7,331 |
| CEX-DEX | 1st day eligible | 17.4% | 26.4% | **66%** | 2,317 |
| CEX-DEX | 2nd day | 17.4% | 30.8% | 56% | 1,967 |
| CEX-DEX | 3rd-5th day | 17.0% | 39.5% | 43% | 5,255 |
| CEX-DEX | 6th+ day | 22.3% | 48.0% | 47% | 12,733 |

**The direction survives; the magnitude and the discount value don't.** Fresh candidates are still
better calibrated than stale ones, but by roughly 1.3-1.5x, not ~2x - a 0.5 discount overcorrects.
The bigger, separate finding this correction surfaced: *even a fresh signal* now over-reads its own
forward realization by more than 2x (43%/66% calibration, not 99%/90%), and nothing anywhere
corrects for that broader over-read - it isn't specific to staleness at all.

**Follow-up check, rerun against the corrected data:** does the freshness check need to look back
more than 1 day? [scripts/analysis-freshness-window.sql](scripts/analysis-freshness-window.sql)
still says no - calibration is flat within a point or two across N=1/2/3 even under the corrected
windows (e.g. CEX-CEX fresh: 43%/44%/43%), so N=1 (the shipped implementation) is unaffected by this
correction.

**What changed in production:**
[XvfConfig.java](src/main/java/com/smalistean/propstrategy/xvf/XvfConfig.java) —
`STALE_SIGNAL_DISCOUNT` moved from `0.5` to `0.65`, closer to the corrected 0.65-0.7 range, pending
a fuller recalibration.

**Caveat the reviewer should weigh:** 0.65 is a reasonable point estimate from the corrected table,
not a re-optimized one - the discount was not swept against realized book-level outcomes the way
`MIN_SPREAD_ANNUAL_PCT` was. The "even fresh signals over-read by 2x" finding is arguably the more
important open question and is entirely unaddressed by this constant.

---

## 3. Dropping dYdX (unaffected by the above)

Unaffected by either correction - this was decided in
[XVF_V1_SCOPE.md](XVF_V1_SCOPE.md) from a separate venue-combination measurement
(`scripts/analysis-venue-sets.sql`, pre-existing, not part of this session's bugs), not from either
of the two corrected backtests above.

**What changed in production:** `XvfConfig.VENUES` dropped dYdX; dead `dydx` branches removed from
`collateral()`/`normaliseBase()`, `LiveVolume`'s dYdX fetch, and `XvfExecutionApplication`'s
`venueDepthRank`. One correctness side-effect: `requireFreshFunding` could previously refuse to
produce a book over dYdX's *own* data going stale, even though dYdX was never tradeable (no
gateway existed for it) - now impossible by construction.

---

## 4. Capital expectancy: what $4,500 actually returns

**Claim under test:** what does the *current* ranking logic (sections 1 and 2, both corrected and
live in production) deliver, net of real fees and real per-venue capital constraints.

**A third bug found and fixed here:** the simulator originally used one flat fee schedule for every
venue (1.8bp maker / 5.0bp taker - `XvfConfig`'s own documented assumption). `XVF_LIVE_FINDINGS.md`
measured real fills and found Bybit charges nearly double its published schedule - **3.6bp maker /
10.0bp taker**, against Binance's 4.5bp taker (cheaper than assumed, due to an active BNB fee
discount) and Hyperliquid's 4.5bp taker (Hyperliquid's maker fee was not established in that
measurement and is left at the original 1.8bp assumption - every Hyperliquid-touching pair's fee
here is still an estimate, not a measured one). The current book is Bybit-legged on nearly every
pair, so this was a real, not cosmetic, understatement.

**Scripts:**
[scripts/analysis-capital-simulation-export.sql](scripts/analysis-capital-simulation-export.sql)
(candidate/funding export, now matching the corrected sections 1-2 ranking) feeding
[scripts/xvf-capital-simulation.py](scripts/xvf-capital-simulation.py) (day-by-day simulator,
now with per-venue fees).

**Result**, $4,500 split $1,500/$1,500/$1,500 (binance/bybit/hyperliquid), all three corrections
applied, each year an independent run starting fresh at $4,500 (no carryover between them):

| year | net return | annualized | trades opened | skipped for lack of venue capital | binance funding | bybit funding | hyperliquid funding |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2025-08-21 → 2026-08-20 | +$295.12 | +6.56% | 701 | 1,432 | -$1,343.27 | +$1,885.99 | -$59.96 |
| 2024-08-21 → 2025-08-20 | +$26.36 | +0.59% | 996 | 414 | +$294.69 | -$76.02 | +$47.82 |

**These land close to the pre-correction numbers** (+7.79%/+1.05%) despite three separate,
independently-motivated corrections going into this run (real per-venue fees, corrected discount
value, corrected lookback). That's worth noting precisely because it's easy to over-read: the
corrections partially offset each other in this specific two-year window (higher real fees pushed
the result down; a changed candidate mix pushed it back up), which is a fact about these two years,
not evidence that the bottom line is insensitive to methodology in general.

**Which venue carries the book flips between the two years.** In the recent year binance loses big
and bybit carries the whole book; in the prior year it's the reverse (binance modestly positive,
bybit modestly negative), and the skip count is much lower (414 vs 1,432) since neither venue got
as capital-constrained. This is not a single-year artifact: a third run, one continuous 2-year
simulation (capital and open positions carried across the year boundary, same $4,500 start) landed
at **+$324.24 net (+7.21% cumulative, ~3.5%/year compounded)**, with binance down -$1,238.66 in
funding over the full 2 years and bybit up +$2,034.24 - the same lopsided pattern sustained, not
reversed, over the longer window; it happens to average out across these particular two years
because the imbalance points in opposite directions in each one, not because it is small. Trades
opened over the continuous run: 1,774, skipped for lack of venue capital: 1,773 - close to a 1:1
ratio of skips to successful opens.

Both years still land far below the double-digit "signal quality" percentages the ranking
backtests report - the same reasons apply as before: a real 20-slot book blends in lower-ranked
candidates, pays real fees, and loses real capacity whenever a venue runs short of capital.

**Caveats the reviewer should weigh, in order of how much they'd change the conclusion:**

1. **Two years is not a robust sample**, and they disagree by more than 10x (6.56% vs 0.59%). A
   third and fourth year should be run before treating either number, or an average, as stable.
2. **The capital-constraint skip count is large** (1,432 and 414) and is the biggest legible drag
   after fees. Whether a different capital split would meaningfully reduce it hasn't been tested -
   this simulation holds the starting split fixed for the whole period.
3. **Still not verified in this simulation** (raised by the independent review, not yet acted on
   here): production's uncapped book walks past a rank-20 candidate that can't actually be opened
   rather than leaving the slot empty; this export caps at rank 20 with no backfill, which is a
   pessimistic bias in the opposite direction from the fee correction's optimistic one. Net bias
   unknown without a corrected run.
4. **Weekly liquidity floor uses the full calendar week**, including days after the candidate's own
   ranking day - a lookahead in the $500k floor check, present in every script in this doc that
   uses the `vol` CTE. Not yet corrected or quantified.
5. **No price/basis risk is modeled**, stated up front in the simulator's own docstring. The real
   ACE finding in `XVF_LIVE_FINDINGS.md` is real, additional risk this number does not include.

---

## 5. Execution-engine correctness (not a "calculation," but load-bearing for all of the above)

Unaffected by the corrections above - these are runtime bugs in the order-execution engine, found
from a real book close-and-reopen, not from any backtest.

**Chase race / over-sizing** — [PairedEntryEngine.java:351](src/main/java/com/smalistean/propstrategy/xvf/execution/PairedEntryEngine.java)
(`adoptVenueFill`, called from `chase()` at line 319). A chase cancels and re-sizes the resting order
from the engine's own fill watermark; a fill landing at the venue right as the cancel goes out
reached the stream late, so the replacement order was sized from a stale remainder. Measured live:
SLP came out 75% oversized, $149/leg against a target of $85. Fix reads the cancelled order's true
fill back from the venue before sizing the replacement.

**Resting order outliving a written-off pair** — [PairedEntryEngine.java:423](src/main/java/com/smalistean/propstrategy/xvf/execution/PairedEntryEngine.java)
(`cancelResting`). `UNHEDGED_ALERT` is terminal and is set from the hedge path, which never touches
the maker order - every route that should have cancelled it (chase's early return,
`finalizeDeadline`, engine `close()`) skipped straight past a pair in that state. Measured live: a
written-off BNT maker kept resting and kept filling in amounts too small to hedge individually.

Both pinned by regression tests in
[PairedEntryEngineTest.java](src/test/java/com/smalistean/propstrategy/xvf/execution/PairedEntryEngineTest.java) -
each verified to fail against the pre-fix code and pass against the fix (checked via `git stash`
against the previous commit, not asserted).

**Caveat the reviewer should weigh:** both were found by *reacting to* a real incident, not a
systematic audit of every state-transition path in `PairedEntryEngine`. Whether there's a third
instance of the same pattern (engine-tracked state silently drifting from venue truth) hasn't been
specifically checked.

---

## 6. Open items this doc does not resolve

- **`XVF_V1_SCOPE.md` flags its own unreconciled numbers**: an independent reproduction of its
  `vset2()` scoring didn't match its published Score/Deployed figures. Predates this session, still
  open.
- **The AWS/dashboard signal path and the local execution path are different strategies right now.**
  `aws/recorder/.../XvfSignalHandler.java` uses a single configured lookback (defaulting to 7 days),
  applies no stale-signal discount, and reads from DynamoDB rather than the corrected
  `XvfSignalEngine` logic in this repo. A number on the public dashboard is not currently comparable
  to a locally-opened position - confirmed by reading the AWS handler directly, not just asserted.
- **The historical-cutoff date uses the JVM's default timezone, not explicit UTC.**
  `XvfExecutionApplication`/`XvfSignalApplication` call `LocalDate.now()` with no `ZoneId` argument;
  the funding-freshness SQL then compares against `asOf::date`. If the process's timezone isn't UTC,
  the effective cutoff shifts by up to a day from what's intended. Confirmed by reading the call
  sites; not yet fixed.
- **These research scripts do not replicate `BybitGateway.requireCryptoPerp`'s live stock/ETF
  filter.** Worst-offender bases in section 4's capital simulation were spot-checked against
  `symbolType` and cleared, but that was a targeted check of ~15 symbols, not a systematic filter
  over the full candidate universe these scripts rank over.
- **There is no frozen per-position forecast to audit against**, an independent-review
  recommendation not yet acted on: `xvf-position-snapshot.py` reconstructs actual fills and funding
  but does not record the entry-time signal that caused each pair to open, so an individual pair's
  actual performance can't be checked against what it was expected to do at entry.
