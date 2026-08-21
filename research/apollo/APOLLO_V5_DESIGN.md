# Apollo V5 — multi-day map search, volume-first ranking, third-touch discount

## Relationship to V4

V5 is a separate, parallel strategy (`apollo-v5-base-poc-continuation`,
`ApolloV5BasePocContinuationStrategy`, `VariableBaseDetectorV5`,
`VolumeProfileFeatureAssemblerV5`). `APOLLO_V4_DESIGN.md` and the V4 code/config
are unchanged; V5 exists so the two remain independently comparable rather than
overwriting V4's frozen behavior in place.

V5 inherits V4.1's entire ordered state machine (base → base-only profile →
accepted breakout → first revisit consumes the base → sweep/reclaim plus a
completed lower-timeframe swing reversal → continuation entry) unchanged. It
changes only how the base itself is found and ranked.

## What changed and why

A source review that watched the labelled concept clips and sampled daily
BTC/ETH chart videos alongside the two course PDFs found three concrete gaps
between V4.1's code and the material:

1. **Multi-day map search.** V4's `VariableBaseDetector` only ever anchors a
   candidate window immediately before its own breakout bar and cannot scan
   past `maximumBaseBars` (48 candles / 12 hours), regardless of how the map
   accumulates over time; `baseMapLookbackDays` was named in
   `APOLLO_V4_DESIGN.md` as the intended fix but was never implemented.
   `VariableBaseDetectorV5.detectCandidates()` searches up to
   `strategy.baseMapLookbackDays` (7, a first hypothesis) days back, using
   cached prefix sums and a sparse table so the wider scan stays tractable.
   Width and drift ATR bounds scale by `sqrt(bars/maximumBaseBars)` beyond the
   original 48-candle reference scale, so a week-long candidate is judged
   against a volatility envelope rather than a fixed short-term ATR multiple.
2. **Volume-first base ranking.** V4 chooses base *shape* first (the largest
   valid geometric window) and checks volume concentration only as a
   pass/fail gate afterward. `VariableBaseDetectorV5` returns every
   geometrically valid candidate, and `VolumeProfileFeatureAssemblerV5`picks
   the one with the strongest POC concentration. A raw POC-share comparison is
   not meaningful across window sizes (a longer window always spreads volume
   over more price levels), so the multi-day candidate is preferred whenever
   one clears the breakout/touch gates, with POC share used only to rank
   within a scale tier — otherwise the wider search is silently outranked by
   the short window on every occasion and never changes anything (confirmed
   by instrumentation before this tie-break was added).
3. **Third-touch discount.** Книга 2.0 p.98 explicitly warns against entering
   the third touch of a horizontal boundary; the `xrp.mp4` course clip shows
   exactly this pattern (three circled touches before the real reversal).
   Neither V4's base detector nor its strategy counted prior approaches to a
   boundary at all. Each V5 candidate now reports `highTouches`/`lowTouches`
   (contiguous approaches within the existing `boundaryPenetrationAtr`
   tolerance); `strategy.maximumBoundaryTouches=2` discounts a breakout whose
   own boundary was already tested three or more times before it broke.

## Verification that the mechanism works

Instrumented (temporary, not part of the frozen run) verification over the
two-year BTC training window: the wider search evaluates 22,269 geometrically
valid multi-day candidates (vs. 31,868 short-scale), of which 2,796 pass the
breakout-confirmation check, 1,225 survive the third-touch filter, and all
1,225 produce a valid profile. With the frozen strategy thresholds
(`minimumBreakoutVolumeRatio=1.20`, `minimumZoneShare=0.02`,
`minimumPocShare=0.05`, `minimumBaseVolumeRatio=1.20`, `minimumRewardRisk=3`)
this does not change the final trade count relative to a lookback-disabled
run, confirming those downstream gates — not base recency or selection — are
the binding constraint. Loosening them as a one-off, non-frozen sanity check
(not a proposed configuration) raised the count from 2 to 54 trades, proving
the multi-day map and third-touch discount do reach real trades once the
other filters allow it.

## Frozen results (2026-08-10)

- Training `[2023-05-07, 2025-05-07)`: 2 trades, +$329.80 net (+0.33%),
  PF 1.63, 0.63% maximum drawdown, one win ($852.68) and one loss (-$522.88).
  Far below the 60-trade evidence floor; not a validation candidate.
