# XVF leverage and venue-capital study

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Status:** research and paper-test design; not production evidence

## Conclusion

There is room to increase XVF's gross funding exposure, but the defensible next experiment is only
**1.25x gross book leverage with at least 25% free initial-margin reserve on every venue**. The
two-year replay does not support 1.5x or 2x deployment:

- in the prior year, leverage scales a real positive funding-minus-fee result almost linearly;
- in the recent year, it accelerates Binance collateral depletion, reduces the number of pairs the
  isolated venue balances can support, and changes which symbols enter the book; and
- the attractive recent 2x result is therefore a different, capacity-filtered portfolio, not proof
  that the original portfolio safely doubles.

Keep equal starting capital as the control. Paper-test a **40% Binance / 25% Bybit / 35%
Hyperliquid** challenger and a conservative, edge-gated replenishment rule. Do not count basis
convergence as profit until it is replayed from actual entry and exit marks: the existing strategy
research measured basis as a material drag, not a free second source of return.

## Leverage definitions

Two different controls are often both called “leverage”:

1. **Economic gross leverage** is `sum(abs(leg notional)) / total strategy equity`. It determines
   how funding, commissions, slippage and basis P&L scale.
2. **Venue margin leverage** is local position notional divided by initial margin assigned at that
   venue. It controls required initial margin, but it is not by itself the strategy's economic
   exposure. Liquidation also depends on collateral mode, maintenance tiers, mark prices, local
   position concentration and whether unused venue equity supports the positions.

This study deliberately ties the two together. At leverage `L`, each leg is
`USD 112.50 × L` and the assumed venue margin leverage is also `L`, leaving USD 112.50 initial
margin per leg. With 20 pairs, a full book therefore has `USD 4,500 × L` gross notional against USD
4,500 total equity.

This equality is a modelling convention, not an execution requirement. If an exchange accepts only
a coarser setting such as 2x, setting venue leverage to 2x does **not** authorize a 2x economic
book. Gross notionals can still be capped at 1.25x and excess venue collateral left unused.

“Venue-isolated” here means Binance, Bybit and Hyperliquid cannot share collateral. The stress
calculation below assumes the equity inside one venue can support that venue's local portfolio. If
positions use isolated-position margin and the reserve is not assigned to them, liquidation can
occur substantially earlier.

## Methodology

The simulator uses the strict, no-lookahead artifacts in the parent Codex replay directory:

- independent periods `[2024-08-21, 2025-08-21)` and `[2025-08-21, 2026-08-21)`;
- USD 4,500 starting equity, normally USD 1,500 per venue;
- 20 pair slots and USD 112.50 base initial margin per leg;
- fixed three-day candidate/reconciliation schedule;
- exact-pair retention and full-rank capital backfill;
- maker-plus-taker entry according to the current replay routing, taker exit;
- fixed annual boundaries with final taker liquidation;
- funding and trading costs scaled by leg notional;
- isolated venue equity and post-fee entry checks; and
- a daily reserve guard that closes the held pair with the weakest stored entry score until every
  venue is back inside its reserve limit.

The guard is an executable counterfactual, not current production behavior. A live implementation
should use current expected net edge, mark-to-market equity and live maintenance margin rather than
the stored entry score.

Capital skips below are attempted ranked candidates rejected for insufficient venue collateral;
they are not unique dates or symbols.

## Pure linear scaling versus executable capacity

First, if the original 1x trades could be held unchanged and every cash flow simply scaled, the
funding-minus-commission result would be:

| Gross leverage | Prior year | Recent year |
| ---: | ---: | ---: |
| 1.00x | 2.08% | 4.71% |
| 1.25x | 2.60% | 5.89% |
| 1.50x | 3.12% | 7.07% |
| 2.00x | 4.16% | 9.42% |

That is arithmetic, not an executable simulation. Once each venue must preserve its own reserve,
the held book changes. The exact equal-allocation results are:

| Reserve | Gross leverage | Prior return | Prior avg pairs | Prior capital skips | Prior guard days/closes | Recent return | Recent avg pairs | Recent capital skips | Recent guard days/closes |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 15% | 1.00x | 2.00% | 11.60 | 1,045 | 1 / 1 | 5.10% | 10.13 | 4,391 | 10 / 10 |
| 15% | 1.25x | 2.46% | 11.41 | 1,067 | 2 / 2 | 5.39% | 9.80 | 4,431 | 11 / 11 |
| 15% | 1.50x | 3.00% | 11.38 | 1,071 | 3 / 3 | 4.75% | 9.57 | 4,459 | 19 / 19 |
| 15% | 2.00x | 3.99% | 11.35 | 1,074 | 3 / 3 | 7.20% | 8.47 | 4,594 | 13 / 13 |
| 25% | 1.00x | 1.84% | 10.62 | 1,161 | 1 / 1 | 4.49% | 9.38 | 4,483 | 10 / 10 |
| 25% | 1.25x | 2.34% | 10.32 | 1,197 | 2 / 2 | 5.34% | 8.96 | 4,534 | 13 / 13 |
| 25% | 1.50x | 2.88% | 10.30 | 1,199 | 1 / 1 | 5.10% | 8.67 | 4,569 | 15 / 15 |
| 25% | 2.00x | 3.86% | 10.40 | 1,187 | 2 / 2 | 8.30% | 7.67 | 4,691 | 15 / 15 |

