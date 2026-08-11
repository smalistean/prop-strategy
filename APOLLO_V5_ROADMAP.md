# Apollo V5 — review and forward roadmap (2026-08-10)

Supersedes the four-point "Next research steps" list in `APOLLO_V5_DESIGN.md`, which was written
before Family B, the price-bin fix, and the granularity investigation produced evidence that
changes the priority order.

## 1. What was actually done

| # | Change | Outcome |
| --- | --- | --- |
| 1 | V5 split from V4: multi-day map search (`baseMapLookbackDays`), volume-first candidate ranking, third-touch discount | V4 left byte-identical; V5 parallel and independently comparable |
| 2 | Labelled example dataset (`APOLLO_LABELLED_EXAMPLES.md`, 20 examples) | First ground-truth check in the project's history |
| 3 | Detector recall vs. the 2 exact-price labelled examples | **Match to within a few dollars on both** (ETH low 1,831 vs. labelled 1,828; BTC 62,307 vs. 62,300), over 508- and 284-bar windows only reachable via the new multi-day search |
| 4 | Third-touch filter re-scoped | **Disabled** — its 3rd counted touch *was* the documented entry trigger; risk already covered by "first revisit consumes the base" |
| 5 | Family B: liquidity/POC-limit entry (`apollo-v5-liquidity-limit`) | **Largest single effect of the session.** ETH 7→92 trades, BNB 3→37, BTC 2→34 |
| 6 | Volume-profile price-bin bug found and fixed (`VolumeProfilePriceSteps`) | XRP/ADA/DOGE/TRX were **mathematically incapable** of producing a trade (whole price history in one `[0,10)` bin). 5 symbols re-imported |
| 7 | Profit-target termination disabled for research | XRP had been silently truncating ~3 months of its training window |
| 8 | ATR-scaled dynamic bin aggregation + concentration normalization | Fixed a real BTC regression with an identified mechanism; otherwise inconclusive (see §3) |

## 2. What the evidence actually supports

**Strong, mechanism-backed findings:**

- **The swing-reversal gate was the binding constraint**, not base detection. Removing only that
  wait (Family B), with every other gate identical, multiplied trade counts 10-25x. This was
  predicted from instrumentation *before* the result was seen, and confirmed.
- **The multi-day search finds real course-visible structures.** Verified against ground truth, not
  just backtest P&L — the single most trustworthy result of the session, because it is the only one
  not exposed to the multiple-testing problem below.
- **A coarser analysis bin mechanically inflates POC/zone share** even with zero real structure.
  Normalizing by the aggregation multiple turned BTC Family B from -$7,889 (drawdown termination)
  to +$2,411 (PF 1.17) with the same 34 trades. Real mechanism, real fix.

**Not supported by the evidence:**

- Any claim that a *specific* symbol/family/granularity combination has an edge. See §3.

## 3. The methodological problem this session created

**56 backtests were run against the same training window today.** Across granularity rounds, the
same symbol/family flips sign repeatedly:

| Config round | BNB Family B | SOL Family B | BTC Family B |
| --- | ---: | ---: | ---: |
| R1 original static step | +$5,567 (37) | +$325 (11) | -$835 (34) |
| R2 finer static step | -$732 (67) | -$1,582 (62) | *(unchanged)* |
| R3 ATR-scaled, unnormalized | +$835 (79) | +$6,473 (85) | -$7,889 (52, MAX_DD) |
| R4 normalized | -$1,880 (3) | +$3,547 (13) | **+$2,411 (34)** |
| R5 BTC-aligned fraction | -$3,013 (5) | +$3,999 (14) | *(n/a)* |

**A real edge does not flip sign five times on bin width.** The honest reading is that at these
sample sizes (3-92 trades) most of these differences are noise, and the "best" configuration found
by scanning many is the one that most overfits the training set. This is exactly the
threshold-churn failure mode `PROJECT_STATUS.md` warns about throughout, arrived at by a different
route: instead of tuning one threshold repeatedly, we varied a data-representation parameter and
re-ran everything.

**Consequence for the XRP result:** XRPUSDT Family B passing all 8 acceptance criteria (+$10,146,
PF 1.55, 60 trades) is a *training* pass found among ~56 runs. It should not be promoted to
validation on its current basis. Its own diagnostics also show it is fragile: edge almost entirely
short-side ($9,857 vs. $290), subperiod 4's PF 12.66 is 70% one calendar month, subperiod 3 lost
-$6,039 at PF 0.12.

## 4. An unexamined structural finding worth pursuing

