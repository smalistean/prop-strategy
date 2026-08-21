# XVF improvement review

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Status:** research conclusions and paper-test design; no production file was changed

## Bottom line

There is meaningful room to improve XVF, but the evidence does not support simply adding leverage
or awarding a bonus to every large funding gap.

The strongest next changes, in order, are:

1. **Score total expected pair P&L, including the direction of the entry basis.** The current
   funding-minus-fee replay materially overstates results once historical price convergence is
   overlaid. A high funding gap produced more funding, but basis was a drag on average.
2. **Reject new entries that do not clear their pair-specific round-trip fee hurdle.** This improved
   both strict replay periods while holding fewer symbols.
3. **Choose the maker route by expected execution cost.** Under the current fee assumptions, making
   Bybit the maker specifically for Bybit-Hyperliquid pairs saves 3.7 bp at entry and improves both
   fee-only replay periods. It still needs real fill and markout evidence.
4. **Construct the book against actual venue collateral.** Use a small constrained optimizer over
   all viable venue-pair alternatives instead of greedily ranking and discovering insufficient
   margin during order placement.
5. **Treat 1.25x gross leverage as a paper experiment only.** Basis tails, maker non-fills and real
   maintenance/liquidation paths are not in the leverage replay. The existing strategy evidence is
   actively unfavorable to 2x and above.

The studies cover Binance, Bybit and Hyperliquid because those are the venues in the strict replay.
They do not establish a result for dYdX.

## What was measured

The common baseline uses two independent one-year periods, USD 4,500 starting equity split USD
1,500 per venue, USD 112.50 per leg, a maximum of 20 pairs, a uniform three-day decision schedule,
exact-pair retention, full-rank capital backfill and final taker liquidation.

| Study | Prior period | Recent period | Interpretation |
| --- | ---: | ---: | --- |
| Funding minus modeled commissions | 2.08% | 4.71% | Strict baseline; no price basis |
| Add delayed-UTC historical basis proxy | about 0.88% | about 1.79% | Time-aligned funding/price proxy; basis roughly halves the result |
| Bybit maker whenever present | 2.24% | 5.19% | Fee-only; assumes every maker attempt fills perfectly |
| Pair-specific fee hurdle for new entries | 2.40% | 5.80% | Funding minus commissions; no basis or fill failures |
| 1.25x gross, equal capital, 25% reserve | 2.34% | 5.34% | Capacity/reserve counterfactual; no basis/liquidation path |
| 1.25x, 40/25/35 capital, 25% reserve | 2.66% | 5.81% | Data-mined paper challenger, not a production allocation |

These rows are separate counterfactuals and **must not be added together**. Each changes a different
part of the book or execution path, and none of the fee/leverage rows includes the newly measured
basis path.

## 1. Funding and realized price P&L

For one pair with equal USD notional `N` per leg, the approximate price component is:

```text
basisPnl = N * (1 - shortExit / shortEntry)
         + N * (longExit / longEntry - 1)

totalPairPnl = settledFunding + basisPnl - fees - slippage
```

A common market move mostly cancels. The remaining price P&L comes from the relative move between
the two venue contracts. If the short venue is expensive at entry and the prices converge, the pair
profits. If the short venue is cheap, ordinary convergence loses money.

This component is economically large. On a USD 112.50 leg, a favorable 1% relative convergence is
about USD 1.125. A 20% annualized funding spread held for three days is only about USD 0.185 before
fees. Basis direction and tails can therefore dominate the attractive annualized funding number.

### Actual-lifecycle evidence

The price overlay reconstructs all retained lifecycles rather than forcing every position to close
after three days. It exactly reconciles the baseline funding and fees before adding price P&L:

| Period | Baseline funding | Fees | Baseline net | Covered basis P&L | Time-aligned full-ledger proxy |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2024-08-21–2025-08-21 | +$286.66 | -$193.09 | +$93.57 | -$49.02 | about +$39.43 / +0.88% |
| 2025-08-21–2026-08-21 | +$435.92 | -$223.90 | +$212.02 | -$120.50 | about +$80.63 / +1.79% |

Price coverage is 1,622 of 1,630 lifecycles. The broad synchronized history is daily trade OHLC at
UTC midnight, not exact executable bid/ask or mark prices at production's Europe/Chisinau cutoff.
The result is a delayed-UTC policy proxy, not an exact live-fill backtest.

### Your large-gap hypothesis

It is partly right, but funding size and basis direction must be separated.

| Entry condition | Lifecycles | Basis hit rate | Basis P&L | Funding | Fees | Combined |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Short venue >5 bp expensive | 468 | 75.0% | +$41.12 | +$210.30 | -$116.97 | +$134.45 |
| Within +/-5 bp | 516 | 55.8% | +$10.06 | +$201.28 | -$130.81 | +$80.53 |
| Short venue >5 bp cheap | 634 | 21.0% | -$220.69 | +$294.35 | -$166.49 | -$92.83 |

