# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-24T06:15:04Z  ·  composition pools read: 23 of 23 admitted, wrapper pools: 1 of 1 (discovery: api)  ·  stored 47 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $219,731,077 | +0.022 | DAI/USDC/USDT | -2.0 bp | 0 |
| USDC | $358,768,846 | -0.021 | DAI/USDC/USDT | -1.0 bp | 0 |
| USDe | $1,258,984 | -0.199 | USDT/USDe | +0.8 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2496 DOLA≈USDe | 1.2523 DOLA | **+21.2 bp** | $65,494,096 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99728 (-27.2 bp via sUSDS) | 77.8% | +21.2 bp | **-6.0 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99990** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 42.5% | $14,403,398 | 0 | 135,000,000 | 0.0% | 0.99984 | -8.5 bp | 0 | 0 |
| USDT/crvUSD | USDT | 46.8% | $36,766,476 | 23,829,295 | 135,000,000 | 64.8% | 0.99994 | -7.5 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 38.2% | $1,330,853 | 0 | 45,000,000 | 0.0% | 0.99974 | -9.5 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 43.1% | $16,053,568 | 0 | 9,000,000 | 0.0% | 0.99986 | -8.3 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 72.2% | $1,454,999 | 0 | 0 | 0.0% | 1.00069 | +7.5 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$159,947,344  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 49,337,317 | 30.8% | -2.5% | -1.5 bp | +12.6 pp | 1 |
| USDC | 50,104,863 | 31.3% | -2.0% | -1.0 bp | +13.6 pp | 1 |
| USDT | 60,505,164 | 37.8% | +4.5% | -2.0 bp | -26.2 pp | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$60,775,913  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 30,753,017 | 50.6% | +0.6% | -2.1 bp | -2.5 pp | 0 |
| RLUSD | 30,022,896 | 49.4% | -0.6% | -1.9 bp | +2.5 pp | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$36,766,475  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 17,200,471 | 46.8% | -3.2% | -0.4 bp | -16.8 pp | 0 |
| crvUSD | 19,566,003 | 53.2% | +3.2% | -1.7 bp | +16.8 pp | 1 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$33,881,304  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 13,884,078 | 41.0% | -9.0% | -0.3 bp | -1.3 pp | 0 |
| USDC | 19,997,226 | 59.0% | +9.0% | -1.8 bp | +1.3 pp | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,076,138  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 9,285,761 | 46.3% | -3.7% | +0.9 bp | -6.7 pp | 0 |
| USDtb | 10,790,377 | 53.7% | +3.7% | -2.9 bp | +6.7 pp | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$20,032,976  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 9,536,163 | 47.6% | -2.4% | -0.7 bp | -9.0 pp | 0 |
| USDC | 10,496,812 | 52.4% | +2.4% | -1.3 bp | +9.0 pp | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$14,403,398  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 6,115,968 | 42.5% | -7.5% | +0.6 bp | +3.6 pp | 0 |
| crvUSD | 8,287,430 | 57.5% | +7.5% | -2.6 bp | -3.6 pp | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$11,045,615  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 7,817,090 | 70.8% | +20.8% | -44.6 bp | -0.7 pp | 1 |
| USDC | 3,228,524 | 29.2% | -20.8% | +35.8 bp | +0.7 pp | 1 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,056  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,137,063 | 71.3% | +21.3% | -0.6 bp | +0.4 pp | 0 |
| USDT | 2,874,993 | 28.7% | -21.3% | +0.6 bp | -0.4 pp | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$10,008,989  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,821,645 | 48.2% | -1.8% | -0.4 bp | -1.8 pp | 0 |
| fxUSD | 5,187,344 | 51.8% | +1.8% | -1.6 bp | +1.8 pp | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,352,510  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,976,714 | 42.5% | -7.5% | +5.2 bp | -0.3 pp | 0 |
| USDat | 5,375,796 | 57.5% | +7.5% | -7.3 bp | +0.3 pp | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$4,920,184  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,718,790 | 34.9% | -15.1% | +0.6 bp | +21.7 pp | 1 |
| USDT | 3,201,394 | 65.1% | +15.1% | -0.8 bp | -21.7 pp | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,782,574  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,445,033 | 72.0% | +22.0% | -156.7 bp | -6.5 pp | 2 |
| USDC | 1,337,541 | 28.0% | -22.0% | +109.4 bp | +6.5 pp | 2 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,158,083  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 577,086 | 18.3% | -31.7% | +31.0 bp | -0.1 pp | 1 |
| USDf | 2,580,997 | 81.7% | +31.7% | -39.9 bp | +0.1 pp | 1 |

### USDx/USDT  (`0xE521BA88837B2726E5343ccA91adA6f19F356C45`)

A = 1000  ·  TVL ~$3,051,414  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDx | 1,466,291 | 48.1% | -1.9% | -0.2 bp | -25.2 pp | 0 |
| USDT | 1,585,123 | 51.9% | +1.9% | -1.8 bp | +25.2 pp | 1 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,957  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,453,739 | 52.9% | +2.9% | -1.4 bp | -8.9 pp | 0 |
| USDC | 1,294,218 | 47.1% | -2.9% | -0.6 bp | +8.9 pp | 0 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,529  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 816,240 | 46.6% | -3.4% | -0.6 bp | +15.2 pp | 1 |
| USDT | 934,288 | 53.4% | +3.4% | -1.5 bp | -15.2 pp | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,607,935  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 420,013 | 26.1% | -23.9% | +43.9 bp | -1.1 pp | 1 |
| USG | 1,187,921 | 73.9% | +23.9% | -54.1 bp | +1.1 pp | 1 |

### USDT/USDe  (`0x5B03CcCAb7BA3010fA5CAd23746cbf0794938e96`)

A = 10000  ·  TVL ~$1,258,984  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 880,626 | 69.9% | +19.9% | -1.5 bp | +14.7 pp | 1 |
| USDe | 378,357 | 30.1% | -19.9% | +0.8 bp | -14.7 pp | 0 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,027,794  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 636,815 | 62.0% | +12.0% | -4.7 bp | +1.7 pp | 0 |
| USDC | 390,979 | 38.0% | -12.0% | +2.5 bp | -1.7 pp | 0 |

### frxUSD/USDT  (`0xD6937eA8D33B0C21348F36F7895FdE3054bA4927`)

A = 10000  ·  TVL ~$1,023,039  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| frxUSD | 419,833 | 41.0% | -9.0% | +0.3 bp | — | 0 |
| USDT | 603,206 | 59.0% | +9.0% | -0.5 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,053  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 790,210 | 78.9% | +28.9% | -1.3 bp | -1.4 pp | 0 |
| USDT | 210,843 | 21.1% | -28.9% | +1.3 bp | +1.4 pp | 0 |

### USDC/USDSM  (`0xAC216046AB7Df980F1B8C5e254c922ef7e0a2d11`)

A = 1000  ·  TVL ~$1,000,132  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 617,470 | 61.7% | +11.7% | -6.3 bp | -1.9 pp | 0 |
| USDSM | 382,662 | 38.3% | -11.7% | +4.2 bp | +1.9 pp | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
