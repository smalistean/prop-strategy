# Apollo v3 one-variable assumption tests

Training universe: BTCUSDT, ETHUSDT, SOLUSDT, XRPUSDT, BNBUSDT, ADAUSDT,
DOGEUSDT, LINKUSDT; 15-minute Binance USD(S)-M data in
`[2023-08-07, 2025-08-07)`. Each result uses the same real maker-entry and
taker-protective-stop model. These are exploratory training comparisons, not
validation evidence.

## Baseline: Book assumption set A

Four clustered 4h pivot touches, with all other values in
`config/backtests/apollo-ordered-liquidity-sequence-v3.properties`.

- 10 filled trades; independent-account aggregate net PnL: **-$2,296.85**.

## B1: Three touches only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches.properties`
changes only `strategy.mapMinimumTouches` from `4` to `3`.

| Symbol | Baseline trades / net | B1 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 1 / $298.54 |
| ETHUSDT | 0 / $0.00 | 0 / $0.00 |
| SOLUSDT | 2 / -$1,074.35 | 2 / -$1,074.35 |
| XRPUSDT | 1 / $1,907.04 | 1 / $1,907.04 |
| BNBUSDT | 1 / -$728.74 | 1 / -$728.74 |
| ADAUSDT | 1 / -$545.56 | 3 / $3,033.20 |
| DOGEUSDT | 1 / -$546.14 | 1 / -$546.14 |
| LINKUSDT | 3 / -$1,607.62 | 3 / -$1,607.62 |

- B1: **12 filled trades**, independent-account aggregate net PnL:
  **$1,281.92**.
- The only incremental observations are two additional ADA trades: one TP and
  one maximum-holding-period exit. This is insufficient to infer that three
  touches are better; it merely makes the hypothesis testable with a slightly
  larger sample.

## B2: Shorten freshness from 12 hours to 4 hours only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-freshness-4h.properties`
keeps B1's three-touch map and changes only `strategy.freshnessBars` from `48`
to `16` 15-minute bars. A previous visit 4-12 hours before the sweep is now
allowed; a visit during the preceding four hours is still rejected.

| Symbol | B1 trades / net | B2 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 3 / -$834.98 |
| ETHUSDT | 0 / $0.00 | 6 / -$2,418.28 |
| SOLUSDT | 2 / -$1,074.35 | 6 / -$3,240.49 |
| XRPUSDT | 1 / $1,907.04 | 3 / $820.86 |
| BNBUSDT | 1 / -$728.74 | 2 / -$593.33 |
| ADAUSDT | 3 / $3,033.20 | 5 / $1,846.00 |
| DOGEUSDT | 1 / -$546.14 | 4 / -$2,184.66 |
| LINKUSDT | 3 / -$1,607.62 | 6 / -$2,404.19 |

- B2: **35 filled trades**, independent-account aggregate net PnL:
  **-$9,009.06**.
- This change raises the sample but sharply worsens performance. Repeated/
  recently consumed areas produced mostly stop-outs; retain the 12-hour
  freshness rule for subsequent isolated comparisons.

## B3: Widen map area tolerance from 0.50 to 0.75 ATR only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-tolerance-075.properties`
keeps B1's three-touch and 12-hour-freshness settings and changes only
`strategy.mapToleranceAtr` from `0.50` to `0.75`.

| Symbol | B1 trades / net | B3 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 2 / $1,343.72 |
| ETHUSDT | 0 / $0.00 | 1 / -$537.81 |
| SOLUSDT | 2 / -$1,074.35 | 2 / -$1,074.35 |
| XRPUSDT | 1 / $1,907.04 | 1 / $1,907.04 |
| BNBUSDT | 1 / -$728.74 | 1 / -$728.74 |
| ADAUSDT | 3 / $3,033.20 | 3 / $3,033.20 |
| DOGEUSDT | 1 / -$546.14 | 2 / -$1,059.61 |
| LINKUSDT | 3 / -$1,607.62 | 4 / -$2,127.31 |

- B3: **16 filled trades**, independent-account aggregate net PnL:
  **+$756.13**.
