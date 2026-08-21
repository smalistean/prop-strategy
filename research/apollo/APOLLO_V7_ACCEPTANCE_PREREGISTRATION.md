# Apollo — acceptance quality: pre-registration (2026-08-11)

Written **before the feature was implemented and before any backtest was run.** Thresholds are fixed
here and may not be changed to improve a result. If they are changed, this document records that the
result is a tuned one and must be reported as such.

## Why this and not something else

`APOLLO_V6_OUT_OF_SAMPLE.md` closed V6: the post-hoc aligned-delta hypothesis flipped sign
out-of-sample, and the V5 baseline showed no demonstrable edge. The one category with a positive
track record in this project is changes derived from the course source; every invented change has
failed, including the most convincing one.

This is a source-mandated rule that no Apollo version has ever implemented.

## The source requirement

> *"A breakout/retest entry requires real acceptance beyond the base: several full-bodied candles,
> not one or two wick-like candles"* (p. 53)

> *"Price has moved beyond a level and formed several full-bodied candles there, or a pair of impulse
> candles"* (p. 3)

## What V5 does today

Acceptance is exactly two candles: a breakout candle closing beyond `base boundary ± ATR x
breakoutAtr`, and the next candle closing beyond the raw boundary. **Neither is tested for body
quality.** Two long-wicked dojis satisfy the rule identically to two impulse candles - precisely the
case p. 53 says to reject.

## Declared thresholds

`APOLLO_COURSE_SOURCE_NOTES.md` (lines 161-162) explicitly lists "the number/body size of acceptance
candles" among the parameters the book does not define. The structure is the source's; the numbers
are mine, and are therefore fixed in advance:

| Parameter | Value | Justification - stated before any run |
| --- | --- | --- |
| `acceptanceMinimumBodyFraction` | **0.50** | The plain reading of "full-bodied" versus "wick-like": more body than wick. Not selected by search; 0.50 is the only non-arbitrary point on the scale. |
| `acceptanceMinimumBodyCandles` | **2** | p. 3's "a pair of impulse candles". Also the number of candles the existing acceptance window already contains, so the rule filters the existing window rather than extending it. |
| direction | must match break side | "Impulse" implies closing in the direction of the break: bullish body for an upward break, bearish for a downward one. |

`acceptanceMinimumBodyFraction = 0` disables the filter and reproduces the frozen V5 baseline exactly.

Body fraction is `|close - open| / (high - low)`, with a zero-range candle treated as failing.

## Predictions, registered in advance

1. **Trade count falls.** A filter cannot add setups. If trade count rises, the implementation is wrong.
2. **Per-trade edge rises in-sample.** This is the hypothesis. If per-trade edge falls in-sample, the
   rule is refuted immediately and will be reported as such, not re-tuned.
3. **The direction of the in-sample change is repeated out-of-sample.** This is the real test.
   V5's history is full of in-sample gains that did not survive; an in-sample gain alone is not a result.

## Evidence standard

- Judged on the **15-symbol unselected universe**. No symbol subsets, no tier selection.
- **Block bootstrap**, not per-trade: the 2026-08-11 comparison showed per-trade resampling overstates
  confidence by up to 6 points because same-week trades across correlated symbols are not independent.
- Training window first, then a **single** confirmatory run on 2022-01-01..2023-02-07.
- One threshold, one run per window. No sweep. A sweep over body fractions would recreate exactly the
  multiple-comparisons problem that produced the aligned-delta failure.

## Stopping rule

If the acceptance filter does not improve per-trade edge **in both windows**, it is recorded as
refuted and V5 keeps `acceptanceMinimumBodyFraction = 0`. A gain in one window only counts as a
failure, not as partial support.

---

# RESULT (same day, 2026-08-11): REFUTED

Training window, 15 symbols unselected, block bootstrap:

| Config | Trades | Net | Per-trade | Win | Blocks | P(profit) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline (filter off) | 590 | +$14,000 | **+$23.73** | 32.4% | 26 | 68.3% |
| acceptance body >= 0.5 | 133 | +$2,936 | **+$22.08** | 28.6% | 25 | 54.5% |

Against the registered predictions:

1. **Trade count falls — confirmed.** 590 -> 133, a 77% cut. The filter behaves as a filter, and
   `acceptanceMinimumBodyFraction=0` reproduced the frozen baseline trade-for-trade on BTCUSDT
   out-of-sample (24 trades, +$5,453.22, identical line by line), so the wiring is sound.
2. **Per-trade edge rises in-sample — FAILED.** It fell, 23.73 -> 22.08.

Prediction 2 was declared as an immediate refutation condition, so the rule is refuted here. No
threshold sweep was run. The out-of-sample confirmation was **deliberately not run**: the stopping
rule requires improvement in both windows, so a second window cannot change the verdict, and running
it could only serve as a search for a rescuing result - the precise behaviour that produced the
aligned-delta failure in `APOLLO_V6_OUT_OF_SAMPLE.md`.

`acceptanceMinimumBodyFraction` stays at 0 in every committed config. The parameter is retained
rather than removed so the negative result stays reproducible.

## What the failure mode tells us

The filter discarded 457 trades and per-trade edge moved by $1.65. The rejected trades had
essentially the same expectancy as the kept ones, so **candle body quality at acceptance carries no
information about trade outcome** here. This is not a badly calibrated filter; it is a filter on a
variable unrelated to profit.

## Cost to the "derive from the source" heuristic

This test was chosen because source-derived changes had a perfect record while invented ones were
0-for-5. This change was source-mandated (p. 53 is explicit), implemented faithfully, and had its
thresholds declared before implementation - and it did nothing.

The heuristic must therefore be restated: source-derived changes have a *better* record, not a
reliable one. Two have now failed - the third-touch rule (mis-scoped, see
`config/backtests/apollo-v5-btc.properties`) and this one.

## Deliberately not pursued

The source says "several" full-bodied candles; this test used 2, taken from p. 3's "a pair of impulse
candles", and the existing acceptance window is only 2 candles long. A longer acceptance window with
a higher required count is a genuinely different rule and might behave differently.

Testing it now, having seen this result, would be threshold-shopping under another name. If it is
worth doing, it requires its own pre-registration and its own unseen window.
