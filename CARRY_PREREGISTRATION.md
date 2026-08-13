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

---

# ADDENDUM: participation threshold (registered before implementation)

## Why

The primary configuration clears the bar in every cumulative window (Sharpe 2.16-3.07), but 2026 is
-4.9% with **negative realised funding**, while that year's raw top-10 funding was +100% annualised.

> **Written before the double-counting defect was found.** Those figures came from the defective
> funding query described in the primary RESULT above; corrected, the primary reaches Sharpe 1.29 and
> 2026 is -5.8%. The reasoning below — that the eligible universe drained — is unaffected, because
> the erosion table counts *symbols*, not funding amounts. This paragraph is left as registered
> rather than rewritten, since the addendum's predictions were made against these numbers.

The cause is measured, not guessed. Of the ten highest-funding names each week, the count that had
both a spot pair and 30 days of history:

| Year | Top-10 slots | Spot + mature | Share |
| --- | ---: | ---: | ---: |
| 2021 | 520 | 414 | 80% |
| 2022 | 520 | 452 | 87% |
| 2023 | 520 | 386 | 74% |
| 2024 | 530 | 228 | 43% |
| 2025 | 520 | 60 | 12% |
| **2026** | 300 | **1** | **0.3%** |

The carry has not decayed - it has migrated into freshly listed, perp-only coins with no spot leg to
hedge against. Funding persistence is intact (2026 autocorrelation 0.447; top-10-by-prior-week still
realises +48% the following week). The strategy is fishing in a pond that has been draining since
2024, and it keeps buying ten positions whether or not they pay.

**More positions is the wrong response.** The eligible set has little rich funding left, so widening
adds names paying nothing while still paying 26 bp to trade them. The correct lever is
participation: hold a position only when it pays, and sit in cash otherwise.

## The rule

A candidate is taken only if its trailing 7-day funding, annualised, is at least **25%**.

- Fewer qualifiers means fewer positions; capital for unfilled slots sits in **cash earning zero**,
  which is conservative - no money-market or staking yield is assumed.
- If nothing qualifies, the book is flat and only exit costs are paid.
- Return is scaled by the deployed fraction, so partial deployment cannot flatter the result.

**25% is not a searched value.** It is the same threshold used in the pre-backtest funding analysis
that motivated this whole strategy, chosen before any backtest existed. It will not be swept.

---

# RESULT — primary configuration (2026-08-12): **REFUTED**, Sharpe 1.29 against a 1.5 bar

286 weekly rebalances, mean 129 eligible symbols, deployed fraction 100%.

```
funding received +7.7%   basis drift -0.1%
NET annual +3.4%   vol 2.6%   Sharpe 1.29   t 3.02   maxDD 4.7%
losing periods 47%   cumulative +20.0%
```

**Bar: Sharpe >= 1.5 and maxDD <= 20%. Drawdown cleared, Sharpe did not.** The strategy fails.

| Year | Funding | Basis | NET | Sharpe | Periods |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2021 | +24.8% | -0.0% | **+20.3%** | 4.50 | 47 |
| 2022 | +2.7% | -0.6% | **-2.3%** | -2.27 | 52 |
| 2023 | +6.2% | +0.0% | +1.7% | 1.65 | 52 |
| 2024 | +10.0% | -0.0% | +5.4% | 3.41 | 52 |
| 2025 | +2.9% | -0.0% | **-1.2%** | -2.10 | 52 |
| 2026 | -3.1% | +0.3% | **-5.8%** | -1.68 | 31 |

Three of six years are negative, and **2021 alone (+20.3%) exceeds the cumulative +20.0%** — the
other four and a half years net slightly below zero in aggregate.

## Correction: the first reported result was wrong

An earlier run of this test reported Sharpe 2.16, +5.7%/yr, maxDD 3.1% and was recorded here as
passing. That was a double-counting defect, not a different configuration.

`futures_funding_rate` holds two `rate_type` values with overlapping coverage — `ARCHIVE`
(833 symbols, 2020-01-01..2026-08-01) and `Regular` (16 symbols, 2022-10-01..2026-08-11). **63,075
(symbol, funding_time) pairs exist under both**, with identical values. `loadFunding` summed the
table directly, so each of those payments counted twice.

The sixteen affected symbols are AAVE, ADA, AVAX, BCH, BNB, BTCUSDC, BTC, DOGE, DOT, ETC, ETH, LINK,
LTC, SOL, TRX and XRP — all large caps. Because the strategy **ranks by trailing funding**, doubling
inflated both their apparent yield and their probability of being selected into the top ten. The
error begins 2022-10; 2021 is identical before and after the fix, which is the consistency check.

Fixed by deduplicating per payment before summing per day (`MAX(funding_rate)` grouped by symbol and
funding_time). Filtering to `ARCHIVE` alone was rejected because it would discard the final ten days.
`CrossSectionalMomentumApplication` carried the same defect; re-run, it moves Sharpe 0.72 -> 0.73 and
remains refuted against its 1.2 bar.

## Against the registered predictions