- Four extra trades raised the sample, but the aggregate profit was lower than
  B1 and three of the four additions were losses. This is inconclusive rather
  than support for the wider tolerance; retain B1's 0.50-ATR map tolerance as
  the reference for the next isolated comparison.

## B4: Extend reclaim deadline from 6 to 8 bars only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-reclaim-8.properties`
keeps B1's three-touch, 0.50-ATR map, and 12-hour freshness settings and
changes only `strategy.reclaimWindowBars` from `6` to `8`.

| Symbol | B1 trades / net | B4 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 1 / $298.54 |
| ETHUSDT | 0 / $0.00 | 0 / $0.00 |
| SOLUSDT | 2 / -$1,074.35 | 3 / -$1,615.59 |
| XRPUSDT | 1 / $1,907.04 | 1 / $1,907.04 |
| BNBUSDT | 1 / -$728.74 | 1 / -$728.74 |
| ADAUSDT | 3 / $3,033.20 | 3 / $3,033.20 |
| DOGEUSDT | 1 / -$546.14 | 1 / -$546.14 |
| LINKUSDT | 3 / -$1,607.62 | 4 / -$2,113.35 |

- B4: **14 filled trades**, independent-account aggregate net PnL:
  **+$234.96**.
- The two extra filled trades, one SOL and one LINK, both stopped out. The
  longer deadline reduces aggregate profit without introducing a verified new
  setup; retain the six-bar reclaim deadline for subsequent comparisons.

## B5: One full-bodied acceptance bar rather than two only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-acceptance-1.properties`
keeps B1's three-touch map, 0.50-ATR tolerance, 12-hour freshness, and six-bar
deadline, and changes only `strategy.minimumAcceptanceBars` from `2` to `1`.
The retained 0.20-ATR body-size threshold still applies.

| Symbol | B1 trades / net | B5 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 5 / -$2,029.73 |
| ETHUSDT | 0 / $0.00 | 2 / $3,045.83 |
| SOLUSDT | 2 / -$1,074.35 | 7 / -$3,039.13 |
| XRPUSDT | 1 / $1,907.04 | 2 / $1,367.84 |
| BNBUSDT | 1 / -$728.74 | 8 / -$691.75 |
| ADAUSDT | 3 / $3,033.20 | 9 / $1,115.37 |
| DOGEUSDT | 1 / -$546.14 | 9 / $446.69 |
| LINKUSDT | 3 / -$1,607.62 | 10 / $1,431.85 |

- B5: **52 filled trades**, independent-account aggregate net PnL:
  **+$1,646.98**.
- This is the first tested relaxation to approach the 60-trade low-frequency
  evidence floor while retaining positive aggregate net PnL. It is a research
  lead only: BTC and SOL remain materially negative, the result is training
  data, and 52 trades is not sufficient to select or validate a strategy.

## B6: Two-bar local structural break only

The initial B6 run exposed a coupling: `localBreakBars` also shortened the
sweep-search range. The implementation now has an explicit
`strategy.sweepSearchBars=10`, preserving the former B1 range independently of
break length. The corrected B6 configuration changes only `localBreakBars`
from `3` to `2`.

| Symbol | B1 trades / net | B6 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 1 / $298.54 |
| ETHUSDT | 0 / $0.00 | 1 / -$553.87 |
| SOLUSDT | 2 / -$1,074.35 | 2 / -$1,074.35 |
| XRPUSDT | 1 / $1,907.04 | 2 / $1,422.64 |
| BNBUSDT | 1 / -$728.74 | 1 / -$728.74 |
| ADAUSDT | 3 / $3,033.20 | 3 / $3,033.20 |
| DOGEUSDT | 1 / -$546.14 | 5 / -$648.59 |
| LINKUSDT | 3 / -$1,607.62 | 3 / -$1,611.05 |

- B6: **18 filled trades**, independent-account aggregate net PnL:
  **+$137.78**.
- The two-bar break adds six filled trades but reduces aggregate profit sharply.
  It is rejected; retain a three-bar break. The explicit sweep-search setting
  makes future break-length tests genuinely one-variable comparisons.

