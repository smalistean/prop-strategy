# XVF Capital-Return Improvement Plan

**Document type:** Improvement and measurement plan  
**Status:** Plan — hypotheses below must be measured before adoption  
**Date:** 2026-08-21

## Goal

Improve XVF's net return on total capital by selecting pairs on expected net P&L, reducing
commissions and turnover, using venue collateral more efficiently, avoiding adverse basis setups,
and considering leverage only after the unleveraged edge is robust.

## Current starting point

| Measurement | 2024-08-21–2025-08-21 | 2025-08-21–2026-08-21 |
|---|---:|---:|
| Funding minus modeled commissions | about 2.08% | about 4.71% |
| Including historical basis proxy | about 0.88% | about 1.79% |
| Bybit-maker fee-only scenario | about 2.24% | about 5.19% |
| Pair-specific fee hurdle, excluding basis | about 2.40% | about 5.80% |

XVF appears to have a positive funding edge, but relative-price movement and trading costs consume
a large part of it. The first target is better selection and capital efficiency, not more exposure.

## What is reducing returns

### 1. The annualised signal is larger than realised earnings

The trailing spread is annualised, while a position is held for only several days. Funding rates
change after entry, so a 20% annualised signal does not mean the position will earn 20% annually.
The signal has also overpredicted forward realised funding. Ranking should therefore use a
calibrated funding estimate rather than the headline percentage alone.

### 2. Basis movement can dominate funding

A 20% annualised gap held for three days is about 16.4 bp before costs. A 1% relative movement
between the contracts is 100 bp. Preliminary evidence indicates:

- shorting the relatively expensive contract often produced favourable basis P&L;
- shorting the relatively cheap contract usually produced adverse basis P&L; and
- a large funding gap alone did not reliably predict convergence profit.

### 3. One entry threshold ignores different venue costs

| Venue pair and route | Approximate three-day break-even annual spread |
|---|---:|
| Binance–Hyperliquid | 18.62% |
| Binance–Bybit | 27.50% |
| Bybit–Hyperliquid, current route | 32.00% |
| Bybit–Hyperliquid, Bybit maker | 27.50% |

The same 20% signal can therefore be profitable for one pair and fee-negative for another.

### 4. Gross-spread ranking can choose the wrong pair

The widest funding pair for a base is not necessarily the best trade after fees, executable basis,
liquidity, and venue capital. A slightly smaller gap can have a higher expected net return.

### 5. Turnover consumes funding

Closing and reopening an unchanged or marginally better position pays another round of fees. A
replacement should be made only when its expected advantage covers the full switching cost.

### 6. Capital becomes stranded at the wrong venue

The required venue mix changed materially between the two periods. A single fixed allocation is
unlikely to remain optimal: one venue can block attractive pairs while collateral remains unused at
another.

### 7. Twenty positions can become a quota

Twenty should be a maximum. If only eight candidates cover their complete expected cost, unused
capital should remain cash rather than funding twelve weak positions.

### 8. Leverage amplifies the least certain component

Leverage scales funding, but also basis divergence, fees, slippage, collateral depletion, and
liquidation risk. It should follow an improvement in unleveraged basis-inclusive return.

## Improvement 1: Pair-specific expected-net hurdle

For each new candidate estimate:

```text
expectedNetBps = expectedFundingBps
               + expectedBasisPnlBps
               - entryFeeBps
               - exitFeeBps
               - entrySlippageBps
               - expectedExitSlippageBps
               - riskBufferBps
```

The first simple version should require expected funding to exceed the complete planned round-trip
fee. Apply this only to new entries; a retained position has already paid its entry cost.

Measure positions rejected, funding sacrificed, fees saved, turnover, basis P&L, total return, and
average deployed capital by venue pair and period. This is the highest-confidence improvement
because the provisional fee-hurdle replay improved both periods.

## Improvement 2: Add basis direction to symbol selection

Normalize multiplier contracts, then calculate:

```text
entryBasisBps = (short executable price / long executable price - 1) * 10,000
```

Test these buckets:

- short contract more than 5 bp expensive;
- within plus or minus 5 bp; and
- short contract more than 5 bp cheap.

