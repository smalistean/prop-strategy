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