1. **Confirmed.** +3.4% realised against the +45.3% conditional funding figure. Realised funding on
   the actually-tradeable set is +7.7%, not +45.3% - the gap is the eligibility filters, not costs.
2. **Refuted.** The prediction was that Sharpe would be high even if return was modest. Return is
   modest **and** Sharpe is 1.29, below the declared bar. Losing weeks are 47%, close to a coin flip.
3. **Confirmed.** 2022, 2025 and 2026 are negative, and each is a market-wide funding compression
   rather than a single-name event. Ten-name diversification did not protect.
4. **Basis drift was near zero** (-0.1% annualised over the full period, worst year -0.6%). This was
   charged explicitly and expected to hurt. On a same-asset hedge it does not - which is precisely
   the property that `CARRY_PERP_HEDGE_PREREGISTRATION.md` gives up.

## Why it fails, beyond the bar

- **The result is one year.** 2021 returns +20.3%; the cumulative over five and a half years is
  +20.0%. Everything after 2021 nets slightly negative in aggregate.
- **Half the weeks lose money** (47%), against a design whose whole premise was small steady gains.
- **2026 is broken**, for the measured reason in the addendum below: the eligible universe drained.

The earlier claim that "Sharpe 2.16 means the return is a sizing choice" no longer holds. At 1.29,
levering to reach a usable return also levers a 4.7% drawdown and a near coin-flip weekly hit rate.
The capital-efficiency question - whether spot can be posted as collateral against the perp margin -
is now moot for this configuration, because there is no risk-adjusted quality worth levering.

## Predictions

1. **Annual return falls.** Less capital is deployed, and periods that paid a little are skipped.
2. **Sharpe rises**, because the skipped periods are the ones contributing cost without carry.
3. **2026 improves markedly** - the book should be near-flat rather than negative, since almost
   nothing qualifies.
4. Deployed fraction declines year over year, tracking the universe erosion above.

## Bar

Unchanged: **Sharpe >= 1.5 and max drawdown <= 20%** on the full period. Additionally, this addendum
is only adopted if it **improves 2026 specifically** - otherwise it is a parameter that helped
in-sample overall while failing the problem it was introduced to solve, which is the failure mode
this project has documented eleven times.

## RESULT — addendum (2026-08-12): NOT ADOPTED

291 rebalances, mean deployed fraction **19%**.

```
funding received +4.8%   basis drift +0.0%
NET annual +3.9%   vol 2.5%   Sharpe 1.58   t 3.73   maxDD 2.7%
losing periods 11%   cumulative +24.3%
```

| Year | NET, primary | NET, threshold | Change |
| --- | ---: | ---: | ---: |
| 2021 | +20.3% | +18.1% | -2.2 |
| 2022 | -2.3% | **+0.0%** | +2.3 |
| 2023 | +1.7% | +1.4% | -0.3 |
| 2024 | +5.4% | +5.1% | -0.3 |
| 2025 | -1.2% | **+0.2%** | +1.4 |
| 2026 | -5.8% | **-4.1%** | +1.7 |

Against the registered predictions:

1. **Annual return falls.** **Refuted** - it rose, +3.4% → +3.9%. Skipping periods that paid nothing
   removed more cost than carry.
2. **Sharpe rises.** **Confirmed** - 1.29 → **1.58**, and losing weeks fall from 47% to 11%.
3. **2026 improves markedly, near-flat rather than negative.** **Refuted** - -5.8% → -4.1%. It is the
   largest single-year improvement, and 2026 is still firmly negative.
4. Deployed fraction fell to 19% on average, consistent with the erosion table.

**This variant clears both numeric bars (Sharpe 1.58, maxDD 2.7%) while the primary does not.**
That inversion is a consequence of the double-counting correction: before the fix the primary looked
better on every measure, and the addendum was rejected partly for lowering Sharpe.

It is still **not adopted**, for two reasons that survive the correction:

- **2026 remains firmly negative**, which is the specific failure it was written to fix. The adoption
  condition asked for improvement there, and 1.7 points that leaves the year at -4.1% is not the
  "near-flat" outcome the prediction named.
- **+3.9%/yr with 81% of capital idle is not worth trading**, which is the standard already applied
  when the primary returned more than this.

Adopting it now would also mean selecting the surviving variant *after* seeing which one survived —
the failure mode this file exists to prevent. It is recorded as measured and left unadopted.

### The unregistered observation worth keeping

In 2026 the threshold made realised funding **worse**: -3.1% without it, **-4.0%** with it. Selecting
harder on trailing funding produced worse forward funding. Within the spot-eligible remnant, high
trailing funding now looks like a distress signal rather than a persistence signal — the opposite of
the 0.447 autocorrelation measured on the full universe.

Sample caveat: 32 periods at 19% deployment, so this is thin and is recorded as an observation, not a
finding. It was not predicted and is not used to justify anything here. It does sharpen the successor
hypothesis: the persistence is real on the **full** universe, and the constraint that destroys it is
the spot requirement — see `CARRY_PERP_HEDGE_PREREGISTRATION.md`.
