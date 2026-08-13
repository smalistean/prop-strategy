# Cross-venue funding spread — exploratory measurement (2026-08-12)

**This is not a test.** It is exploratory measurement, and every parameter below was chosen while
looking at results. Its only purpose is to decide whether a pre-registered test is worth writing.
Nothing here may be quoted as evidence that the strategy works — see
`CROSS_VENUE_FUNDING_PREREGISTRATION.md`, which is evaluated on forward data only.

## The mechanism

Hyperliquid and Binance both pay funding on the same coin, under different formulas and different
schedules. Where the two rates diverge, a position that is **short the coin on the venue paying more
and long it on the venue paying less** collects the difference. It is the same asset on both legs, so
price exposure largely cancels without needing a spot pair.

Structural facts measured before any backtest:

- Hyperliquid pays **hourly**. Binance pays 8-hourly on most symbols and **4-hourly on 443 of 747**.
- **127 of 232** Hyperliquid perps sit near +11% annualised, its interest-rate component, which does
  not track the premium in quiet markets. Binance's rate floats.
- On a live snapshot of 206 overlapping coins the median absolute spread was **1.9% annualised**,
  with 24 coins above 20% and 6 above 50%. Any edge is in the tail.

## Data

| Table | Rows | Coverage |
| --- | ---: | --- |
| `hyperliquid_funding_rate` | 4,451,878 | 232 coins, hourly, 2023-05-12 .. 2026-08-12 |
| `hyperliquid_kline` (1d) | 180,838 | 232 coins, 2023-05-01 .. 2026-08-12 |
| `futures_funding_rate` | 2,595,028 | 833 symbols, 2020-01-01 .. 2026-08-11 |
| `futures_kline` (1h) | 65,711,304 | 833 symbols |

Joined panel: **21,388 coin-weeks, 212 coins, 162 weeks, 2023-06-12 .. 2026-08-03.**

### Join guards, each one a way this could have produced a wrong number

- **Binance funding deduplicated per (symbol, funding_time) before summing.** Two `rate_type` values
  overlap on 63,075 pairs. Summing directly double-counts them — the defect that made carry look like
  Sharpe 2.16 when it is 1.29.
- **Complete weeks only, on both venues.** Hyperliquid 167+ hourly payments; Binance 20+ at any
  cadence (21 at 8h, 42 at 4h, 168 on new listings that fund hourly); 7 daily closes on each venue.
  A partial week would read as low funding rather than as missing data.
- **Both venues required.** A missing venue is never read as zero funding.
- **k-prefixed coins map to Binance's 1000-form** (kPEPE to 1000PEPEUSDT). Both quote a rate and a
  return, which are unit-free, so no scaling is applied.
- **Weekly price boundaries taken identically on both venues**, so any definitional error applies to
  both legs and cancels in the difference.

## Persistence

Lag-1 weekly autocorrelation, pooled over 22,406 coin-week pairs:

| Series | Autocorrelation |
| --- | ---: |
| **The spread** | **0.463** |
| Hyperliquid leg alone | 0.520 |
| Binance leg alone | 0.444 |

This is the measurement an earlier probe failed to make. That probe reported +0.568 on 14 coins over
3 weeks, but sampled majors pinned at the +11% component — a constant is trivially autocorrelated,
so the figure meant nothing. The spread itself persists, which is the property the trade needs.

## Basis drift — the term that could have killed it

The hedge is the same coin on two venues, so price exposure cancels only insofar as the two marks
move together. Signed price PnL is `-sign(spread) * (hyperliquid_return - binance_return)`.

| | Value |
| --- | ---: |
| Mean | **+0.76%** annualised |
| Median absolute | 3.62% |
| Standard deviation | **120.7%** |
| p99 absolute | 45.6% |

Small in the typical week, very fat-tailed. But signed against the position it does not cost:

| Entry bucket | n | Funding carry | Basis PnL | Net after 13.5% cost | SD |
| --- | ---: | ---: | ---: | ---: | ---: |
| \|spread\| < 20% | 17,785 | +4.9% | +0.8% | **-7.9%** | 112 |
| 20-50% | 2,698 | +19.9% | **-1.2%** | +5.3% | 77 |
| > 50% | 693 | +45.1% | **+8.9%** | **+40.5%** | 326 |

Trading the median loses money; only the tail pays. Cost is 26 bp round trip on two perp legs
(4 x 6.5 bp), which at weekly turnover is 13.5% annualised.

**The favourable basis in the >50% bucket is not explained and should be distrusted.** A coherent
story exists — extreme Hyperliquid funding accompanies a mark above Binance's, which mean-reverts, so
the short collects funding and convergence together — but that story was constructed after seeing the
number, and it is not tested here.

## Per-year, entering above a 20% spread

| Year | n | Funding | Basis | Net after cost |
| --- | ---: | ---: | ---: | ---: |
| 2023 | 852 | +37.7% | +0.2% | **+24.4%** |
| 2024 | 1,204 | +24.6% | -1.2% | **+9.9%** |
| 2025 | 848 | +15.2% | +4.4% | **+6.1%** |
| 2026 | 487 | +21.3% | +1.1% | **+8.9%** |

All four positive, weakest year still above cost. Concentration is also low: the top coin (MAVIA) is
4.6% of the total, and it takes **32 coins to reach 50%** of the result out of 209.

## The honest statistics

A pooled t-statistic over 3,391 coin-weeks reads 4.44. **That number is wrong** — it treats coins
within a week as independent when they share a common market factor. Collapsing to an equal-weight
weekly portfolio and testing the 162-week series:

| Book | Weeks | Net annual | t-stat | Sharpe-like | Weeks positive |
| --- | ---: | ---: | ---: | ---: | ---: |
| All qualifying (~21 coins/wk) | 162 | +9.8% | **1.87** | 1.06 | 61.1% |
| Capped at 10 positions | 162 | +14.2% | **2.58** | **1.46** | 64.8% |

On this repository's capital convention — charge both legs — **+14.2% becomes +7.1%/yr at Sharpe
1.46**, since Sharpe is scale-invariant. Both legs are margin instruments here, unlike spot carry
where the spot leg is bought outright, so the real capital requirement is well below two-leg notional.

## Why none of this is evidence

1. **Every cut was chosen after seeing results.** The 20% and 50% bucket edges, the 10-position cap,
   the weekly hold. The 20% threshold has a partial defence — it came from the live snapshot before
   any backtest existed — but the cap and the bucket edges do not.
2. **Survivorship is total, not partial.** The universe comes from Hyperliquid's `meta` endpoint,
   which lists currently-listed coins. All 212 coins survived to 2026-08. Coins that delisted are
   absent, and distressed coins with extreme funding are exactly the ones that delist. This is
   unquantified and the >50% bucket is most exposed to it.
3. **162 weeks is three years.** t = 2.58 is suggestive, not conclusive.
4. **No liquidity or size modelling.** Funding is a percentage; whether the notional is reachable on
   the thin coins that pay most is untested, and Hyperliquid order books are shallower than Binance's.
5. **Cost is assumed, not measured.** 6.5 bp per side per leg is the repository convention, carried
   over from Binance work; Hyperliquid's fee schedule and slippage were not measured.

## What was fixed while doing this

- `CarryHarvestApplication` and `CrossSectionalMomentumApplication` double-counted Binance funding.
  Found by checking this join, not by auditing. Carry falls from Sharpe 2.16 to **1.29** and is now
  refuted; momentum moves 0.72 to 0.73 and stays refuted.
- The Hyperliquid funding importer aborted a coin's entire history on one unparseable rate, costing
  nine coins about 20,000 rows each. Now skipped and counted per row.
- The candle importer initially used 1h and produced a full-looking table where every coin began at
  the same recent date: the venue documents that **only the most recent 5,000 candles are available**
  and ignores `startTime` once that binds. Daily covers the whole history in 1,201 rows per coin. A
  coverage assertion now fails loudly instead of returning a truncated panel.
