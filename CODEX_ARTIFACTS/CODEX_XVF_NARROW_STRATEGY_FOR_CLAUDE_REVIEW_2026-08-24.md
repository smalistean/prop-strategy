# XVF Narrow Strategy — Claude Review Draft

**Created by:** OpenAI Codex  
**Date:** 2026-08-24  
**Document type:** Proposed forward-shadow strategy specification  
**Status:** For independent review; not approved for live capital

## Objective

Replace the broad “select the largest annualized funding gap” approach with a deliberately narrow
Binance–Bybit challenger. A candidate must have persistent funding direction, enough forecast
funding to cover costs with a large buffer, and a favourable executable entry basis. The strategy
uses fixed position sizes, leaves unused capital in cash and does not add leverage.

The purpose of the next phase is to measure this policy prospectively beside the existing policy.
It is not a recommendation to change live entry or exit behaviour from the current one-week sample.

## Why narrow the strategy

The corrected replay enters only after both venue observations actually arrived. Its clean broad
baseline contained 338 observations and had negative median net P&L at every fixed horizon:

| Horizon | Median net |
| --- | ---: |
| 24 hours | -17.39 bp |
| 48 hours | -16.12 bp |
| 72 hours | -14.40 bp |

The strongest measured filter combined funding persistence with a favourable entry basis. Requiring
the entry basis to cover the full 22.6 bp planned Binance–Bybit round trip retained six observations
across four bases before overlap handling. At 24 hours, median net was +12.89 bp, five of six
observations beat fees and the worst was -6.85 bp. Only four positions remained after rejecting
overlapping re-entry. This is encouraging but far too small for production promotion.

The result is not broad or diversified. ACX dominated less restrictive variants, and the latest
sample covers approximately one week. The proposed rules are therefore a frozen shadow challenger,
not fitted production thresholds.

## 1. Trading universe

The initial challenger trades only:

- Binance USD-M perpetuals;
- Bybit linear perpetuals;
- the same verified normalized underlying on both venues; and
- instruments with valid contract, multiplier, quantity-step, minimum-notional and liquidity data.

Exclude TradFi/equity perpetuals, ambiguous ticker collisions, unverified mappings and contracts
whose multiplier normalization cannot be proven. Examples already removed include `SHAZ`,
`SAMSUNGEM` and `LGELECTRONICS`.

Allow at most one open cross-venue position per canonical base. Do not create an ACX whitelist:
ACX's historical contribution is a concentration warning, not evidence of a durable symbol edge.

Hyperliquid candidates may continue to be recorded, but they are outside this narrow challenger
until synchronized price and execution coverage is comparable.

## 2. Funding direction and normalization

For each venue contract, normalize the quoted funding rate to an hourly rate:

```text
hourlyRate = fundingRate / fundingIntervalHours
```

For the same base on Binance and Bybit:

```text
expected24hGapBps =
    (shortVenueHourlyRate - longVenueHourlyRate) * 24 * 10,000
```

The higher normalized funding-rate venue is the short leg. The lower normalized funding-rate venue
is the long leg. A positive result means the direction is expected to receive more funding on the
short leg than it pays on the long leg.

Example:

```text
Binance expected 24h funding: +35 bp
Bybit expected 24h funding:   -15 bp
Expected 24h gap:              50 bp

Direction: short Binance, long Bybit
```

## 3. Four-observation persistence gate

Require four consecutive hourly paired observations for the exact same two contracts and direction:

```text
samples = 4
all four expected24hGapBps > 0
median(expected24hGapBps over the four observations) > 2 * roundTripCostBps
```

Reject the candidate if an observation is missing, the direction reverses, or any of the four gaps
is non-positive. Use each observation's real `observed_at`, not only its rounded hour bucket.

For the currently modelled Binance–Bybit route:

```text
Bybit maker entry fee:     3.6 bp
Binance taker entry fee:   4.5 bp
Bybit taker exit fee:     10.0 bp
Binance taker exit fee:    4.5 bp
---------------------------------
Planned round trip:       22.6 bp

Required four-hour median expected 24h gap:
2 * 22.6 = 45.2 bp
```

Fees must come from the account's current tier and intended route. The value 22.6 bp is the measured
configuration, not a permanent constant.

The hurdle is intentionally severe. A 20% simple annualized gap is only approximately 5.48 bp per
day. It does not cover a 22.6 bp round trip in a one-day hold. A 45.2 bp expected daily gap is about
165% simple annualized; the extra margin compensates for rapid forecast decay and unmodelled risk,
not an assumption that such a rate persists for a year.

## 4. Entry-basis gate

Normalize multiplier contracts before comparing prices. Calculate the planned executable basis:

```text
entryBasisBps = ln(shortExecutablePrice / longExecutablePrice) * 10,000
```

Require:

```text
entryBasisBps >= roundTripCostBps
```

For a 22.6 bp Binance–Bybit round trip, the short contract must be approximately 0.226% more
expensive than the long contract at entry. This aligns the funding direction with the relative-price
dislocation: the strategy shorts the higher-funding and more expensive contract while buying the
lower-funding and cheaper contract.

