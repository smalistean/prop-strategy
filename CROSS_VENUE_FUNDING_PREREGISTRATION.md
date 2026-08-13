# Cross-venue funding spread — forward pre-registration (2026-08-12)

**Evaluated on weeks beginning after 2026-08-17 only.** Every week up to and including 2026-08-03 was
used for exploration in `CROSS_VENUE_FUNDING_MEASUREMENT.md` and is disqualified as evidence.

## Why this is a forward test rather than a backtest

The historical panel has already been examined thoroughly: bucket edges, position cap, hold period.
Running a "test" on those same 162 weeks would measure how well parameters chosen from the data fit
the data. This repository has recorded that mistake eleven times, and the fact that the current
result looks good is the reason to be stricter, not more relaxed.

The exploratory estimate, stated here so it cannot be quietly revised later:

| | Exploratory value |
| --- | ---: |
| Net annual, 10-position book, one-leg notional | +14.2% |
| Net annual, two-leg capital | **+7.1%** |
| Sharpe-like (weekly series, annualised) | **1.46** |
| t-statistic, 162 weeks | 2.58 |
| Weeks positive | 64.8% |

**A forward result materially below these is decay or overfitting, and the honest reading is that
the exploratory numbers were the artefact.**

## The strategy, fixed here

1. **Weekly**, at 00:00 UTC Monday, compute for every coin the realised funding over the prior 7 days
   on Hyperliquid and on Binance, each summed over its own payment schedule.
2. `spread = hyperliquid_funding - binance_funding`, annualised by x52.
3. **Eligible**: the coin trades on both venues; both had a complete funding week (Hyperliquid >= 167
   hourly payments, Binance >= 20 at any cadence); both had 7 daily closes.
4. Take the **10 coins with the largest |spread|**, requiring **|spread| >= 20% annualised**. Fewer
   than 10 qualifiers means fewer positions; unfilled slots hold cash at zero.
5. Each position: **short the perp on the venue paying more, long the perp on the venue paying less**,
   equal notional, same coin.
6. **Hold one week**, then re-rank.

### Values that will not be swept

The 20% threshold, the 10-position cap, the 7-day lookback, the 7-day hold, the completeness
thresholds, and the choice of `|spread|` rather than signed spread for ranking. All are carried over
from the exploration exactly as they stood. Changing any of them after seeing forward results
converts this into a second exploration.

## Costs and accounting, declared

- **6.5 bp per side per leg** (4.5 taker + 2 slippage), so **26 bp round trip** on the pair.
  Hyperliquid's actual fee schedule and realised slippage are **not measured** — if they exceed this,
  the declared cost is optimistic and the result must be adjusted, not reinterpreted.
- **Basis is charged in full**: `-sign(spread) * (hyperliquid_return - binance_return)` over the hold.
- **Funding is the realised sum on each venue** at whatever cadence each used.
- **Capital is the total of both legs**, matching `CARRY_PREREGISTRATION.md` so the two are directly
  comparable. That both legs are margin instruments — making real capital lower — is noted and
  deliberately not taken as credit.

## Predictions, registered

1. **Forward Sharpe is below 1.46.** Some of the exploratory figure is parameter fitting. A forward
   result *above* it should be treated as suspicious rather than as confirmation.
2. **Basis remains a small term** in the median week and is fat-tailed, with the worst weeks
   coinciding with market-wide liquidations when the two venues' marks diverge most.
3. **The favourable +8.9% basis in the >50% bucket does not repeat.** It was found after the fact and
   has no tested mechanism; the registered expectation is that basis contributes approximately zero.
4. **Deployed slots fall below 10 in some weeks**, since qualification requires a 20% spread.

## The bar

Over **at least 52 forward weeks**, on the declared configuration, all four:

- **Sharpe >= 1.0** on the weekly portfolio series,
- **net annual return > 0** on two-leg capital,
- **>= 55% of weeks positive**,
- **no single month contributing more than 50%** of the cumulative result.

Sharpe 1.0 rather than the 1.5 carry was held to, and the reason is stated rather than assumed: 1.5
was set for a same-asset spot hedge with near-zero basis, and this construction carries genuine
two-venue basis risk. **This is a lower bar and it is being declared before results exist, not after
a 1.5 test failed.** If that reasoning is unconvincing, the bar to use is 1.5.

## Explicitly forbidden

- Sweeping any value in "will not be swept" after seeing forward results.
- Quoting the exploratory 162 weeks as though they were evidence.
- Extending the evaluation window past 52 weeks because the result is not yet significant, or
  stopping it early because it is.
- Reporting the one-leg return without the two-leg figure beside it.
- Dropping liquidation weeks as anomalous. They are the risk being underwritten.

## Survivorship — the known defect, and what is being done about it

The historical panel is **fully survivorship-biased**: Hyperliquid's `meta` endpoint lists currently
listed coins, so all 212 coins in the exploration survived to today. Coins that delisted are absent,
and distressed coins with extreme funding are the likeliest to delist. This cannot be repaired
retrospectively — there is no historical universe listing, and the venue's S3 archive carries L2 book
snapshots and asset contexts but explicitly **not** candles.

It can be prevented going forward. **Starting now, the coin universe is snapshotted weekly**, so a
coin that is delisted mid-test remains in the record with its final week marked, and the forward test
is measured on a universe that includes what died. A coin delisted during a hold is treated as closed
at its final mark, and the loss or gain is recorded rather than dropped.

Until that record exists, the forward test inherits a milder version of the same bias, and any result
must be read with that in mind.

## Known limits before starting

The richest spreads sit on the thinnest coins, where Hyperliquid's book is shallowest. Funding is a
percentage and says nothing about whether the notional is reachable. If the forward result is carried
by coins where a realistic position would have moved the market, the Sharpe is an artefact of
assuming free liquidity — so **per-coin notional and realised slippage are recorded from the first
week**, not reconstructed later.
