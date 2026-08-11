# Apollo V6 — plan (rewritten 2026-08-11)

**Status: CLOSED 2026-08-11 by the §9 stopping rule. See `APOLLO_V6_OUT_OF_SAMPLE.md`.**
Out-of-sample on 2022-01..2023-02: baseline +$6.53/trade (P 55.0%), aligned -0.03 **-$31.89/trade**
(P 34.8%), absorption +0.03 +$55.58/trade on 28 trades (P 60.4%). Nothing near the 90% threshold.
The post-hoc aligned hypothesis flipped sign; the V5 baseline showed no demonstrable edge.

**Status when written: proposal, not started.** V5 stays frozen and comparable, as V4 was when V5 began.

This supersedes the first V6 draft, which proposed four additional geometric rules. Diagnostics run
after that draft showed it was aimed at the wrong problem; the reasoning is recorded in §2 rather
than quietly dropped.

## 1. Where V5 actually stands

Family B + higher-timeframe filter, unselected 15-symbol universe, training window:

| Metric | Value |
| --- | ---: |
| Trades | 590 |
| Net | +$14,000 |
| Per-trade | +$23.73 |
| Bootstrap P(profit) | 70.2% |
| t-statistic | 0.54 |

**Superseded 2026-08-11 — see `APOLLO_V6_WALKFORWARD.md`.** A half-year breakdown shows the entire
+$14,000 comes from 2023-08..2024-02 (+$14,697); the other eighteen months net -$697 combined. The
+$23.73 per-trade figure below is one favourable regime, not a persistent edge, and every comparison
made against it is measured from an inflated reference.

**Important qualification: these are in-sample figures.** The config was chosen while looking at this
data, so the bootstrap measures sampling noise *within* the fitted sample — not generalisation. The
true out-of-sample expectation is lower than +$23.73, and 70.2% is optimistic rather than
conservative. Every number above should be read in that light.

## 2. The corrected diagnosis

The first draft assumed the problem was edge size in the abstract, and a follow-up hypothesis
blamed execution costs. Both were tested. Costs are **not** the binding problem:

| Symbol | Gross (pre-cost) | Costs | Net | Drag |
| --- | ---: | ---: | ---: | ---: |
| ETHUSDT | +$13,665 | $2,538 | +$11,095 | 19% |
| XRPUSDT | +$15,498 | $1,781 | +$13,776 | 12% |
| AVAXUSDT | +$6,241 | $972 | +$5,214 | 16% |
| LTCUSDT | **-$8,054** | $1,076 | -$9,130 | — |
| BCHUSDT | **-$7,943** | $649 | -$8,592 | — |
| SOLUSDT | **-$3,072** | $810 | -$3,882 | — |

On symbols that work, costs take 12-19% — material but not decisive. **The losers are negative
before any cost at all.** Zero fees would not rescue them.

**The problem is which zones the strategy considers tradeable.** It is a signal-selection problem,
not an execution or sizing problem.

## 3. How to extend — the principle

This session's record is unusually clear about where improvements came from:

| Change | Origin | Outcome |
| --- | --- | --- |
| Family B entry | Source | **worked** (10-25x trades) |
| Higher-timeframe filter | Source (`slom_trenda` captions) | **worked** (-$6,806 → +$14,000) |
| 42-day map lookback | Source (video level persistence) | **worked** (PF up on all four) |
| Third-touch | Source, mis-scoped in implementation | failed |
| Consumed-as-targets | Claimed source support; source contradicts it | failed (-97% trades) |
| ATR-scaled aggregation | Invented | noise |
| Internal-wave target | Invented | failed |
| Granularity, 5 rounds | Invented | noise, repeated sign flips |
| Partial exits | Invented | worse on all three |
| Holding-period sweep | Invented | inconsistent |

Every improvement traced to the source. No invented change helped. That is not an argument against
ever inventing, but it is evidence about hit rates that should shape the method.

**V6's rule: extend by instrumentation, not invention.** The distinction:

- *Invention* — adding an indicator or filter because it might help. Track record above: 0 for 5.
- *Instrumentation* — measuring a concept the source is already about, more accurately than the
  source itself can. The trader eyeballs a volume histogram; we hold the raw aggregate trades that
  histogram is rendered from.