Average and maximum venue initial-margin utilization were:

| Reserve | Gross leverage | Prior avg Binance / Bybit / HL | Prior max | Recent avg Binance / Bybit / HL | Recent max |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 15% | 1.00x | 62% / 61% / 49% | 85% | 71% / 49% / 45% | 85% |
| 15% | 1.25x | 61% / 60% / 49% | 85% | 69% / 46% / 47% | 85% |
| 15% | 1.50x | 60% / 60% / 48% | 85% | 65% / 44% / 48% | 85% |
| 15% | 2.00x | 58% / 62% / 48% | 85% | 34% / 37% / 50% | 85% |
| 25% | 1.00x | 57% / 55% / 45% | 75% | 62% / 45% / 41% | 75% |
| 25% | 1.25x | 55% / 53% / 44% | 75% | 61% / 42% / 43% | 75% |
| 25% | 1.50x | 54% / 54% / 44% | 75% | 57% / 39% / 45% | 75% |
| 25% | 2.00x | 53% / 56% / 45% | 75% | 30% / 33% / 47% | 75% |

The recent Binance ending balance under the 25% reserve was USD 343.95 at 1x, USD 255.57 at
1.25x, USD 265.69 at 1.5x and only USD 149.10 at 2x. Average held pairs simultaneously fell from
9.38 to 7.67. The 2x return is driven by the altered book and a favorable Bybit-receives/Binance-
pays funding regime; it is not clean linear leverage alpha. The prior year had the opposite cash
flow direction, so Binance equity rose as leverage increased.

## Free-margin and liquidation stress

At the reserve cap, a simple adverse local mark or cross-venue basis move that consumes all equity
above **initial margin** is approximately:

`free-margin shock = reserve / (gross leverage × (1 - reserve))`

For reference, the second column below is that operational buffer. The third assumes, solely for a
sensitivity calculation, a flat 0.5% maintenance-margin ratio and all local venue equity available
to its positions:

| Reserve | Gross leverage | Move exhausting free initial margin | Illustrative move to 0.5% maintenance |
| ---: | ---: | ---: | ---: |
| 15% | 1.00x | 17.7% | 117.2% |
| 15% | 1.25x | 14.1% | 93.6% |
| 15% | 1.50x | 11.8% | 77.9% |
| 15% | 2.00x | 8.8% | 58.3% |
| 25% | 1.00x | 33.3% | 132.8% |
| 25% | 1.25x | 26.7% | 106.2% |
| 25% | 1.50x | 22.2% | 88.4% |
| 25% | 2.00x | 16.7% | 66.2% |

The maintenance column is **not a liquidation forecast**. Its apparently large numbers come from
low aggregate leverage and the assumption that every dollar at a venue supports every local leg.
It omits actual tier schedules, isolated-position margin, mark/index divergence, local long/short
concentration, intraday path, fees during liquidation and cross-venue transfer latency. With truly
isolated position margin, an approximate per-position distance is closer to
`1 / venue margin leverage - maintenance ratio`; unused account reserve may not rescue it.

The 25% reserve at 1.25x provides a materially better operational buffer than 15%—26.7% versus
14.1%—without relying on the illustrative liquidation number. That is the basis for the paper-test
recommendation.

## Existing historical liquidation evidence

`XVF_STRATEGY.md` contains stronger empirical risk evidence than the simple stress formula above.
Its historical weekly test counted both legs and reported:

| Per-leg leverage | Legs liquidated | Weeks affected |
| ---: | ---: | ---: |
| 1x | 2.1% | 12.3% |
| 2x | 7.2% | 32.5% |
| 3x | 16.9% | 54.1% |
| 5x | 39.7% | 81.2% |

It also records that 2x, 3x and 5x were worse after friction, and that even a 1x short can be lost
when its coin doubles. Stop/liquidation slippage was 0.80% median and 3.27% mean across 77 events.
There is no historical 1.25x liquidation row, so this study does not interpolate one; that is
precisely what the paper test must measure.

The same strategy document's twelve-month daily-cadence example earned USD 2,392.71 of funding but
lost USD 1,260.56 to basis before fees. Its measured three-day basis drag was -10.4% annualized.
Those results come from a different historical pipeline and cannot be numerically merged with this
replay, but they reject the assumption that a large funding gap automatically supplies positive
basis P&L. Entry-to-exit basis convergence should be added as a separately measured return stream.

## Capital allocation

At 1.25x gross leverage and a 25% reserve:

| Binance / Bybit / HL | Prior funding | Prior fees | Prior net / return | Prior avg pairs / skips | Recent funding | Recent fees | Recent net / return | Recent avg pairs / skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 33.3 / 33.3 / 33.3 | 290.11 | 184.75 | 105.36 / 2.34% | 10.32 / 1,197 | 462.17 | 221.78 | 240.39 / 5.34% | 8.96 / 4,534 |
| 40 / 20 / 40 | 263.12 | 149.40 | 113.72 / 2.53% | 8.98 / 1,356 | 478.23 | 213.18 | 265.05 / 5.89% | 9.02 / 4,527 |
| 35 / 25 / 40 | 281.03 | 162.35 | 118.68 / 2.64% | 9.58 / 1,285 | 440.93 | 215.95 | 224.97 / 5.00% | 8.84 / 4,549 |
| **40 / 25 / 35** | **280.03** | **160.53** | **119.50 / 2.66%** | **9.54 / 1,290** | **482.19** | **220.87** | **261.32 / 5.81%** | **9.29 / 4,494** |

The 40/25/35 split had the best worst-year result in a five-percentage-point allocation grid and
beat equal allocation in both slices. It is nevertheless selected after seeing these same two
years. It also held fewer prior-year pairs than equal allocation, so part of its improvement is an
implicit pair-selection filter rather than greater deployment. Treat it as a paper challenger, not
a new production default.

The economic reason to overweight Binance initially is not simply signal demand. Funding cash flow
flipped by period: Binance received funding in the prior slice but paid heavily in the recent slice,
while Bybit did the opposite. A static optimum will therefore be unstable.

## Conservative replenishment counterfactual

The tested no-lookahead rule transfers only when:

- a venue falls below 20% of total equity;
- at least 10 of the desired top-20 legs still require that venue;
- a donor is above 35% and remains inside the same reserve after withdrawal;
- no transfer occurred in the preceding 60 days; and
- at most USD 225 is moved toward a 25% target, with USD 1 donor cost.

It never triggered in the prior period. In the recent period:

| Gross leverage | No-transfer return | Replenishment return | Events / principal | Effect |
| ---: | ---: | ---: | ---: | --- |
| 1.00x | 4.49% | 4.18% | 4 / USD 900 | worse despite more capacity |
| 1.25x | 5.34% | 6.03% | 4 / USD 900 | avg pairs 8.96→10.24; skips 4,534→4,378; Binance end USD 255.57→603.94 |
| 1.50x | 5.10% | 6.75% | 5 / USD 1,125 | better in this slice, but still regime-dependent |

The 1x failure is important: restoring capacity can admit lower-ranked trades whose incremental
funding does not cover their extra turnover. A live rule must require the projected net value of the
marginal admitted pairs to cover commissions, basis/slippage allowance, transfer cost and a safety
margin. Capital availability alone is not an entry signal.

## Actionable paper-test specification

1. Keep **1.00x/equal capital** as the control.
2. Run a **1.25x gross** challenger with **25% free initial-margin reserve per venue**. If venue API
   granularity requires a higher margin setting, enforce the 1.25x economic-notional cap separately.
3. Compare equal starting balances with a predeclared **40/25/35** challenger; do not retune the
   split during the test.
4. Monitor live mark-to-market equity and maintenance margin continuously. Stop new entries at the
   reserve boundary and close the lowest **current expected-net-edge** pair before the boundary is
   breached.
5. Permit capped replenishment only for a depleted, still-demanded venue and only when the marginal
   book has positive expected value after all trading, transfer and basis allowances.
6. Record entry and exit marks on both venues, maker attempts/fills, adverse markout, slippage,
   maintenance tier and liquidation distance. Attribute P&L separately to funding, basis, fees and
   transfers.
7. Do not test 1.5x or 2x with money until 1.25x completes an out-of-sample paper period without
   reserve violations and the basis-inclusive result remains positive.

## Limitations

- Only two already-observed one-year slices were used; allocation choices are data-mined.
- No actual mark-price or basis path is present, so neither basis profit nor liquidation can be
  simulated.
- Maintenance schedules, cross/isolated position settings and tier changes are absent.
- Slippage, maker non-fills, adverse selection, delistings and forced liquidation costs are absent.
- The reserve guard uses stale entry score rather than a refreshed expected net edge.
- Transfer latency, stablecoin conversion, chain risk and withdrawal availability are absent; only
  a USD 1 donor cost is charged.
- The underlying strict replay still has five missing recent Binance held leg-days.
- Results use the current replay's maker/taker routing and do not incorporate a Bybit-maker policy.
- Funding forecasts and stale-score calibration remain provisional; leverage magnifies any forecast
  error as well as genuine edge.

## Reproduce

From the repository root:

```bash
python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/xvf-leverage-capital-study.py
```

The script resolves the strict generated CSV inputs from the parent artifact directory, so the
reproduction command remains valid if the checkout moves.
