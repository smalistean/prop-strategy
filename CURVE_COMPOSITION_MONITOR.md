# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-22T06:15:04Z  ·  composition pools: 23, wrapper pools: 1 (discovery: api)  ·  stored 47 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $229,724,475 | +0.045 | DAI/USDC/USDT | -2.5 bp | 0 |
| USDC | $363,304,966 | -0.017 | DAI/USDC/USDT | -0.8 bp | 0 |
| USDe | $1,296,124 | +0.205 | USDT/USDe | -1.5 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2493 DOLA≈USDe | 1.2519 DOLA | **+20.9 bp** | $62,662,868 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99731 (-26.9 bp via sUSDS) | 77.7% | +20.9 bp | **-6.0 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99990** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 40.4% | $14,405,569 | 0 | 135,000,000 | 0.0% | 0.99979 | -9.3 bp | 0 | 0 |
| USDT/crvUSD | USDT | 47.8% | $46,470,066 | 33,750,000 | 135,000,000 | 72.6% | 0.99995 | -7.7 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 36.3% | $1,310,719 | 0 | 45,000,000 | 0.0% | 0.99968 | -10.4 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 44.1% | $15,173,564 | 0 | 9,000,000 | 0.0% | 0.99988 | -8.4 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 72.8% | $1,449,850 | 0 | 0 | 0.0% | 1.00072 | +7.7 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,188,538  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 45,035,676 | 28.1% | -5.2% | -1.2 bp | +2.5 pp | 0 |
| USDC | 49,257,408 | 30.7% | -2.6% | -0.8 bp | +5.1 pp | 0 |
| USDT | 65,895,454 | 41.1% | +7.8% | -2.5 bp | -7.6 pp | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$61,285,519  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 31,990,699 | 52.2% | +2.2% | -2.4 bp | -0.2 pp | 0 |
| RLUSD | 29,294,820 | 47.8% | -2.2% | -1.6 bp | +0.2 pp | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$46,470,066  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 22,215,088 | 47.8% | -2.2% | -0.6 bp | -6.8 pp | 0 |
| crvUSD | 24,254,978 | 52.2% | +2.2% | -1.4 bp | +6.8 pp | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$37,099,064  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 11,706,891 | 31.6% | -18.4% | +0.8 bp | -17.8 pp | 0 |
| USDC | 25,392,174 | 68.4% | +18.4% | -3.1 bp | +17.8 pp | 1 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,076,089  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 9,336,324 | 46.5% | -3.5% | +0.7 bp | -2.4 pp | 0 |
| USDtb | 10,739,765 | 53.5% | +3.5% | -2.8 bp | +2.4 pp | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$20,032,434  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 10,326,601 | 51.5% | +1.5% | -1.2 bp | +5.8 pp | 0 |
| USDC | 9,705,833 | 48.5% | -1.5% | -0.8 bp | -5.8 pp | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$14,405,569  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 5,822,899 | 40.4% | -9.6% | +1.1 bp | -1.4 pp | 0 |
| crvUSD | 8,582,670 | 59.6% | +9.6% | -3.1 bp | +1.4 pp | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$11,500,719  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 8,350,448 | 72.6% | +22.6% | -51.8 bp | +11.2 pp | 1 |
| USDC | 3,150,271 | 27.4% | -22.6% | +42.9 bp | -11.2 pp | 1 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,056  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,137,052 | 71.3% | +21.3% | -0.6 bp | +0.7 pp | 0 |
| USDT | 2,875,004 | 28.7% | -21.3% | +0.6 bp | -0.7 pp | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$10,003,630  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,210,044 | 42.1% | -7.9% | +1.7 bp | -7.9 pp | 0 |
| fxUSD | 5,793,586 | 57.9% | +7.9% | -3.8 bp | +7.9 pp | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,350,370  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,040,241 | 43.2% | -6.8% | +4.6 bp | -4.8 pp | 0 |
| USDat | 5,310,129 | 56.8% | +6.8% | -6.7 bp | +4.8 pp | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$4,929,331  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,260,481 | 25.6% | -24.4% | +1.6 bp | +4.1 pp | 0 |
| USDT | 3,668,850 | 74.4% | +24.4% | -1.8 bp | -4.1 pp | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,781,690  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,515,022 | 73.5% | +23.5% | -175.8 bp | -4.4 pp | 2 |
| USDC | 1,266,669 | 26.5% | -23.5% | +127.5 bp | +4.4 pp | 2 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,157,491  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 601,977 | 19.1% | -30.9% | +28.0 bp | -2.4 pp | 0 |
| USDf | 2,555,514 | 80.9% | +30.9% | -36.8 bp | +2.4 pp | 1 |

### USDx/USDT  (`0xE521BA88837B2726E5343ccA91adA6f19F356C45`)

A = 1000  ·  TVL ~$3,051,759  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDx | 1,459,543 | 47.8% | -2.2% | -0.1 bp | -18.5 pp | 0 |
| USDT | 1,592,216 | 52.2% | +2.2% | -1.9 bp | +18.5 pp | 1 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,957  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,481,535 | 53.9% | +3.9% | -1.5 bp | -0.4 pp | 0 |
| USDC | 1,266,423 | 46.1% | -3.9% | -0.5 bp | +0.4 pp | 0 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,529  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 787,833 | 45.0% | -5.0% | -0.3 bp | +2.4 pp | 0 |
| USDT | 962,696 | 55.0% | +5.0% | -1.7 bp | -2.4 pp | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,668,643  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 460,264 | 27.6% | -22.4% | +38.0 bp | +0.1 pp | 1 |
| USG | 1,208,379 | 72.4% | +22.4% | -47.9 bp | -0.1 pp | 1 |

### USDT/USDe  (`0x5B03CcCAb7BA3010fA5CAd23746cbf0794938e96`)

A = 10000  ·  TVL ~$1,296,124  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 382,962 | 29.5% | -20.5% | +0.8 bp | -24.3 pp | 0 |
| USDe | 913,162 | 70.5% | +20.5% | -1.5 bp | +24.3 pp | 1 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,077,789  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 651,796 | 60.5% | +10.5% | -4.1 bp | -0.1 pp | 0 |
| USDC | 425,993 | 39.5% | -10.5% | +2.0 bp | +0.1 pp | 0 |

### frxUSD/USDT  (`0xD6937eA8D33B0C21348F36F7895FdE3054bA4927`)

A = 10000  ·  TVL ~$1,025,020  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| frxUSD | 270,322 | 26.4% | -23.6% | +1.4 bp | — | 0 |
| USDT | 754,699 | 73.6% | +23.6% | -1.7 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,052  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 788,485 | 78.8% | +28.8% | -1.3 bp | -1.2 pp | 0 |
| USDT | 212,568 | 21.2% | -28.8% | +1.3 bp | +1.2 pp | 0 |

### USDC/USDSM  (`0xAC216046AB7Df980F1B8C5e254c922ef7e0a2d11`)

A = 1000  ·  TVL ~$1,000,132  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 618,379 | 61.8% | +11.8% | -6.4 bp | +7.2 pp | 0 |
| USDSM | 381,754 | 38.2% | -11.8% | +4.2 bp | -7.2 pp | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
