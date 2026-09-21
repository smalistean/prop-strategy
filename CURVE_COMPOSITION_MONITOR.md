# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-21T06:15:05Z  ·  composition pools: 22, wrapper pools: 1 (discovery: api)  ·  stored 45 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $217,453,367 | +0.152 | DAI/USDC/USDT | -4.0 bp | 0 |
| USDC | $366,910,913 | -0.059 | DAI/USDC/USDT | +1.3 bp | 0 |
| USDe | $1,292,720 | -0.263 | USDT/USDe | +1.6 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2492 DOLA≈USDe | 1.2519 DOLA | **+21.6 bp** | $62,659,384 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99722 (-27.8 bp via sUSDS) | 78.0% | +21.6 bp | **-6.2 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99997** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 44.0% | $14,196,163 | 0 | 135,000,000 | 0.0% | 0.99988 | -8.2 bp | 0 | 0 |
| USDT/crvUSD | USDT | 53.3% | $34,130,164 | 21,464,299 | 135,000,000 | 62.9% | 1.00007 | -6.3 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 41.3% | $1,309,646 | 0 | 45,000,000 | 0.0% | 0.99982 | -8.8 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 42.7% | $14,937,221 | 0 | 9,000,000 | 0.0% | 0.99984 | -8.5 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 72.4% | $1,386,604 | 0 | 0 | 0.0% | 1.00070 | +6.3 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,392,198  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 38,552,399 | 24.0% | -9.3% | -1.9 bp | -2.1 pp | 0 |
| USDC | 35,723,199 | 22.3% | -11.1% | +1.3 bp | -3.8 pp | 0 |
| USDT | 86,116,600 | 53.7% | +20.4% | -4.0 bp | +5.9 pp | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$63,604,388  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 32,805,088 | 51.6% | +1.6% | -2.3 bp | -0.9 pp | 0 |
| RLUSD | 30,799,300 | 48.4% | -1.6% | -1.7 bp | +0.9 pp | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$37,113,242  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 15,256,961 | 41.1% | -8.9% | -0.3 bp | -10.3 pp | 0 |
| USDC | 21,856,281 | 58.9% | +8.9% | -1.8 bp | +10.3 pp | 1 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$34,130,164  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 18,192,561 | 53.3% | +3.3% | -1.7 bp | +7.3 pp | 0 |
| crvUSD | 15,937,602 | 46.7% | -3.3% | -0.3 bp | -7.3 pp | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,076,075  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 9,201,536 | 45.8% | -4.2% | +1.1 bp | -3.2 pp | 0 |
| USDtb | 10,874,539 | 54.2% | +4.2% | -3.1 bp | +3.2 pp | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$20,032,189  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 9,656,100 | 48.2% | -1.8% | -0.8 bp | +4.4 pp | 0 |
| USDC | 10,376,089 | 51.8% | +1.8% | -1.2 bp | -4.4 pp | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$14,196,163  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 6,243,413 | 44.0% | -6.0% | +0.2 bp | +1.1 pp | 0 |
| crvUSD | 7,952,749 | 56.0% | +6.0% | -2.2 bp | -1.1 pp | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$11,840,982  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 8,222,794 | 69.4% | +19.4% | -40.1 bp | +8.6 pp | 1 |
| USDC | 3,618,189 | 30.6% | -19.4% | +31.5 bp | -8.6 pp | 1 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,054  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,095,651 | 70.9% | +20.9% | -0.6 bp | +0.2 pp | 0 |
| USDT | 2,916,403 | 29.1% | -20.9% | +0.6 bp | -0.2 pp | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$9,992,544  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,935,107 | 49.4% | -0.6% | -0.8 bp | +0.4 pp | 0 |
| fxUSD | 5,057,437 | 50.6% | +0.6% | -1.2 bp | -0.4 pp | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,396,867  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,617,809 | 49.1% | -0.9% | -0.3 bp | +0.5 pp | 0 |
| USDat | 4,779,059 | 50.9% | +0.9% | -1.7 bp | -0.5 pp | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,822,597  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,473,422 | 25.3% | -24.7% | +1.6 bp | -9.6 pp | 0 |
| USDT | 4,349,175 | 74.7% | +24.7% | -1.9 bp | +9.6 pp | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,781,717  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,648,767 | 76.3% | +26.3% | -221.3 bp | -2.2 pp | 2 |
| USDC | 1,132,950 | 23.7% | -26.3% | +170.9 bp | +2.2 pp | 2 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,157,207  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 611,902 | 19.4% | -30.6% | +26.9 bp | -1.4 pp | 0 |
| USDf | 2,545,304 | 80.6% | +30.6% | -35.6 bp | +1.4 pp | 1 |

### USDx/USDT  (`0xE521BA88837B2726E5343ccA91adA6f19F356C45`)

A = 1000  ·  TVL ~$3,052,043  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDx | 1,795,996 | 58.8% | +8.8% | -4.8 bp | -14.4 pp | 0 |
| USDT | 1,256,047 | 41.2% | -8.8% | +2.7 bp | +14.4 pp | 1 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,960  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,580,215 | 57.5% | +7.5% | -2.1 bp | +3.2 pp | 0 |
| USDC | 1,167,745 | 42.5% | -7.5% | +0.0 bp | -3.2 pp | 0 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,539  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 651,927 | 37.2% | -12.8% | +0.9 bp | -5.4 pp | 0 |
| USDT | 1,098,612 | 62.8% | +12.8% | -3.0 bp | +5.4 pp | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,678,866  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 419,834 | 25.0% | -25.0% | +49.1 bp | -3.1 pp | 1 |
| USG | 1,259,031 | 75.0% | +25.0% | -59.5 bp | +3.1 pp | 1 |

### USDT/USDe  (`0x5B03CcCAb7BA3010fA5CAd23746cbf0794938e96`)

A = 10000  ·  TVL ~$1,292,720  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 986,342 | 76.3% | +26.3% | -2.4 bp | +22.6 pp | 1 |
| USDe | 306,377 | 23.7% | -26.3% | +1.6 bp | -22.6 pp | 0 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,077,787  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 645,988 | 59.9% | +9.9% | -3.9 bp | -1.2 pp | 0 |
| USDC | 431,799 | 40.1% | -9.9% | +1.8 bp | +1.2 pp | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,053  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 791,187 | 79.0% | +29.0% | -1.3 bp | -0.9 pp | 0 |
| USDT | 209,866 | 21.0% | -29.0% | +1.3 bp | +0.9 pp | 0 |

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