The directional result was stable in both periods. The adverse bucket lost about USD 46 in each
year, while the aligned bucket earned about USD 60 and USD 75. These are conditional slices of the
existing selected ledger; rejecting them changes backfill and venue balances, so a complete
filtered-book replay is still required.

The highest funding-gap quartile earned USD 307.75 of funding, lost USD 130.83 on basis and paid USD
104.39 in fees, leaving USD 72.53 under the local funding attribution. Large gaps can still be good
funding trades, but the basis was compensation/risk, not a free second profit source. Raw funding
gap and basis P&L had a -0.169 correlation in this sample.

### Proposed basis rule

At entry, calculate normalized executable basis from the short bid and long ask, with instrument
multiplier and canonical-asset identity verified. Do not use a binary bonus for a large funding gap.
Instead estimate:

```text
expectedNetBps = expectedFundingBps
               + expectedBasisPnlBps
               - expectedEntryExecutionBps
               - expectedExitExecutionBps
               - transferAndRiskBps
```

An initial safe shadow rule is: flag every candidate where the short venue is more than 5 bp cheap,
then measure whether forecast funding covers expected convergence loss plus all costs. Do not make
the 5 bp value live from this same sample.

## 2. Bybit as maker

The current execution code chooses the maker using a fixed venue-depth rank. Under the modeled fee
table, Binance-Bybit already rests on Bybit. The only economically meaningful change from "Bybit
always maker" is Bybit-Hyperliquid:

```text
current: Bybit taker 10.0 bp + Hyperliquid maker 1.8 bp = 11.8 bp
tested:  Bybit maker  3.6 bp + Hyperliquid taker 4.5 bp =  8.1 bp
saving:                                                   3.7 bp
```

Fee-only replay results:

| Route | Prior | Recent |
| --- | ---: | ---: |
| Current fixed thinner-venue maker | 2.08% | 4.71% |
| Bybit maker whenever present | 2.24% | 5.19% |
| Both entry legs maker, perfect-fill upper bound | 2.80% | 6.09% |
| Both entry legs taker | 1.21% | 3.93% |

The recent improvement is almost exactly 509 new Bybit-Hyperliquid entries times the 3.7 bp saving.
It assumes 100% immediate maker fills, zero adverse selection and no extra Hyperliquid taker
slippage. If failed Bybit maker attempts fall back to both legs taker, the fee-only fill-probability
hurdle is 42.2%; markout and lost funding make the real hurdle higher.

The live rule should be an expected-cost router, not a permanent venue rank. For both possible maker
assignments, use the account's actual fee tier, size-specific book slippage, maker-fill probability,
post-only reject rate, 1/5/30-second markout and hedge latency. Bybit's post-only order can cancel if
it would cross, and its create acknowledgement is asynchronous, so fills must be confirmed from the
private execution stream.

## 3. Selecting symbols and building the book

The simplest measured improvement is a pair-specific fee hurdle applied only to genuinely new
entries. A retained pair's entry cost is sunk.

| Pair | Current planned round trip | Three-day break-even annual spread |
| --- | ---: | ---: |
| Binance-Bybit | 22.6 bp | 27.50% |
| Binance-Hyperliquid | 15.3 bp | 18.62%; current 20% global floor binds |
| Bybit-Hyperliquid, current route | 26.3 bp | 32.00% |
| Bybit-Hyperliquid, Bybit maker | 22.6 bp | 27.50% |

In the strict replay, the current-route fee hurdle improved 2.08% to 2.40% and 4.71% to 5.80%,
while average held pairs fell from 12.78 to 9.47 and from 11.25 to 8.03. Cash is better than a new
pair whose funding proxy does not cover known turnover.

A rescaled position-count sweep did not support reducing the configured cap. With the fee hurdle,
15 pairs led the recent period by only 0.21 percentage points, while 20 led the prior period by 0.56
points. Keep 20 as a **maximum, not a quota**, and keep each slot at the fixed 20-slot notional when
the book is sparse. Do not concentrate unused capital into the survivors.

The current signal discards all but the widest gross-spread venue pair for each base. The improved
ordering should be:

```text
all legitimate cross-venue alternatives for a canonical base
  -> funding forecast, basis, fees, liquidity, rules and collateral
  -> best expected-net alternative for that base
  -> global venue-capital-constrained book
```

Use one pair per canonical base and hard-gate freshness, instrument identity, full-slot liquidity,
minimum notional, at least 100 native quantity steps, notional/underlying residual tolerance and
fresh executable depth.

For a retained pair, compare the expected value of holding with the full cost of closing and
replacing. This adds turnover hysteresis without inventing a fixed minimum holding period.

## 4. Capital distribution

The required venue mix is regime-dependent. Desired top-20 leg demand at the 90th percentile was:

| Period | Binance | Bybit | Hyperliquid |
| --- | ---: | ---: | ---: |
| Prior | 16 | 15 | 15 |
| Recent | 19 | 20 | 7 |

No static split can fund all three p90 counts with only 40 total legs, and the funding cash-flow
direction also flipped between periods. A recent-optimal allocation is not a robust default.

The proposed book constructor should convert safely usable collateral into integer leg slots and
solve a small deterministic 0/1 problem:

```text
maximize sum(selected[i] * expectedNetUsd[i])

subject to:
  selected pairs <= 20
  venue legs <= safe capacity at that venue
  selected alternatives per canonical base <= 1
  every freshness, fee, basis, liquidity and sizing gate passes
```

Start with equal capital as the control. The 40% Binance / 25% Bybit / 35% Hyperliquid split at
1.25x and a 25% reserve is a paper challenger only: it returned 2.66% and 5.81% versus 2.34% and
5.34% for equal allocation in the same counterfactual, but it was selected after seeing both years.

A conservative replenishment rule improved the recent 1.25x replay from 5.34% to 6.03%, but the
same logic reduced the 1x recent result from 4.49% to 4.18%. Transfer only when the expected net
value of the additional fundable pairs exceeds trading costs, transfer cost/delay and a safety
margin. More deployment is not automatically more profit.

## 5. Leverage

Leverage scales funding, but it also scales basis error, fees, slippage and venue depletion. The
clean arithmetic says 1.25x would scale the unchanged baseline from 2.08/4.71% to 2.60/5.89%, but
the executable 25%-reserve counterfactual held a different book and returned 2.34/5.34%.

At 1.25x with a 25% reserve, average initial-margin utilization was 55/53/44% on
Binance/Bybit/Hyperliquid in the prior period and 61/42/43% recently. The recent Binance ending
balance was only about USD 256. At 2x it fell to about USD 149 and the average pair count fell to
7.67; the attractive headline return came from a different, capacity-filtered portfolio.

Existing strategy research is stronger risk evidence than the funding-only leverage replay:

| Per-leg leverage | Legs liquidated | Weeks affected |
| ---: | ---: | ---: |
| 1x | 2.1% | 12.3% |
| 2x | 7.2% | 32.5% |
| 3x | 16.9% | 54.1% |
| 5x | 39.7% | 81.2% |

The next experiment should therefore be 1.25x gross with at least 25% free initial-margin reserve,
in paper mode. Do not test 1.5x or 2x with money before synchronized mark/basis paths, real
maintenance tiers, maker non-fills, liquidation mechanics and forced close costs are replayed.

The implementation must also separate:

- `TARGET_GROSS_LEVERAGE`, which sizes economic exposure; and
- `VENUE_MARGIN_LEVERAGE`, which is the integer venue margin setting.

If a venue requires 2x as its setting, the strategy can still cap gross notional at 1.25x and leave
the remaining collateral unused. One `LEG_LEVERAGE` value should not control both concepts.

## Recommended experiment sequence

1. **Collect a shadow execution dataset.** Store synchronized bid/ask/mark/index, normalized
   instrument metadata, order-book depth, current and next funding stamps, account fee tier, maker
   attempts/fills, markout, hedge slippage, collateral and actual funding payments.
2. **Shadow the pair-specific fee gate.** Persist why every candidate passes or fails; retain the
   current book unchanged during measurement.
3. **A/B route Bybit-Hyperliquid entries.** Compare current Hyperliquid-maker with Bybit-maker using
   fill ratio, total execution cost, basis P&L and funding actually captured—not fee alone.
4. **Replay an adverse-basis gate end to end.** Use only basis known at entry and allow lower-rank
   backfill; keep aligned, flat and adverse metrics separate.
5. **Run the constrained book optimizer in shadow.** Compare its expected and realized book with the
   current greedy walk, including unused cash and every venue-capacity rejection.
6. **Add turnover-aware hold/replace decisions.** Charge old-pair exit and new-pair entry before a
   newcomer displaces an incumbent.
7. **Only then paper-test 1.25x.** Run equal capital as control and 40/25/35 as a frozen challenger,
   with a 25% reserve and automatic deleveraging based on current expected net edge.

Promotion must be based on total pair P&L—funding, price basis, fees, slippage and transfers—plus
maker fill rate, hedge latency/residual exposure, reserve violations, tail loss and stability across
out-of-sample periods. Headline annualized funding is not sufficient.

## Supporting Codex studies

- `XVF_BASIS_CONVERGENCE_CODEX_STUDY.md`
- `XVF_MAKER_ROUTING_STUDY.md`
- `XVF_SYMBOL_SELECTION_AND_BOOK_CONSTRUCTION.md`
- `XVF_LEVERAGE_AND_CAPITAL_STUDY.md`

Every supporting script and generated result is isolated under this Codex artifact directory.
