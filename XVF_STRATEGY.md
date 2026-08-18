# XVF — cross-venue perpetual funding spread

Consolidated specification. Supersedes the scattered figures in
`CROSS_VENUE_FUNDING_MEASUREMENT.md` for configuration purposes; that document remains the record of
how the effect was found and why it is not yet evidence.

**Status: exploratory.** Every parameter below was chosen while looking at the data. The forward test
in `CROSS_VENUE_FUNDING_PREREGISTRATION.md` is what converts this into evidence, and it has not run.

---

## 1. The trade

Two venues pay funding on the same coin under different formulas and schedules. Where the rates
diverge, hold both sides:

```
short the perpetual on the venue paying MORE funding
long  the perpetual on the venue paying LESS funding
equal notional, same coin
```

Price exposure cancels because it is the same asset. The return is the funding differential. It is
not arbitrage: basis risk, venue risk and per-leg liquidation risk are all real and all measured
below.

## 2. Configuration

| Parameter | Value | How it was settled |
| --- | --- | --- |
| Venues | binance, bybit, hyperliquid, dydx | the four with usable funding history |
| Signal | trailing **7-day** realised funding per venue, per coin | carried over from cash-and-carry |
| Spread | `max(rate) - min(rate)` across venues, annualised | — |
| Entry | spread **> 20% annualised** | Sharpe peak; 12.0% at 0%, 25.4% at 40% but only 6 positions |
| Positions | **top 20** by spread | Sharpe 5.12 vs 4.83 at top 10; ranks 11-20 earn as much as 6-10 |
| Weighting | **equal** | spread-weighting cost a third of the Sharpe for one point of return |
| Rebalance | **every 3 days** | 22.0% vs 14.7% daily and 19.5% weekly |
| Leverage | **1x per leg** (capital = total notional) | 2x/3x/5x all worse once friction charged |
| Liquidity floor | **$500k** weekly quote volume on the thinner leg | removes untradeable prints; basis goes to zero |
| Execution | **post-only**, cross the laggard after ~1 minute | 3.2bp to cross vs 92bp of naked drift |

### Values deliberately NOT swept further

The 7-day lookback, the 20-position cap, the $500k floor. The entry threshold and rebalance cadence
were swept and both landed on interior optima, which is itself a reason for suspicion - a parameter
sitting on a peak is what an overfit looks like even when it was not fitted. Treat the cadence as
"2 to 5 days" and the threshold as "around 20%", not as 3 and 20.

## 3. Costs, measured not assumed

| Component | Value | Source |
| --- | --- | --- |
| Maker fee, perp | 2.0 bp (1.8 bp with BNB) | venue schedules, VIP0 |
| Taker fee, perp | 5.0 bp | same |
| **Blended fee per fill** | **3.3 bp** | 54% fill post-only within 1 min, 46% cross |
| Round trip, 2 legs | **~13 bp** | 4 fills |
| Annualised at 3-day cadence | **~8%** | 4 fills x 3.3bp x 122 rebalances |
| Basis drag at 3-day cadence | **-10.4%** | realised, scales with churn |
| Stop/liquidation slippage | 0.80% median, **3.27% mean** | 77 events, 1-minute bars |

Basis is a **per-round-trip** cost, not a per-day one: -22.6% at daily rebalancing, -10.4% at three
days, -3.4% weekly, -1.3% at fourteen. Churn realises it; holding lets it mean-revert. This is why
daily rebalancing loses to 3-day despite capturing more funding.

## 4. Results

Backtest, 289-329 weekly observations 2020-2026 depending on configuration:

| Configuration | Net annual | Sharpe-like |
| --- | ---: | ---: |
| Original: weekly, taker, top 10 | 9.2% | 2.27 |
| + maker execution | 19.6% | 4.84 |
| + top 20 | 19.0% | **5.12** |
| + 3-day cadence | **22.0%** | — |
| minus the 3.3bp blended fee correction | **~18.5%** | — |

Twelve-month simulation on $10,000, all improvements, daily cadence (the 3-day figure is from the
cadence sweep, not this run):

```
funding   +2,392.71
basis     -1,260.56
fees         -34.50
profit    +1,097.65   =  +10.98%
lowest balance 9,978.37  (-0.2% drawdown)
```

