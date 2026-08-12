# Cash-and-carry (funding harvest) — pre-registration (2026-08-12)

Written **before the spot import and before any backtest**. Values fixed here are not to be swept.

## Why this differs from everything already refuted

Apollo, Gerchik and cross-sectional momentum all required a forecast to come true. This does not.
The position is long spot and short the perpetual on the same asset: price exposure cancels, and the
exchange pays funding three times a day (sometimes more) for holding it. **The mechanism is a
payment, not a prediction.**

Return is also not the objective. Cross-sectional momentum returned +19.5% annually and is worthless
because Sharpe 0.72 with a 51% drawdown cannot be levered. A carry book at 8% and Sharpe 3 is a
better business, because leverage converts Sharpe into return and cannot convert return into Sharpe.

## Measured basis for the design (funding table, 833 symbols, 2.5M payments)

Unconditional daily funding, annualised:

| | Median | Mean | p05 | p01 | Worst |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mature (31d+) | +11.0% | **+0.3%** | -40% | -235% | -14,037% |
| First 30 days | +11.0% | **-14.2%** | -178% | -821% | -11,700% |

Shorting indiscriminately earns nothing: the mean is zero because a fat left tail consumes the
positive median. New listings are outright negative in the mean.

Conditioning on the prior week paying more than 25% annualised, the following week:

| | n | Mean | Median | p05 | p01 |
| --- | ---: | ---: | ---: | ---: | ---: |
| **Mature (30d+)** | 8,176 | **+45.3%** | +33.8% | **+8%** | -44% |
| New (<30d) | 837 | +49.5% | +40.5% | -6% | -187% |

Selection raises the mean *and truncates the tail* - on mature symbols the 5th percentile is
positive. Persistent positive funding indicates entrenched long-side crowding, a state that does not
invert as abruptly as a fresh listing does. Weekly funding autocorrelation is **0.417**.

## The new-listing exclusion, and where it came from

The user observed that newly added symbols are funded **hourly** for the first days rather than every
eight hours. Verified: 3,034 symbol-days carry 24 payments, 235,824 carry 6, and days on the hourly
schedule average **-456% annualised** - Binance raises the frequency when the perp dislocates, and
those are exactly the days a short perp bleeds.

**Symbols are excluded for their first 30 days.** It costs about 4 points of mean and removes
roughly four times the tail risk. Declared here, not tuned.

## The strategy

1. Weekly, rank symbols by **trailing 7-day realised funding**.
2. Eligible: listed **>= 30 days**; perp 30-day median volume **>= $10m**; **a spot pair exists** with
   30-day median volume **>= $2m**.
3. Take the **top 10** by trailing funding, equal weight.
4. Each position: **long spot, short perp, equal notional.** Net price exposure ~0.
5. Hold one week, then re-rank.

## Costs, declared

- Both legs, both ways: **4.5 bp taker + 2 bp slippage = 6.5 bp per side per leg**. A full
  round trip on a hedged position is therefore **26 bp**.
- **Basis drift is charged**: PnL is (spot exit/entry) - (perp exit/entry) + funding received, so any
  divergence between the legs is a real cost, not assumed away.
- Funding is the actual per-symbol sum over the hold, at whatever interval Binance used.
- Capital is the **total** of both legs, not the margin, so returns are not flattered by netting.

## Predictions, registered

1. Realised return is **materially below** the +45.3% funding figure. That number is funding alone;
   the basis leg and 26 bp of round-trip cost both subtract.
2. **Sharpe exceeds return** in importance and should be high even if return is modest - the profile
   for a hedged carry book is small, steady gains with rare sharp losses.
3. Worst weeks coincide with market-wide deleveraging, when funding flips negative across many
   symbols simultaneously. Diversification across ten names will **not** protect against this,
   because the shock is common.

## The bar for continuing

Net of all costs, on the declared configuration, over the full available history:

- **Sharpe >= 1.5**, and
- **maximum drawdown <= 20%**

Higher Sharpe and tighter drawdown than the momentum test demanded, deliberately: a market-neutral
carry book that cannot clear those is not doing its job, and the whole argument for preferring it
over a directional strategy is risk-adjusted quality rather than headline return.

## Explicitly forbidden

- Sweeping the 25% funding threshold, the 30-day exclusion, the position count, or either liquidity
  floor after seeing results.
- Reporting funding received as though it were strategy return.
- Excluding the deleveraging weeks as "anomalies" - they are the risk being underwritten.
- Netting the two legs to inflate the return on capital.

## Known limits before starting

Spot data exists on Binance for the pairs that matter, verified including small caps. But the richest
funding sits on the least liquid names, where the spot leg is thinnest and the hedge hardest to hold.
The measured funding advantage may simply not be reachable at size, and if the eligible set collapses
once a real spot-liquidity floor is applied, that is itself the finding.