- Post-final-test video-review window `[2026-05-07, 2026-08-01)` (86 days,
  where V4.1 produced a flat zero-entry false negative): 126 bases mapped
  (10 multi-day), 118 consumed, still 0 completed trades. The map now
  functions where it previously produced nothing, but the swing-reversal and
  reward/risk gates remain the bottleneck there.

Neither run justifies threshold tuning. The next legitimate step is comparing
the now-functioning multi-day map against labelled base examples, per the
existing `APOLLO_COURSE_SOURCE_NOTES.md` procedure — not further changes to
the confirmation/target rules.

## Configuration

`config/backtests/apollo-v5-btc.properties` mirrors
`config/backtests/apollo-v4-btc.properties` with two additions:
`strategy.baseMapLookbackDays=7` and `strategy.maximumBoundaryTouches=2`.

## Nine-symbol training results (2026-08-10)

BTCUSDT, ETHUSDT, SOLUSDT, XRPUSDT, BNBUSDT, ADAUSDT, DOGEUSDT, TRXUSDT, and
LINKUSDT were the first nine symbols with full aggregate-trade and
volume-profile-bin coverage. Each ran independently (no shared capital) over
the frozen training window `[2023-05-07, 2025-05-07)`:

| Symbol | Trades | Win rate | Net PnL | PF | Max DD |
| --- | ---: | ---: | ---: | ---: | ---: |
| BTCUSDT | 2 | 50.0% | +$329.80 | 1.63 | 0.63% |
| ETHUSDT | 7 | 28.6% | -$288.15 | 0.81 | 1.81% |
| BNBUSDT | 3 | 66.7% | +$67.40 | 1.13 | 0.89% |
| SOLUSDT | 3 | 0% | -$382.55 | 0.00 | 0.62% |
| XRPUSDT | 0 | — | $0 | — | 0% |
| ADAUSDT | 0 | — | $0 | — | 0% |
| DOGEUSDT | 0 | — | $0 | — | 0% |
| TRXUSDT | 0 | — | $0 | — | 0% |
| LINKUSDT | 0 | — | $0 | — | 0% |

Independent-account aggregate: 15 trades, -$273.51 net. Five of nine symbols
produced zero entries over the full two years; SOL is a clean loser (0/3, PF
0). No symbol individually clears the 60-trade evidence floor. This repeats
the project's established pattern (e.g. the B5/C1 expanded-universe
rejection): a proxy calibrated by eye on BTC does not transfer to a broader
universe, and adding symbols mostly adds symbols where the detector finds
almost nothing rather than diversifying real edge.

## Next research steps (2026-08-10)

The nine-symbol result, combined with the instrumented finding above that the
swing-reversal and reward/risk gates — not the base map — are now the binding
constraint, sets the priority order for what comes next. In order:

1. **Labelled base/entry dataset.** Every mechanical Apollo version to date
   (v2, three different v3 proxies, V4, V4.1, V5) has been tested against
   price data but never checked against real course examples. This is the
   step `APOLLO_COURSE_SOURCE_NOTES.md` has called for since it was written
   and it has still never been done. Build ~30 labelled examples from the
   course videos (base start/end, boundaries, POC, entry family, entry/stop/
   target) and check whether V5's detector actually finds those bases before
   changing anything else.
2. **Liquidity/POC-limit entry family.** `APOLLO_COURSE_SOURCE_NOTES.md`
   Family B: a limit order near the principal/internal volume wave, stop
   behind the whole zone plus one quarter of its height, no swing-reversal
   requirement. V5 only implements Family A (trend-break/structure retest).
   Since the swing-reversal gate is the proven bottleneck, a second entry
   family that does not depend on it is a source-grounded way to find more
   samples without loosening any existing rule.
3. **Hook-trigger entry family.** Family C: enter on completion of the early
   pullback/hook, first target before the setup-timeframe trend break,
   minimum 1:3 risk/reward. Also currently unimplemented.
4. **Multi-timeframe swing hierarchy.** `hasBrokenAndRetested` only reasons
   about 15m pivots; it has no concept of an intact higher-timeframe
   structure the local break should not fight. Both `slom_trenda_tf.mp4` and
   `slom-trenda2.mp4` are centrally about this distinction. Add an explicit
   1h/4h confirmed-swing filter.

