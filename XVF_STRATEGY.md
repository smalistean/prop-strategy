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
| Signal | trailing **7-day** realised funding per venue, per coin | swept 3-21 days, 2026-08-19 - see §2b. Sits on a plateau, not a peak |
| Spread | `max(rate) - min(rate)` across venues, annualised | — |
| Entry | spread **> 20% annualised** | Sharpe peak; 12.0% at 0%, 25.4% at 40% but only 6 positions |
| Positions | **top 20** by spread | Sharpe 5.12 vs 4.83 at top 10; ranks 11-20 earn as much as 6-10 |
| Weighting | **equal** | spread-weighting cost a third of the Sharpe for one point of return |
| Rebalance | **every 3 days** | 22.0% vs 14.7% daily and 19.5% weekly. One cadence for both pair types blends two different optima - see §2c |
| Leverage | **1x per leg** (capital = total notional) | 2x/3x/5x all worse once friction charged |
| Liquidity floor | **$500k** weekly quote volume on the thinner leg | removes untradeable prints; basis goes to zero |
| Execution | **post-only**, cross the laggard after ~1 minute | 3.2bp to cross vs 92bp of naked drift |

### 2b. The 7-day lookback, actually swept

It was not, until 2026-08-19: `LOOKBACK_DAYS` had been carried over from an earlier cash-and-carry
strategy and never re-validated for XVF. The prompt to check it was a live-execution question -
whether Postgres and a shorter-window DynamoDB signal pipeline would rank pairs the same way - which
turned up first that Postgres's own completeness filter cannot run at a 3-day window at all (it
compares a trailing count against a fixed *weekly* median regardless of the window, so anything
short of ~7 days fails almost every symbol), and second that the question underneath - is 7 actually
right - had never been answered.

**Method.** 315 rebalances, every 3 days from 2024-01-01 to 2026-08-01, on the same ranking logic as
production: trailing sum of realised funding, best cross-venue pair per base, $500k weekly quote-
volume floor, top 20 by annualised spread, >20% entry threshold. Windows of 3, 5, 7, 10, 14 and 21
days, each scored against realised funding over the following 3 days - the actual holding period.
Binance volume from hourly klines; Bybit and Hyperliquid have only daily klines and store base
volume, so quote volume is `base_volume x close_price`, a reconstruction rather than a stored figure.

**Gross realised funding, annualised, paired against L=7 on identical rebalance dates:**

| Lookback | Gross | vs L=7 | t-stat | |
| ---: | ---: | ---: | ---: | --- |
| 3 | 27.45% | -0.39pp | 0.17 | tied |
| 5 | 26.69% | -1.16pp | 0.53 | tied |
| **7** | **27.84%** | - | - | - |
| 10 | 26.54% | -1.31pp | 2.07 | marginal |
| 14 | 24.90% | -2.95pp | 2.93 | **worse** |
| 21 | 23.86% | -3.98pp | 3.72 | **worse** |

Gross funding alone says 3 and 5 are free - identical return to 7, for less code complexity. They
are not: turnover at 3 days is **62.7%** of the book per rebalance against **40.8%** at 7, and
turnover is what realises basis drag and execution cost, neither of which appears in a funding-only
number. Net of a 21.5bp-per-replacement cost (13bp execution + 8.5bp basis drag, both from §3 -
8.5bp is -10.4% spread over the same 122 rebalances/year), L=7 nets 17.2% against L=3's 11.0%.

That 21.5bp assumes the 54% maker-fill rate §3 was built on. Live testing has not matched it - see
`XVF_LIVE_FINDINGS.md` §5 - and the fees actually measured there run 18bp (binance-hyperliquid) to
29bp (binance-bybit) per round trip on an all-taker fill alone, before basis drag. Re-running the net
figures at 26.5-37.5bp per replacement widens the gap further rather than closing it: L=3's extra
turnover costs more, not less, the worse execution actually is.

**So 7 turns out to be defensible, but not because it is a peak.** It sits on a plateau from about 7
to 21 days that is flat inside the ~2pp standard error the 315-rebalance sample carries; only the
short end (3, 5) is genuinely worse, on turnover rather than on signal. If anything the honest
argument from this data is for *longer* than 7 - 14 gives up a little gross return for nearly half
the churn - but that argument is operational (fewer fills, less exposure to the basis-divergence risk
measured in `XVF_LIVE_FINDINGS.md` §10), not a return argument, and it is not strong enough to move a
number that was never wrong on its own terms.

One instability worth recording rather than smoothing over: splitting by year, L=7's edge is carried
almost entirely by 2026 (32.2% against 24-29% for the other windows), while L=3 led in 2025. A ranking
that reorders year to year is further evidence these differences are noise, not structure - which is
itself the reason to leave `LOOKBACK_DAYS` alone rather than chase whichever window happened to win
on the most recent slice.

### 2c. Rebalance cadence, split by pair type (2026-08-20)

§2b's sweep is REBALANCE_DAYS - how far back the signal looks. This is a different question: how
long to HOLD, and whether one answer serves both pair types the current book mixes. Prompted by a
live observation - realized funding on the current book was running well below its entry-time
signal, and CEX-CEX pairs specifically are where XVF_V1_SCOPE.md already found gaps close fast.

**Method.** Same 20-candidate weekly selection as the live signal, split into CEX-CEX and CEX-DEX by
whether hyperliquid is one of the two legs, 2023-11 to present. Realized funding spread measured
over 1/2/3/5/7/10/14 days starting one week after signal (matching §2b's convention), netted against
a 13bp round-trip fee annualized at each cadence - a fixed per-cycle cost that penalizes short
cadences disproportionately, which a gross-only number would miss.

**Net of fees, annualized:**

| Pair type | 1d | 2d | 3d | 5d | 7d | 10d | 14d |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CEX-CEX | -8.8% | +9.8% | **+16.0%** | **+16.4%** | +15.4% | +14.1% | +12.4% |
| CEX-DEX | -17.2% | +3.2% | +10.5% | +14.4% | +16.1% | **+16.8%** | +16.7% |

**CEX-CEX peaks at 3-5 days - close to where the book already sits - then declines steadily out to
14.** CEX-DEX peaks at 7-10 days, a wide plateau, and is still climbing past where CEX-CEX has
already turned over. The single 3-day cadence is close to optimal for CEX-CEX (16.0% against a 16.4%
peak) and leaves real money on the table for CEX-DEX (10.5% against a 16.8% peak six points higher).
Gross spread decays for both (CEX-CEX front-loads harder: 38.6% at day 1 falling to 15.0%/day by
days 4-7, against CEX-DEX's gentler 30.4% to 20.4%) but the fee-netted optimum is what should drive
cadence, not the gross shape alone - a faster cadence to chase CEX-CEX's steep day-1 reading is
fee-dominated and net-negative at 1-2 days for both types.

**Not yet acted on.** This says the current uniform cadence probably under-serves CEX-DEX rather than
that CEX-CEX is being held too long, which is the opposite of the intuition that prompted the check.
Implementing a pair-type-specific cadence is a real change to the execution loop, not measured here -
this is the sweep that would justify it, not the change itself.

### Values deliberately NOT swept further

The 20-position cap and the $500k floor. The entry threshold and rebalance cadence were swept and
both landed on interior optima, which is itself a reason for suspicion - a parameter sitting on a
peak is what an overfit looks like even when it was not fitted. Treat the cadence as "2 to 5 days"
and the threshold as "around 20%", not as 3 and 20. The lookback was swept too, above, and did not
land on a peak at all - a flat region from 7 to 21 with only the short end distinguishable, which is
a much less suspicious shape.

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