Use prices consistent with the planned route:

- If Bybit is the long maker leg, use the intended Bybit post-only buy price and the executable
  Binance taker-sell price.
- If Bybit is the short maker leg, use the intended Bybit post-only sell price and the executable
  Binance taker-buy price.
- Use depth-weighted executable prices for the actual quantity when top-level size is insufficient.

The latest replay used first-minute prices as a proxy because synchronized historical L1 was not
available. This is an evidence limitation, not permission to use candle opens in live selection.

The basis is a gate and secondary ranking feature. Do not add the complete entry basis to expected
profit: convergence is uncertain and the dislocation can widen.

## 5. Causal entry timing

The pair cannot be evaluated until both venue observations exist:

```text
signalKnownAt = max(binanceObservedAt, bybitObservedAt)
```

Order submission must occur after `signalKnownAt`, using the then-current order book. In a candle
replay, entry is the first complete minute after this timestamp. Do not enter at `observed_hour`:
the studied observations arrived around `HH:50:34`, and using `HH:00` created approximately 51
minutes of look-ahead and could count funding settled before the actual entry.

## 6. Candidate selection and ranking

For each canonical base:

```text
fundingSurplusBps =
    medianExpected24hGapBps - 2 * roundTripCostBps
```

Apply the following sequence:

1. Validate instrument identity, freshness, multiplier and executability.
2. Determine the normalized funding direction.
3. Require four consecutive same-direction observations.
4. Require the four-observation median to exceed twice complete planned fees.
5. Require executable entry basis to cover the full planned round trip.
6. Reject if that canonical base already has an open position.
7. Rank passing bases by `fundingSurplusBps`.
8. Use entry basis only as a secondary ordering field.
9. Select only candidates fundable from safe free collateral.

Record every rejected candidate and its first rejection reason. Do not fill empty slots with a
candidate that fails a gate.

## 7. Entry execution

Retain the observed Binance–Bybit route at the current small size:

1. Submit a post-only maker order on Bybit.
2. When Bybit fills, immediately hedge only the filled quantity on Binance as taker.
3. Keep partial fills delta-balanced; never hedge more than the confirmed maker quantity.
4. If the maker order does not fill within the existing controlled lifecycle, cancel it and remain
   in cash.
5. Do not automatically cross both entry legs as taker.

The seven-day private-order audit found:

- 47/47 accepted entry-maker lifecycles received at least a partial fill;
- 46/47 completed fully;
- median first-fill time was 12.18 seconds, p95 was 47.72 seconds and max was 73.93 seconds;
- 81/82 first maker fills matched a Binance hedge;
- entry hedge latency was 0.55 seconds median and 6.44 seconds p95; and
- all matched Binance hedges were taker.

Bybit history omits synchronous post-only submissions rejected before an exchange order exists, so
these numbers are not an unconditional submission fill rate. They also apply only to the account's
observed small sizing.

## 8. Maker-route evidence and missing counterfactual

A filled Bybit-maker entry saves 3.7 bp in fees versus making Binance under the measured tiers. The
observed Bybit entry maker markout at +1 minute was -2.61 bp notional-weighted; negative means
adverse to the maker direction. This leaves approximately 1.09 bp of the nominal fee advantage
before route-specific slippage.

Exact Binance Futures aggregate trades were retrieved for all 81 matched hedges. Entry Binance
taker execution cost versus the last aggregate trade at or before the Bybit maker fill was +2.859
bp notional-weighted and +3.454 bp median. This is a real current-route cost, but it cannot simply be
subtracted from the Bybit-maker fee advantage: a Binance-maker alternative would instead incur
unknown Bybit taker crossing and slippage.

To prove which route is cheaper, capture both venues' market state for every maker submission,
acknowledgement, partial/final maker fill, hedge submission and hedge fill:

- shared lifecycle and attempt IDs;
- exchange timestamp and local receive timestamp;
- best bid, best ask and displayed sizes on both venues;
- depth to the intended notional;
- mark and index prices;
- order side, price, quantity and maker/taker flag;
- actual fee and fee tier;
- post-only rejections, cancellations and replacements; and
- direction-adjusted 1s, 5s and 30s markout.

Then calculate both paths from the same market state:

```text
observed route cost = maker fill + opposite-venue hedge fill + actual fees

counterfactual route cost =
    other venue's maker price + first venue's executable taker price + fees
```

Until this exists, preserve Bybit-maker at small size but do not claim proven all-in superiority.

## 9. Position sizing and capital policy

Use fixed equal-dollar legs:

```text
legNotional = totalStrategyCapital / (2 * 20)
```

With USD 4,500 total strategy capital, each leg is USD 112.50 and each two-leg pair has USD 225
gross exposure. A full 20-position book is 1x gross exposure.

Rules:

- 20 positions is a maximum, not a quota.
- Keep the same slot notional when fewer candidates pass.
- Never redistribute empty-slot capital among the survivors.
- Leave unused capacity in cash.
- Keep equal venue capital as the control.
- Use 1x target gross exposure; do not add leverage.