Explicitly out of scope for "improvement": widening `minimumRewardRisk` or
the volume-ratio thresholds to manufacture more trades. The 54-trade run
used earlier to prove the pipeline mechanically works was a sanity check, not
a candidate configuration, and this project's standing rule is that
threshold changes made after seeing a result are not evidence of anything.

## Family B: liquidity/POC-limit entry (2026-08-10)

`ApolloV5LiquidityLimitStrategy` (`apollo-v5-liquidity-limit`,
`config/backtests/apollo-v5-liquidity-limit-btc.properties`) implements
`APOLLO_COURSE_SOURCE_NOTES.md` Family B. It reuses V5's map exactly
(`VariableBaseDetectorV5`, `VolumeProfileFeatureAssemblerV5.mergePersistentBases`,
unchanged) — the only difference from Family A
(`ApolloV5BasePocContinuationStrategy`) is the entry decision: it acts
directly on the zone's first revisit once price reclaims it, without waiting
for `hasBrokenAndRetested`'s completed lower-timeframe swing reversal. Stop
and target selection are identical to Family A (whole zone + 25% height;
next mapped liquidity zone; same volume-quality and reward:risk gates).

This was motivated by the label-comparison finding above: the one real,
exact-price course example we could recall-check doesn't show a distinct
prior "breakout away, then later revisit" sequence — price simply
approaches and sweeps a pre-existing mapped zone directly, which is Family
B's shape, not Family A's.

### Nine-symbol training results — first Apollo candidate to clear the trade-count floor

| Symbol | Trades | Net PnL | PF | Max DD | Trade count | Net profit |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| ETHUSDT | 92 | +$4,408.26 | 1.13 | 6.57% | **PASS (>=60)** | **PASS** |
| BNBUSDT | 37 | +$5,566.56 | 1.56 | 4.04% | fail | PASS |
| BTCUSDT | 34 | -$835.21 | 0.94 | 5.50% | fail | fail |
| SOLUSDT | 11 | +$324.73 | 1.12 | 4.54% | fail | PASS |
| XRPUSDT, ADAUSDT, DOGEUSDT, TRXUSDT, LINKUSDT | 0 | $0 | — | 0% | fail | fail |

Family A's swing-reversal gate was confirmed as the dominant bottleneck: removing it (while
keeping every volume-quality and reward:risk gate identical) took ETH from 7 trades to 92, BNB
from 3 to 37, BTC from 2-3 to 34. **ETHUSDT passes 6 of 8 acceptance criteria** — net profit,
profit factor (1.13), maximum drawdown, **trade count (92 >= 60, the first Apollo variant of any
kind in this project to clear that floor)**, average win/loss ratio, and stressed-cost net profit.
It fails on subperiod stability (2 of 4 profitable, need 3) and concentration (83.5% of profit
from one subperiod, cap is 60%) — subperiod 1 lost -$3,332.77 while subperiod 2 alone made
+$6,988.74. This is not a validation candidate: stability/concentration failure is exactly the
project's standing signal that a result is not yet robust, regardless of how the other six
criteria look, and validation/final-test data remain unopened.

The five symbols with zero trades under Family A are still zero under Family B — the block for
those is upstream at the map/volume-quality-gate level (bases never clear
`minimumZoneShare`/`minimumPocShare`/`minimumBreakoutVolumeRatio`), not at the entry-timing choice
this step changed. Worth investigating on its own terms (likely a liquidity-depth effect: thinner
aggregate-trade volume on these symbols may structurally produce lower POC/zone concentration
regardless of a real base being present) rather than folded into Family C or the swing-hierarchy
work.

## Root cause of the zero-trade symbols: a degenerate global price-bin width (2026-08-10)

