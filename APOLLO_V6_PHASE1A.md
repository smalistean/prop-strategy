# Apollo V6 Phase 1a — zone aggressor delta (2026-08-11)

First order-flow instrumentation test. Status: **in-sample lead, awaiting out-of-sample check.**

## What was built

`futures_volume_profile_bin` has stored `aggressive_buy_quote` / `aggressive_sell_quote` per price
level since the schema was created, and `VolumeProfileBin.deltaQuote()` already existed. No Apollo
version had ever read either. POC was computed from total notional only, which cannot distinguish
who was crossing the spread.

`VolumeProfileFeatureAssemblerV5.profile()` now also returns the zone's net aggressor delta,
normalised by the zone's own traded volume so it is comparable across symbols and periods, exposed
as `FeatureKey.selectedBaseDelta()`. `ApolloV5LiquidityLimitStrategy` gained
`strategy.minimumAbsorptionDelta` (0 disables).

## Two competing hypotheses

**Absorption (predeclared).** A zone that held while one side aggressed was absorbed by passive flow
on the other side, and the passive side later wins. A LONG therefore wants a zone built on *sell*
aggression: `delta <= -X`. Encoded as **positive** config values.

**Aligned (post-hoc).** The opposite: a LONG wants *buy* aggression, `delta >= +X` — delta pointing
the same way as the trade. Encoded as **negative** config values, deliberately so the two remain
distinguishable in the record.

The aligned hypothesis was formed **after** inspecting the delta distribution, and is recorded as
post-hoc. It was not predicted in advance.

## Observed delta distribution (before any backtest)

Pooled over 618 candidate entries on four symbols: range -0.162 to +0.355, median -0.018, 64%
negative. Not degenerate — a live, well-spread variable.

The tilt that prompted the second hypothesis: LONG zones averaged **+0.016**, SHORT zones
**-0.041**. Both lean *opposite* to what absorption predicts. Only 14% of shorts satisfied the
absorption condition at all.

## Results — 15 symbols pooled, unselected, training window

| Config | Trades | Net | Per-trade | P(profit) per-trade boot | **P(profit) BLOCK boot** |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline (delta off) | 590 | +$14,000 | $23.73 | 70.1% | **68.3%** |
| absorption +0.01 *(predeclared)* | 153 | +$6,908 | $45.15 | 68.4% | — |
| absorption +0.03 *(predeclared)* | 94 | +$7,549 | $80.31 | 75.2% | **75.4%** |
| aligned -0.01 *(post-hoc)* | 357 | +$21,268 | $59.57 | 84.2% | **80.5%** |
| **aligned -0.03** *(post-hoc)* | 258 | **+$24,503** | **$94.97** | 90.4% | **84.1%** |

**The block-bootstrap column is the one to trust.** The per-trade bootstrap resamples individual
trades as independent, but fifteen correlated crypto symbols trading the same setup in the same week
are close to one observation, not fifteen. Resampling whole four-week blocks preserves that
correlation. The 258 aligned trades occupy only **26 blocks** - that is the effective sample size.

The correction is largest exactly where it matters most: the aligned configuration lost **6.3
points** (90.4% → 84.1%), while absorption barely moved because its 94 trades were already spread
thinly across 24 blocks with little clustering to correct.

**The predeclared hypothesis failed.** Absorption raises per-trade edge but cuts volume so severely
that net profit *falls* and confidence barely moves.

**The post-hoc hypothesis is strong but short of the bar.** Per-trade edge roughly 4x
(23.73 → 94.97) and net up 75%; per-trade $94.97 exceeds the ~$70 that `APOLLO_V6_PLAN.md`
calculated as necessary. But on the correlation-aware block bootstrap, confidence is **84.1%**, not
the 90.4% the per-trade resample suggested — below the plan's >90% stopping-rule threshold, and that
is an *in-sample* figure, which is optimistic by construction.

## Why this may be real rather than fitted

- **Monotonic dose-response.** Both -0.01 and -0.03 improve per-trade edge *and* confidence, in
  order. A single lucky threshold would not behave this way.
- **A structural mechanism that predicts the observed direction.** V5 zones only enter the map after
  price *broke out of them*. The construction therefore pre-selects zones where one side already won
  a fight, which is a continuation setting. Absorption theory describes reversals at untested
  levels — a framework this zone-construction actively selects against. The predeclared hypothesis
  applied a reversal model to a continuation-selected sample.

## Why it is not yet a finding

- Post-hoc direction, chosen after seeing the distribution.
- In-sample: the config was selected on the same window it is measured on.
- Four configurations compared.

**Required next step:** re-test on 2021-10..2022-10, which was not in the database when this
hypothesis was formed. If the aligned effect survives on data it was never derived from, it is real.
If it evaporates, it was pattern-matching. That test is blocked only on the Phase 0 import.

## Incidental fix: Binance archive format change

Phase 0's history extension failed on every pre-2022 archive with "Unexpected aggregate-trade CSV
header". Cause: Binance monthly aggregate-trade files from roughly 2022 onward begin with a
`agg_trade_id,price,...` header row; older files start directly with data. All three readers
(`AggregateTradeArchiveReader`, `AggregateTradePriceBinReader`, `HistoricalVolumeProfileReader`)
required the header and threw otherwise.

**This made any history extension before ~2022 impossible in this codebase**, not just for this run.
Fixed by peeking at the first line with `mark`/`reset` and rewinding when it is data, so both
layouts parse identically and the existing parse loops are untouched. Verified against a real
2021-10 archive: 4,352,065 rows, 44,640 minutes, zero gaps, zero duplicates.
