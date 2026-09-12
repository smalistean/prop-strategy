# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-12T06:29:53Z  ·  composition pools: 23, wrapper pools: 1 (discovery: api)  ·  stored 47 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $202,463,279 | +0.102 | DAI/USDC/USDT | -3.2 bp | 0 |
| USDC | $369,816,454 | -0.041 | DAI/USDC/USDT | +0.2 bp | 0 |
| USDe | $1,473,539 | -0.033 | USDT/USDe | -0.2 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2477 DOLA≈USDe | 1.2505 DOLA | **+22.6 bp** | $59,951,368 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99711 (-28.9 bp via sUSDS) | 78.4% | +22.6 bp | **-6.4 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99991** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 39.1% | $14,393,543 | 0 | 135,000,000 | 0.0% | 0.99976 | -9.9 bp | 0 | 0 |
| USDT/crvUSD | USDT | 46.1% | $19,570,248 | 7,288,178 | 135,000,000 | 37.2% | 0.99992 | -8.3 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 39.6% | $1,118,089 | 0 | 45,000,000 | 0.0% | 0.99977 | -9.7 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 52.8% | $15,029,690 | 0 | 9,000,000 | 0.0% | 1.00005 | -6.9 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 73.1% | $1,396,179 | 0 | 0 | 0.0% | 1.00075 | +6.9 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,416,529  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 41,666,004 | 26.0% | -7.4% | -1.5 bp | -10.9 pp | 0 |
| USDC | 41,761,699 | 26.0% | -7.3% | +0.2 bp | -10.9 pp | 0 |
| USDT | 76,988,826 | 48.0% | +14.7% | -3.2 bp | +21.9 pp | 1 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$66,270,691  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 34,834,201 | 52.6% | +2.6% | -2.5 bp | -3.8 pp | 0 |
| RLUSD | 31,436,490 | 47.4% | -2.6% | -1.5 bp | +3.8 pp | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$37,705,719  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 18,627,620 | 49.4% | -0.6% | -1.0 bp | +18.1 pp | 1 |
| USDC | 19,078,100 | 50.6% | +0.6% | -1.0 bp | -18.1 pp | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,719  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 9,848,355 | 49.1% | -0.9% | -0.5 bp | -2.7 pp | 0 |
| USDtb | 10,227,364 | 50.9% | +0.9% | -1.5 bp | +2.7 pp | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$20,030,475  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 8,693,513 | 43.4% | -6.6% | -0.1 bp | +12.0 pp | 1 |
| USDC | 11,336,962 | 56.6% | +6.6% | -1.9 bp | -12.0 pp | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$19,570,248  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 9,027,209 | 46.1% | -3.9% | -0.2 bp | -0.7 pp | 0 |
| crvUSD | 10,543,039 | 53.9% | +3.9% | -1.8 bp | +0.7 pp | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$14,393,543  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 5,626,454 | 39.1% | -10.9% | +1.4 bp | -9.4 pp | 0 |
| crvUSD | 8,767,089 | 60.9% | +10.9% | -3.4 bp | +9.4 pp | 0 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,052  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,067,092 | 70.6% | +20.6% | -0.6 bp | +0.5 pp | 0 |
| USDT | 2,944,960 | 29.4% | -20.6% | +0.6 bp | -0.5 pp | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$9,888,302  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 6,010,982 | 60.8% | +10.8% | -19.9 bp | +7.0 pp | 0 |
| USDC | 3,877,320 | 39.2% | -10.8% | +11.6 bp | -7.0 pp | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,259,346  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,294,474 | 46.4% | -3.6% | +1.9 bp | +3.5 pp | 0 |
| USDat | 4,964,872 | 53.6% | +3.6% | -3.9 bp | -3.5 pp | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,979,131  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,429,720 | 49.3% | -0.7% | -0.8 bp | -1.5 pp | 0 |
| fxUSD | 4,549,411 | 50.7% | +0.7% | -1.2 bp | +1.5 pp | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$6,189,869  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 2,576,450 | 41.6% | -8.4% | +0.3 bp | -22.5 pp | 0 |
| USDT | 3,613,419 | 58.4% | +8.4% | -0.5 bp | +22.5 pp | 1 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,777,489  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,714,275 | 77.7% | +27.7% | -250.9 bp | -3.0 pp | 2 |
| USDC | 1,063,214 | 22.3% | -27.7% | +199.5 bp | +3.0 pp | 2 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,154,477  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 684,864 | 21.7% | -28.3% | +20.4 bp | -0.3 pp | 0 |
| USDf | 2,469,612 | 78.3% | +28.3% | -28.5 bp | +0.3 pp | 0 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,929  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,493,588 | 54.4% | +4.4% | -1.6 bp | +3.7 pp | 0 |
| USDC | 1,254,341 | 45.6% | -4.4% | -0.4 bp | -3.7 pp | 0 |

### rUSDY/USDC  (`0xe1fbaEc91b8A211db901AdF5ACc5b31f9A988279`)

A = 2000  ·  TVL ~$2,091,193  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| rUSDY | 1,117,719 | 53.4% | +3.4% | -4.7 bp | +1.6 pp | 0 |
| USDC | 973,474 | 46.6% | -3.4% | -3.3 bp | -1.6 pp | 0 |

### USDx/USDT  (`0xE521BA88837B2726E5343ccA91adA6f19F356C45`)

A = 1000  ·  TVL ~$2,049,484  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDx | 1,537,101 | 75.0% | +25.0% | -19.1 bp | +25.6 pp | 1 |
| USDT | 512,383 | 25.0% | -25.0% | +16.4 bp | -25.6 pp | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,758,193  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 472,067 | 26.8% | -23.2% | +40.9 bp | -0.4 pp | 1 |
| USG | 1,286,126 | 73.2% | +23.2% | -50.9 bp | +0.4 pp | 1 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,503  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 746,189 | 42.6% | -7.4% | +0.0 bp | -10.6 pp | 0 |
| USDT | 1,004,315 | 57.4% | +7.4% | -2.1 bp | +10.6 pp | 1 |

### USDT/USDe  (`0x5B03CcCAb7BA3010fA5CAd23746cbf0794938e96`)

A = 10000  ·  TVL ~$1,473,539  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 785,127 | 53.3% | +3.3% | -0.4 bp | — | 0 |
| USDe | 688,412 | 46.7% | -3.3% | -0.2 bp | — | 0 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,077,779  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 659,205 | 61.2% | +11.2% | -4.4 bp | +2.1 pp | 0 |
| USDC | 418,574 | 38.8% | -11.2% | +2.2 bp | -2.1 pp | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,054  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 800,479 | 80.0% | +30.0% | -1.5 bp | -0.0 pp | 0 |
| USDT | 200,575 | 20.0% | -30.0% | +1.5 bp | +0.0 pp | 0 |

### USDC/USDSM  (`0xAC216046AB7Df980F1B8C5e254c922ef7e0a2d11`)

A = 1000  ·  TVL ~$1,000,070  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 546,171 | 54.6% | +4.6% | -2.9 bp | -2.2 pp | 0 |
| USDSM | 453,900 | 45.4% | -4.6% | +0.8 bp | +2.2 pp | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