The measured full-fee policy used only 7.27% of a 20-slot book at 24 hours and 10.67% at 48 hours.
The immediate problem is scarcity of validated entries, not lack of leverage or capital capacity.

## 10. Holding and exit measurement

For the forward shadow comparison:

- primary outcome: fixed 24-hour close;
- secondary outcome: fixed 48-hour close;
- retain existing operational and risk exits in any live book; and
- do not promote the tested “two consecutive non-positive hourly gaps” exit.

That dynamic exit triggered on every studied candidate after 9.33 hours on average and reduced
median net to -4.60 bp, with only four of 12 outcomes positive after fees.

The 24-hour and 48-hour full-fee variants produced 20.86 and 20.33 bp per occupied capital-day.
The longer hold did not improve occupied-capital efficiency. Therefore 24h and 48h are outcome
horizons for the shadow test, not yet a production exit change.

## 11. Outcome calculation

For equal one-leg notional, calculate realized relative-price P&L as:

```text
basisPnlBps =
    (1 - shortExitPrice / shortEntryPrice
       + longExitPrice / longEntryPrice - 1) * 10,000
```

Then:

```text
netBps = realizedFundingBps
       + basisPnlBps
       - actualEntryFeesBps
       - actualExitFeesBps
       - actualSlippageBps
```

Report funding, basis P&L, each fee, slippage, net P&L, maker attempt/fill/rejection rate, hedge
latency, residual exposure, capital occupancy and unused cash separately. Report results by symbol,
venue direction and chronological cohort, including the portfolio result without its best symbol.

## 12. What this proposal does not recommend

- No live leverage increase.
- No concentration of unused cash into passing candidates.
- No ACX-specific strategy.
- No capital-allocation optimization from this one-week sample.
- No all-taker fallback solely to avoid missing an entry.
- No claim that a large funding gap guarantees positive realized funding.
- No claim that favourable entry basis must converge.
- No production exit change from the current exit sensitivity.

## 13. Data collection and execution cadence

The main evidence limitation is the short pending-funding history, not the settled-funding archive.
Retain at least two to three months of hourly observations with venue, venue symbol, exact
`observed_at`, `observed_hour`, target funding stamp, funding rate and funding interval. Export the
DynamoDB buffer into PostgreSQL before its 30-day TTL removes observations that cannot be recovered
from venue history endpoints.

For every preliminary candidate, capture synchronized executable market state on both venues: best
bid/ask and displayed quantities, depth through the intended leg notional, exchange and local receive
timestamps, mark price and index price. Continue refreshing `perp_funding_all` for exact settled
outcomes. For attempted entries, retain every post-only acceptance/rejection, partial/final fill,
maker/taker classification, hedge timestamp and price, fee, cancellation and chase. Snapshot
instrument identity, asset class, contract multiplier, funding interval and listing state so an ETF,
equity or ticker collision cannot silently enter the crypto cohort.

Review after 60 complete days as a first frequency checkpoint. Initial performance evidence should
contain at least 50 non-overlapping entries across at least 20 bases; a stronger assessment needs
roughly 100-200 entries across multiple regimes. At the first observed rate of approximately one
accepted entry every two days, 100 entries could require about six months.

The recorder writes DynamoDB observations at `HH:50`, and the installed local launchd export copies
them into `venue_funding_observation` at `HH:55`. Because the narrow execution selector reads
PostgreSQL, its dry-run launchd job follows once per hour at `HH:05`. Running more
often cannot add funding information because observations arrive hourly. Running less often can miss
a candidate that passes for only one hourly snapshot. Repeated live runs read actual open positions
and must not reopen a base already held; empty cycles should remain in cash.

## Compact rule

Trade only verified Binance–Bybit contracts where the same funding direction persists for four
hourly observations, the median forecast 24h gap exceeds twice the complete route fee, and the
short contract's executable price is at least one complete route fee above the long contract.
Enter Bybit-maker and hedge confirmed fills on Binance as taker, use fixed slot size, leave unused
capital in cash, and measure 24h and 48h all-in outcomes without leverage.

## Questions for Claude's independent review

1. Are the funding interval normalization, bps units and 24-hour projection dimensionally correct?
2. Is `medianExpected24hGapBps > 2 * roundTripCostBps` defensible as a frozen shadow gate, or does
   it double-count protection supplied by the separate full-fee basis gate?
3. Should the entry basis remain a hard gate, or be treated as a risk penalty without assuming
   convergence?
4. Are the route-specific executable prices defined correctly for both possible Bybit maker sides?
5. Is maker-miss-to-cash preferable to a controlled taker fallback under the measured fee tiers?
6. Does restricting the first challenger to Binance–Bybit improve evidential clarity, or discard
   too much useful Binance–Hyperliquid data?
7. Is a fixed 24-hour primary outcome appropriate, given that the tested dynamic exit failed and
   48 hours did not improve occupied-capital efficiency?
8. What minimum independent forward sample and concentration limits should be required before any
   live promotion?
9. Which assumptions would most likely make the encouraging six-observation result disappear?
