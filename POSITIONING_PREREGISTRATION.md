# Positioning signals — direction prediction pre-registration (2026-08-12)

Written **before running any test**. Signals and the bar are fixed here.

## Why

`futures_metric_snapshot` holds 6M rows - open interest, top-trader long/short ratio, taker buy/sell
ratio at 5-minute resolution across 833 symbols - imported today and never used for anything.

It matters now because a corrected simulation shows a directional edge is worth **+13 points** on the
prop challenge at 0.5x leverage (40.9% -> 54.1% at a 60% hit rate). An earlier simulation said
direction was worth half a point; that was a bug and the conclusion was wrong.

## Base rate

In 110,190 liquid symbol-days since 2024-06, **48.6%** closed above their open. That, not 50%, is
what a signal must beat.

## Signals, declared before testing

1. **Top-trader position ratio extreme.** Crowded positioning among large accounts is expected to be
   contrarian: an extreme long ratio precedes down days.
2. **Open interest change against price change.** Rising OI with rising price is new longs;
   rising OI with falling price is new shorts; falling OI is unwinding.
3. **Taker buy/sell volume ratio extreme.** Aggression imbalance, expected contrarian at extremes on
   the same logic as (1).

Each is tested alone, on next-day direction, by decile. No combinations, no interactions - those come
only if a single signal survives.

## Bar

- Hit rate at the extreme decile **>= 55%** (against the 48.6% base rate), and
- the direction of the effect is **consistent in at least 4 of 5 years**.

One year carrying the result is the failure mode documented eleven times in this repository.

## Forbidden

- Choosing the decile cut after seeing results.
- Reporting the best of the three as though it were the only one tested.
- Dropping 2022 or any other period as anomalous.

---

# RESULT (2026-08-12): ALL THREE REFUTED

| Signal | Best cell | Base rate | Bar |
| --- | --- | --- | --- |
| 1. Top-trader ratio | 51.9% up (decile 1), 48.8% (decile 10) | 48.9% | >=55% FAIL |
| 2. OI vs price | 46.6% up after "new longs" = **53.4% for a short** | 51.1% down | >=55% FAIL |
| 3. Taker ratio | 51.9%, no monotonic gradient | 48.9% | >=55% FAIL |

Signal 1 moves in the predicted contrarian direction but the extreme decile lands on the base rate.
Signal 3 is noise. Signal 2 is the only one with a coherent gradient and a real effect: fresh long
crowding (rising OI with rising price) precedes a down day 53.4% of the time, **+2.3 points** over
base. At 0.5x leverage that is worth roughly +4 points on the challenge - genuine, and under the bar.

**Coverage limitation, disclosed:** `futures_metric_snapshot` covers **15 symbols, not 833**. The
importer's default symbol list was the old universe and was never widened. The test therefore ran on
~21,000-25,000 symbol-days rather than the full panel. Importing metrics for all 833 symbols would
give roughly 40x the sample and could move a 53.4% estimate materially in either direction - but
re-testing a refuted signal on more data, after seeing this result, is exactly the move this
repository has documented as a mistake eleven times. It would need a fresh pre-registration.

Nothing here is adopted. No signal is used in the live monitor.