## B7: Require 2.5R rather than 3R mapped target room only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-min-rr-25.properties`
keeps B1's entry sequence and changes only `strategy.minimumRewardRisk` from
`3.0` to `2.5`. The target remains the opposing mapped liquidity area.

| Symbol | B1 trades / net | B7 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 3 / -$790.59 |
| ETHUSDT | 0 / $0.00 | 1 / -$523.63 |
| SOLUSDT | 2 / -$1,074.35 | 4 / -$2,109.55 |
| XRPUSDT | 1 / $1,907.04 | 2 / $3,182.76 |
| BNBUSDT | 1 / -$728.74 | 4 / -$2,375.75 |
| ADAUSDT | 3 / $3,033.20 | 4 / $2,498.62 |
| DOGEUSDT | 1 / -$546.14 | 3 / -$1,633.17 |
| LINKUSDT | 3 / -$1,607.62 | 6 / $688.18 |

- B7: **27 filled trades**, independent-account aggregate net PnL:
  **-$1,063.12**.
- Lowering the required mapped-target room adds 15 observations but turns the
  aggregate negative. Retain the 3R minimum for subsequent comparisons.

## B8: Require 1.20× average volume at the structural break only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-volume-120.properties`
keeps B1's settings and changes only `strategy.minimumConfirmationVolumeRatio`
from `1.00` to `1.20` relative to the prior 20-bar average.

| Symbol | B1 trades / net | B8 trades / net |
|---|---:|---:|
| BTCUSDT | 1 / $298.54 | 1 / $298.54 |
| ETHUSDT | 0 / $0.00 | 0 / $0.00 |
| SOLUSDT | 2 / -$1,074.35 | 1 / -$523.73 |
| XRPUSDT | 1 / $1,907.04 | 1 / $1,907.04 |
| BNBUSDT | 1 / -$728.74 | 0 / $0.00 |
| ADAUSDT | 3 / $3,033.20 | 2 / $3,598.40 |
| DOGEUSDT | 1 / -$546.14 | 0 / $0.00 |
| LINKUSDT | 3 / -$1,607.62 | 1 / -$541.06 |

- B8: **6 filled trades**, independent-account aggregate net PnL:
  **+$4,739.19**.
- The filter removed six of B1's twelve trades and left a result concentrated
  in two ADA observations plus one XRP trade. It is an insufficient-sample
  quality lead, not evidence for a 1.20× threshold or a validation candidate.

## B9: Require a 0.20-ATR sweep rather than 0.10 ATR only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-sweep-020.properties`
keeps B1's settings and changes only `strategy.sweepAtr` from `0.10` to `0.20`.

- B9: **9 filled trades**, independent-account aggregate net PnL:
  **+$469.10**.
- It filtered the XRP winner and reduced both trade count and aggregate PnL
  versus B1. Retain the 0.10-ATR sweep depth for subsequent comparisons.

## B10: One-bar rather than two-bar 4h pivot confirmation only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-pivot-strength-1.properties`
changes only `strategy.mapPivotStrength` from `2` to `1`.

- B10: **3 filled trades**, independent-account aggregate net PnL:
  **-$1,836.74**.
- Denser pivots changed which clustered area the map selected; this was not a
  broadening of B1. It is rejected; retain two-bar pivot confirmation.

## B11: Extend 4h map history from 72 to 96 bars only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-lookback-96.properties`
changes only `strategy.mapLookbackBars` from `72` to `96`.

- B11: **12 filled trades**, independent-account aggregate net PnL:
  **+$1,266.87**.
- It did not increase sample size and slightly reduced B1's aggregate PnL.
  Retain the 72-bar map lookback.

## B12: Require 0.30-ATR rather than 0.20-ATR acceptance bodies only

`config/backtests/apollo-ordered-liquidity-sequence-v3-map-3-touches-body-030.properties`
changes only `strategy.minimumBodyAtr` from `0.20` to `0.30`.

- B12: **6 filled trades**, independent-account aggregate net PnL:
  **+$4,739.19**.
- It selected the same six filled trades as B8's 1.20× volume filter. This is
  an overlapping, insufficient-sample quality subset—not independent evidence
  for either threshold.