The PDFs define liquidity as **"a concentration of unfilled orders"** (`APOLLO_COURSE_SOURCE_NOTES.md`).
Total traded volume cannot see that. Aggressor data can. Apollo has never used any of it.

## 4. What is sitting unused

`futures_volume_profile_bin` stores `aggressive_buy_quote` / `aggressive_sell_quote` **per price
level**, and `VolumeProfileBin.delta()` already exists. The V5 strategy path reads neither — POC is
computed from total notional only.

`futures_agg_trade_minute` additionally holds size-bucketed aggression:
`large_10k_*`, `large_100k_*`, `large_1m_*` (count, buy quote, sell quote), plus `buy_vwap`,
`sell_vwap`, `max_aggregate_quote`.

The course says liquidity is where **large** participants hold unfilled orders. `large_100k` /
`large_1m` aggressor delta at a zone is the closest measurement of that claim available in public
data.

## 5. Phase 0 — honest baseline before any change

1. **Extend history** to the earliest available per symbol (BTCUSDT/ETHUSDT already reach 2021-08;
   the rest currently start 2022-10).
2. **Walk-forward the existing frozen config** across that history — rolling origin, each window
   genuinely unseen at the time it is evaluated.

Point 2 moved from last to first, and it is the most important change from the previous draft. Every
figure in §1 is in-sample. Until the current config is measured out-of-sample there is no honest
reference to improve *against*, and any Phase 1 result would be compared to an inflated baseline.

If the out-of-sample per-trade edge is materially negative, V6 stops here and the branch closes —
before spending effort on new signals.

## 6. Phase 1 — order-flow instrumentation

Each is a single declared variable, predeclared, tested on all 15 symbols pooled, judged
out-of-sample on per-trade edge. Ordered by expected value.

**1a. Zone aggressor delta.** Classify each mapped zone by net aggression over the bars that formed
it. A zone built by sellers hitting a passive bid while price holds is *absorption*; identical total
volume built by buyers lifting offers is *distribution*. The current POC treats these as the same
zone. They are opposite setups, and this speaks directly to the §2 diagnosis.

**1b. Large-trade delta at the zone.** As 1a, restricted to >=$100k aggressors. The nearest available
proxy for the source's actual definition of liquidity.

**1c. Absorption at the revisit.** Not only what built the zone, but what happens when price returns
to it: heavy opposing aggression that fails to move price is the textbook absorption signature, and
is the moment the entry decision is actually made.

**1d. Acceptance quality.** From the PDFs and still unimplemented in any version: acceptance requires
*"several full-bodied candles, not one or two wick-like candles"* (p. 53). V5 accepts on one
ATR-threshold close plus one acceptance close, with no body-quality test. Cheap, source-mandated.

## 7. Phase 2 — evaluation discipline

1. **Block bootstrap by time period** rather than per-trade. Same-window trades across symbols are
   correlated, so the per-trade bootstrap overstates independence. Current figures are optimistic in
   a way worth quantifying rather than caveating.
2. **Pre-registration** before any validation run, per `APOLLO_V5_PREREGISTRATION.md`.
3. Validation `[2025-02-07, 2025-08-07)` and final test `[2025-08-07, 2026-02-07)` remain unopened.

## 8. Explicitly out of scope

- **Cost engineering** — measured at 12-19% on working symbols; losers are negative pre-cost.
- **More symbols as the primary lever** — needs ~137 for significance at the current edge; symbols
  beyond the present 15 are progressively less liquid, and liquidity correlated positively with
  performance (rho +0.46).
- **Family C (hook-trigger)** — zero of the labelled examples show it as the primary sequence.
- **Re-tuning any V5 parameter** — five granularity rounds produced sign flips, not improvement.
- **Further changes measured only in-sample** — the failure mode §5 exists to prevent.

## 9. Stopping rule, declared in advance

If Phase 1 does not produce a **positive out-of-sample per-trade edge with a block-bootstrap
P(profit) above 90%**, V6 closes with the finding: a small real effect, not establishable on
available data.

Note this is deliberately stated out-of-sample. The previous draft's stopping rule ("triple the
per-trade edge") was an in-sample target and would have been satisfiable by overfitting.