The block was not liquidity depth. `futures_volume_profile_bin` was built with a single hardcoded
`profilePriceStep=$10` for every symbol. XRPUSDT ($0.32-3.66), ADAUSDT ($0.14-1.32), DOGEUSDT
($0.055-0.48), and TRXUSDT ($0.049-0.44) all trade entirely inside `[$0, $10)` for the whole
training window, so **every bucket's entire volume collapsed into one bin at price_from=0** -
confirmed directly: `COUNT(DISTINCT price_from)=1` for all four. The resulting mapped zone is
therefore always exactly `[0, 10)`, and both strategies require reclaim *beyond* the zone
(`close>zoneHigh` long, `close<zoneLow` short) - `close>10` and `close<0` are both mathematically
impossible for these coins. LINKUSDT ($4.93-30.76) wasn't fully degenerate but only spanned 4
possible bins across its whole range.

Fixed with `VolumeProfilePriceSteps` (new,
`src/main/java/.../database/VolumeProfilePriceSteps.java`): a declared, per-symbol default step
chosen from each symbol's real training-window price range (not computed algorithmically at
runtime, matching this project's convention of explicit declared thresholds), wired as the default
in `VolumeProfileBinImportApplication`, `BacktestApplication`, and `HistoricalVolumeProfileApplication`
(still overridable via `-DprofilePriceStep`, and it throws for an undeclared symbol rather than
silently reusing $10). The five affected symbols' stale $10-step bins (632,870 rows) and manifest
entries were deleted and re-imported from the already-downloaded local archives at their correct
step, producing 402-674 distinct price levels each instead of 1-4.

### Nine-symbol re-run after the fix

| Symbol | Family A trades/net | Family B trades/net/PF | Notes |
| --- | --- | --- | --- |
| XRPUSDT | 7 / +$96.61 | **60 / +$10,146.42 / PF 1.55** | **Passes all 8 acceptance criteria** - first in this project's history. See caveats below. |
| ADAUSDT | 4 / -$1,667.50 | 9 / +$10,180.08 / PF 7.11 | Far below evidence floor either way; PF is not meaningful on 9 trades. |
| DOGEUSDT | 3 / -$463.35 | 38 / -$8,814.23 / PF 0.39 | `MAX_DRAWDOWN` termination (10.10%). Rejected. |
| TRXUSDT | 1 / +$273.17 | 37 / -$6,060.82 / PF 0.58 | Rejected. |
| LINKUSDT | 8 / -$1,064.24 | 69 / -$5,140.14 / PF 0.79 | `MAX_DRAWDOWN` termination (10.02%). Rejected despite clearing trade count. |

### XRPUSDT Family B: passes training acceptance, with real caveats

All 8 criteria pass: net +$10,146.42 (+10.15%), PF 1.55, 7.21% max drawdown, 60 trades, 3 of 4
profitable subperiods, 54.3% largest-subperiod contribution (cap 60%), 2.17 average win/loss,
positive stressed-cost profit. This is the first strategy of any kind in this project's history to
pass every training acceptance criterion. It is not being treated as more than a promising lead,
for concrete reasons visible in the diagnostics:

- **`Termination: PROFIT_TARGET_REACHED`.** The account hit the +10% prop profit target and stopped
  trading. The monthly calendar ends at 2025-01; **the last ~3 months of the training window
  (Feb-Apr 2025) were never actually evaluated.**
- **The edge is almost entirely short-side**: SHORT net +$9,856.83 (30 trades, 46.7% win) vs. LONG
  net +$289.60 (30 trades, 36.7% win) - longs are barely breakeven.
- **Subperiod 4's PF of 12.66 is 70% one calendar month** (December 2024 alone contributed
  +$6,992.54 of that subperiod's +$9,986.62). The subperiod-level concentration check (54.3% < 60%
  cap) does not catch concentration *within* a passing subperiod.
- **Subperiod 3 lost -$6,039.42 at PF 0.12** (16 trades) - a genuine bad stretch sits in the middle
  of the otherwise-passing run.
- Max consecutive losses = 13; 32 of 60 trades are stop-losses (0% win, as expected structurally)
  offset by a minority of large winners (22 holding-period-expired exits at 86.4% win rate, 5 take-
  profits at 100%) - the same fragile "many small losers, few large winners" shape that made ETH's
  result fail stability, just barely clearing the bar here instead of missing it.

Validation and final-test data remain closed. A training pass is the gate to *consider* opening
validation, not a result to act on by itself, and this project's own history (RSI/ATR, structural
channel, and several Apollo variants) has repeatedly shown a training-only pass does not reliably
survive contact with held-out data.