Split them again by funding-gap quartile and venue pair. Report trade count, funding, basis P&L,
fees, total P&L, median, hit rate, and worst 5% outcome for each annual period.

Then replay the complete book after penalising or excluding adverse-basis candidates. The likely
edge is alignment between funding direction and price dislocation—not simply a large funding gap.

## Improvement 3: Choose maker route by expected execution cost

Evaluate both possible one-maker routes:

```text
route cost = maker fee
           + taker fee
           + size-specific taker slippage
           + maker non-fill probability * fallback cost
           + adverse-selection markout
```

The main opportunity is Bybit–Hyperliquid:

```text
Current:     Hyperliquid maker + Bybit taker = 11.8 bp
Alternative: Bybit maker + Hyperliquid taker =  8.1 bp
Nominal saving:                              =  3.7 bp
```

Compare current routing, Bybit-maker, minimum-cost feasible routing, all-taker, and both-maker as an
upper bound. Calculate the maker fill probability and maximum Hyperliquid slippage at which the
3.7 bp saving survives.

## Improvement 4: Construct the book with venue-capital constraints

Choose candidates by expected net USD while accounting for the two venue-capital slots each pair
consumes:

```text
maximize total selected expected net USD

subject to:
  selected pairs <= 20
  one pair per normalized base
  required leg capital <= usable capital at each venue
  all funding, basis, fee, liquidity, and sizing gates pass
```

Compare a simple capital-aware ranked backfill with a small integer optimizer. For every rebalance
report desired/funded legs, used/unused capital, capital-rejected candidates, expected value lost to
stranded collateral, average live pairs, and realised return on total and deployed capital.

## Improvement 5: Capital distribution

Use equal thirds as the control. Compare:

- equal thirds;
- 40% Binance / 25% Bybit / 35% Hyperliquid as a challenger;
- a coarse grid of fixed allocations; and
- equal starting capital with conditional transfers.

Report both periods independently. A transfer should occur only when one venue blocks positive
expected-net candidates, another has genuinely free collateral, and the additional expected profit
exceeds transfer fees, delay, turnover, and a safety margin. Include minimum transfer, cooldown,
delay, and collateral unavailable in transit.

A fixed split should advance only if it helps both periods without materially increasing
concentration. Otherwise equal thirds remains the control.

## Improvement 6: Treat 20 positions as a maximum

Test maximum books of 10, 15, and 20 while keeping leg notional fixed at USD 112.50. Do not
redistribute unused capital among fewer positions.

Measure funding, fees, basis P&L, turnover, and marginal return for ranks 1–5, 6–10, 11–15, and
16–20. Keep cash when a candidate does not cover expected cost and risk.

## Improvement 7: Test leverage last

After choosing the best 1x policy, compare 1.00x, 1.25x, and 1.50x with at least a 25% free-margin
reserve at each venue. Include scaled basis P&L, fees, slippage, skipped candidates, ending venue
equity, reserve breaches, forced deleveraging, and liquidation sensitivity.

The first reasonable challenger is 1.25x. Funding-only improvement is insufficient evidence for
higher leverage.

## Measurement sequence

### Step 1: Reproduce the baseline

Run two independent periods:

```text
Period 1: [2024-08-21, 2025-08-21)
Period 2: [2025-08-21, 2026-08-21)
```

Use USD 4,500 starting capital, USD 1,500 per venue, USD 112.50 per leg, three-day rebalancing,
unchanged-pair retention, capital-aware backfill, and final liquidation.

Report funding, fees, basis P&L, net return, turnover, average positions, capital skips, missing
data, and ending venue balances.

### Step 2: Measure one improvement at a time

Run separate scenarios for:

1. pair-specific fee hurdle;
2. basis-direction filter;
3. maker routing;
4. capital-aware selection;
5. capital distribution; and
6. maximum book size.

### Step 3: Combine only robust improvements

Combine only changes that improve basis-inclusive return in both periods. Compare the combined
challenger with the unchanged baseline.

### Step 4: Stress the challenger