Per-leg liquidation frequency, **counting both legs**:

| Per-leg leverage | Legs liquidated | Weeks affected |
| ---: | ---: | ---: |
| 1x | 2.1% | 12.3% |
| 2x | 7.2% | 32.5% |
| 3x | 16.9% | 54.1% |
| 5x | 39.7% | 81.2% |

A short at 1x still dies if the coin doubles, and 2.1% of selections do exactly that in one week.
"Unlevered" is not "safe".

## 5. Minimum capital

Constraint is step-size rounding, not exchange minimums. A leg needs roughly `100 x one step` in
notional for rounding error under 1%.

| | Per leg | Capital (40 legs) |
| --- | ---: | ---: |
| Median selected symbol | $5 | $200 |
| p90 symbol | $77 | **$3,089** |
| Worst (LLYUSDT, $12.09/step) | $1,209 | $48,364 |

**$3,000 minimum. $10,000 comfortable** ($250/leg, covers all but the coarsest).

Skip any candidate where `leg_notional < 100 x step_size x price`. At $10,000 that excludes the
tokenised-equity perps (LLY, META, TSM, IWM, ARM, ALAB, AMAT, WDC) which carry $3-12 step values -
worth knowing that the selection reaches those at all, since they are equities rather than crypto and
their funding dynamics were never separately examined.

## 6. Capacity

| Capital | Weeks supporting full deployment at 1% participation |
| --- | ---: |
| $10,000 | 100.0% |
| $50,000 | 99.0% |
| $100,000 | 96.2% |
| $1,000,000 | 87.5% |

Capacity is not the constraint at any realistic personal scale. Return is.

## 7. What is NOT measured

1. **Adverse selection on maker fills.** You fill when price moves through your level, so the fills
   you get are the worse ones. Requires trade-level data; absent from the 3.3bp.
2. **Fill rates off Binance.** All 313 measured legs were Binance. dYdX and Hyperliquid have thinner
   books; their fills will be worse and crossing more expensive.
3. **Survivorship.** Hyperliquid, Bybit and dYdX universes come from currently-listed endpoints. Every
   coin in the backtest survived to today. Binance's archive includes delistings; the others do not.
4. **Cross-venue collateral — measured; see `XVF_IMPLEMENTATION.md` §7.** Legs sit on separate venues
   with no cross-margining, and every figure in §4 above assumes capital is already on the venue a
   leg lands on. It cannot be. Funding each venue for its own peak needs **1.53x capital at p90,
   1.88x at worst**; an equal 25% split fills the intended book in **5.5% of weeks**. Sizing down to
   fit is the only remedy with a measured cost, and it cuts return on total capital by roughly a
   third — a nominal 19% becomes ~12.5%.

   Adding venues makes it worse, not better: on the same 14 weeks, six venues need 1.40x at p90
   against four venues' 1.25x. The venues also do not share a settlement asset — Hyperliquid and dYdX
   are USDC-only, Bybit USDT — so a top-up crosses a stablecoin as well as a chain.
5. **Reconciliation.** This project produced 7.5%, 10.98%, 18.5%, 19.0%, 19.6%, 22.0% and 28% from
   pipelines built at different times over different periods. They have not been collapsed into one
   number from one code path. Until they are, treat any single figure as indicative.

## 8. Bugs found while measuring this, all of which moved the headline

| Bug | Effect |
| --- | --- |
| Binance funding double-counted across two `rate_type` values | cash-and-carry read Sharpe 2.16, actually 1.29 |
| Stale contracts: 8% of Binance symbol-weeks flat with zero volume | a coin's whole move booked as basis |
| Contract collision: BOB/1000000BOB, CAT/1000CAT on one base | price series jumped between instruments |
| Join key: `1000PEPE` / `PEPE` / `kPEPE` as three assets | every meme coin silently dropped from cross-venue joins |
| Best-of-N inflation with growing venue count | +42.7% and +125.9% in 2026, actually +18.4% and +15.8% |
| Spot return from hourly bars vs perp from daily closes | manufactured +20% basis out of a window mismatch |
| Only the short leg's liquidation measured | understated liquidation frequency by half |

Four of the first five made results look better than they were. That asymmetry is not chance: a bug
that flatters a result does not announce itself.
