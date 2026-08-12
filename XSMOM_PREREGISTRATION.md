# Cross-sectional momentum — pre-registration (2026-08-11)

Written **before the data import and before any wide-universe run**. Everything below is fixed here.

## Why this, after twelve refutations

Apollo and Gerchik both asked *"will this instrument move up from here?"* — time-series prediction on
one chart, replicated across 15 correlated instruments. That multiplies trade count without
multiplying information: 590 trades collapsed to 26 independent blocks, and the entire apparent edge
in one case was a single favourable half-year.

This asks a different question — *"which of these will outperform the others?"* — where the common
factor cancels rather than dominates. Measured on our own data: mean pairwise weekly correlation
0.625, cross-sectional dispersion 4.87% per week.

## Contamination, stated up front

I have already run this on 15 symbols over 2022-01..2026-08, including the reserved validation and
final-test windows. **There is no clean out-of-sample period available for this strategy family.**
Pretending otherwise would be worse than admitting it.

The known 15-symbol figures, gross of costs, recorded here so they cannot be quietly improved upon:

| Variant | Ann. | Vol | Sharpe | t | Max DD |
| --- | ---: | ---: | ---: | ---: | ---: |
| weekly, top/bottom 3 | 31.1% | 47.6% | **0.65** | 1.40 | 61.6% |
| weekly, top/bottom 5 | 26.1% | 36.6% | 0.71 | 1.53 | 53.3% |
| daily, top/bottom 3 | 46.4% | 53.6% | 0.87 | 1.86 | 73.6% |
| biweekly, top/bottom 3 | 34.9% | 54.0% | 0.65 | 1.38 | 62.7% |
| monthly, top/bottom 3 | 16.3% | 55.1% | 0.30 | 0.63 | 61.0% |

**This is therefore not a test of whether momentum exists. It is a test of whether breadth improves a
pre-stated baseline by a pre-stated amount.** Genuine validation can only come forward in time.

## The universe rule

- All USDT perpetuals present in the Binance archive: **832 symbols**, including delisted ones.
  FTTUSDT and LUNAUSDT are both present and verified retrievable.
- A symbol is eligible at time *t* only while its archives exist, so listing and delisting dates come
  from the data with no lookahead and no judgement from me.
- **Liquidity floor: trailing 30-day median daily quote volume >= $10,000,000.** Declared now, not
  tuned. Most of the 832 are untradeable; this is expected to leave roughly 150-250 names at a time.
- **A symbol is excluded for its first 30 days** of trading, so listing pumps cannot enter the
  ranking.

The live API lists only 654 USDT perps (527 trading), missing ~178 fully delisted symbols. Using it
would define the universe by which coins survived to today - unknowable in advance, and corrupting to
both sides of a long/short book.

## The strategy

1. Every rebalance, rank eligible symbols by trailing **7-day** return.
2. Long the **top 20%**, short the **bottom 20%**, equal weight within each side.
3. Equal capital on each side, so net market exposure is approximately zero.
4. Hold to the next rebalance.

Parameters are **the same grid already run on 15 symbols** - deliberately, so that no new parameter
selection happens. Primary variant: **weekly rebalance**. Daily is reported alongside because I have
already seen it; suppressing it would be selective reporting.

Top/bottom 20% rather than a fixed count of 3, so position count scales with the universe and the two
tests stay comparable.

## Costs, declared

- Taker fees both sides: **4.5 bp** per side. No maker assumption - a momentum book chases.
- Slippage: **2 bp** per side.
- Round-trip per position turned over: **13 bp**, the same figure measured today.
- **Funding is charged on real rates** where available. Shorting alts sometimes pays and sometimes
  costs, and on a 40-position short book that is not a rounding error.
- Weights are equal, not inverse-volatility. Vol targeting is a known Sharpe improver and therefore a
  knob; it is reported as a declared secondary variant, never as the headline.

## Predicted effect of breadth

Cross-sectional strategies scale roughly as `IR ~ IC x sqrt(N)`. Moving from 15 names to ~200
eligible is a **3.7x** breadth increase, which maps Sharpe 0.65 -> ~2.4 **if the information
coefficient holds at the same level**.

It will not hold. Thinner names have worse liquidity, and today's Apollo work found liquidity
correlating positively with performance (rho +0.46). So the honest prediction is *improvement,
substantially short of the theoretical figure*.

**Registered prediction: primary-variant Sharpe rises above 1.0 net of costs.** Below that, breadth
did not deliver and the premise is wrong.

## The bar for continuing

Net of all costs, on the primary variant, over the full available history:

- **Sharpe >= 1.2**, and
- **maximum drawdown <= 35%**

Both, not either. Sharpe 0.65 at a 62% drawdown is not a system regardless of headline return -
return alone is meaningless because any edge can be levered.

If the bar is missed, this closes like Apollo and Gerchik did, and the finding is recorded as:
breadth does not rescue cross-sectional momentum in crypto perpetuals at accessible cost levels.

## Explicitly forbidden

- Sweeping the liquidity floor, the lookback, or the position fraction after seeing results.
- Reporting the best variant of the grid as though it were the primary.
- Dropping symbols, periods, or the delisted names for any reason discovered after the fact.
- Quoting a gross figure without the net one beside it.

## What would still be true if this passes

Passing establishes a historical statistical relationship on a contaminated period, not a live edge.
Cross-sectional momentum is a documented and widely traded effect; if it works here it is likely
already competed against. The only honest confirmation is forward, on data that does not exist yet.