Across every profitable Family B run, the profit concentrates in one exit reason:

- ETH: 62/92 trades are stop-losses (-$34,699). The 21 *holding-period-expired* exits win 100% of
  the time (+$18,444), and 7 take-profits win 100% (+$19,383).
- XRP: 32/60 stop-losses (-$18,010); 22 holding-period-expired at 86.4% win (+$11,238); 5
  take-profits (+$16,370).
- BTC (normalized): same shape.

**Roughly half the profit comes from the 24-hour maximum-holding-period timeout, not from reaching
the mapped target.** That is not what the strategy is nominally designed to do. It suggests the
target (next mapped liquidity zone) is frequently too far to be reached, and the timeout is
harvesting partial moves by accident. Average MFE (1.68%) exceeding average MAE (1.02%) on ETH is
consistent with real directional edge that the current target selection fails to convert.

This has never been investigated and is cheap to test. It is now the highest-value open question.

## 5. Roadmap, in priority order

### Step 1 — Stop granularity work. Freeze the representation.

One clean fix came out of it (concentration normalization, with an identified mechanism). Five
rounds produced no consistent improvement otherwise, and BNB is negative in every configuration
tried — that is now a reasonably solid negative finding about BNB, not a tuning failure. Freeze the
current representation and change no more bin/step parameters without a mechanism-level reason.

*Open item:* the ETH `$1` re-import (in progress) will make aggregation active for ETH for the
first time. Record its effect once, then stop.

### Step 2 — Exit/target structure (new, highest value)

Test, as isolated one-variable changes against a frozen config:
- **Nearer target**: first internal volume wave instead of the next mapped zone (the course
  explicitly says internal waves can be earlier targets than the principal POC —
  `APOLLO_COURSE_SOURCE_NOTES.md`, Книга 2.0 p.32).
- **Holding period sensitivity**: if 24h is load-bearing, 12h/36h/48h should move results
  systematically. If they do not, the timeout is capturing noise, not structure.
- **Partial exit at 1R** with the remainder running to the mapped target.

Rationale: this addresses where the P&L demonstrably comes from, and each test is a single
declared variable rather than a representation change that re-rolls every result.

### Step 3 — Extend the labelled dataset with exact prices, and label *entries*

The current dataset has 20 examples but only 2 with exact prices/dates, and it only ever checked
*base detection*. The far more valuable check is whether the strategy's **entry decisions** match
the course's — same direction, similar price, similar stop. Target ~15 exact-price examples with
entry/stop/target read off the chart. This is the only evidence stream immune to the
multiple-testing problem in §3, and it is what `APOLLO_COURSE_SOURCE_NOTES.md` has asked for since
it was written.

### Step 4 — Multi-timeframe swing hierarchy

Still unimplemented, still source-grounded: two dedicated concept clips (`slom_trenda_tf.mp4`,
`slom-trenda2.mp4`) are entirely about which swing is the "actual/relevant" one and how the same
move reads differently per timeframe. `hasBrokenAndRetested` reasons only about 15m pivots with a
single `swingPivotStrength`. Add an explicit 1h/4h confirmed-swing filter as a declared,
one-variable test.

Note this only affects **Family A**, which currently produces 3-12 trades per symbol. Improving a
gate on a strategy that barely trades is lower value than Step 2 — hence fourth, not first.

### Step 5 — Hook-trigger (Family C) — deprioritized

**Zero of 20 labelled examples** show the hook-trigger as the primary sequence. Our own ground
truth says it is rarer in this material than Families A and B. Build it only if Steps 2-4 stall, or
if extending the labelled set (Step 3) turns up hook examples that are currently missing.

### Step 6 — Fix the evidence standard before opening validation

Before any candidate touches validation data:

1. **Pre-register one candidate** — symbol, family, full config, written down before the run.
2. **Reconsider the 60-trade floor for this payoff shape.** ETH's 92 trades contain only ~30
   winners carrying all the profit; effective sample size is far below the nominal count. A floor
   defined on *winning* trades, or a bootstrap confidence interval on expectancy, would be more
   honest than a raw count.
3. **Prefer walk-forward** over the single train/validation/final split. With 56 training runs
   already spent, a single held-out period is a weak check; rolling-origin evaluation would use the
   data far better.

## 6. Explicitly closed

- Further per-symbol bin-width or `pocBinAtrFraction` tuning (§3).
- BNBUSDT as a Family A or Family B candidate — negative in all five configurations tested.
- Promoting the XRP training pass to validation on its current basis (§3).