Report monthly P&L, largest winners and losers, largest month's profit contribution, result without
the five best trades, all-taker sensitivity, an extra 5 bp and 10 bp execution drag, missing-data
sensitivity, and nearby parameter values.

### Step 5: Test 1.25x separately

Only after the combined 1x policy passes the stress checks, repeat it at 1.25x with a 25% venue
reserve.

## Capital-return scorecard

Every scenario should produce one row per period containing:

| Category | Metrics |
|---|---|
| Return | funding, basis P&L, fees, slippage, net P&L, return on total capital |
| Capital efficiency | average deployed capital, return on deployed capital, unused capital |
| Portfolio | average/max pairs, turnover, new/retained/closed positions |
| Venue capacity | used/unused capital, rejected candidates, ending equity by venue |
| Risk | worst month, worst trade, drawdown, reserve breaches |
| Data quality | missing funding, missing prices, unverifiable symbol mappings |

## Suggested first challenger

```text
Starting allocation: equal thirds
Gross leverage: 1.00x
Maximum positions: 20
Leg notional: fixed USD 112.50
Entry rule: pair-specific fee hurdle for new positions
Basis rule: penalise or reject materially short-cheap entries
Routing: compare current routing with Bybit-maker for Bybit–Hyperliquid
Retention: keep an unchanged pair unless replacement edge covers turnover
Unused capacity: remain in cash
```

Keep 40/25/35 capital, conditional transfers, and 1.25x leverage as separate challengers so their
individual effects remain measurable.

## Investigation update: 2026-08-24

The corrected one-week replay enters only after both venues' real observation timestamps. It found
that the broad funding-only selection loses after fees, while persistence plus a favourable entry
basis is the strongest measured improvement. The current shadow challenger should therefore:

- require four consecutive same-direction hourly funding observations;
- require the four-hour median expected funding to exceed twice the complete fee hurdle;
- require the short venue's executable entry basis to cover the full planned round-trip fee;
- allow only one open position per canonical base and reject overlapping re-entry;
- keep fixed slot notional, treat 20 positions as a maximum, and leave unused capital in cash; and
- retain Bybit-maker for the observed small Binance-Bybit entries while continuing to measure it.

This is a forward-shadow policy, not a live-capital promotion. The full-fee basis rule retained only
six observations / four bases before overlap handling, and the result remains too small for
leverage or adaptive sizing. The tested two-consecutive-non-positive-gap exit degraded results and
should not advance. A 48-hour hold did not improve return per occupied capital-day over 24 hours,
so both horizons should remain outcome measurements rather than assuming that longer is better.

### Bid/ask capture required for maker-route proof

For every maker submission, acknowledgement, first/partial/final maker fill, hedge submission and
hedge fill, capture a shared lifecycle ID, exchange timestamp, local receive timestamp, both
venues' best bid/ask and displayed size, depth to the requested notional, mark/index, order side,
price, quantity, maker/taker flag, actual fee and fee tier. Preserve rejected post-only submissions
and canceled/replaced attempts as well as accepted orders.

This permits both routes to be priced from the same market state:

```text
observed route cost     = maker fill + opposite-venue hedge fill + actual fees
counterfactual route    = other venue's maker price + first venue's executable taker price + fees
maker adverse selection = direction-adjusted 1s / 5s / 30s post-fill markout
```

The latest audit found a 3.7 bp fee advantage for Bybit-maker, about -2.61 bp notional-weighted
entry markout at +1 minute, and +2.86 bp observed Binance hedge cost versus the last pre-hedge
aggregate trade. The hedge cost cannot be compared fairly with Binance-maker until the missing
counterfactual Bybit taker bid/ask is captured at the same instant.

## Decision rule

Advance an improvement only when it:

- increases basis-inclusive net return in both periods;
- survives conservative fee and slippage assumptions;
- is not dependent on one month, venue pair, or several outliers;
- improves return on total capital, not only deployed notional;
- does not repeatedly deplete one venue; and
- remains directionally positive at nearby parameter values.

The final result should identify which improvements pass, the recommended 1x policy, and whether
1.25x deserves a forward paper test.
