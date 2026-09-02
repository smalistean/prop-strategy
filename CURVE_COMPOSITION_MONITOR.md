# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A5); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T14:17:42Z  ·  composition pools: 21, wrapper pools: 1 (discovery: api)  ·  stored 43 composition rows; stored 1 wrapper rows; stored 5 pegkeeper rows

## Overall: NORMAL - no action

**Coverage gap (A4):** no admitted composition pool holds USDe - every pool with it is below the $1M admission or contains an excluded coin (FRAX). Monitored through the wrapper NAV metric and the API price band only.

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $233,247,630 | +0.156 | DAI/USDC/USDT | -4.5 bp | 0 |
| USDC | $383,540,208 | -0.048 | DAI/USDC/USDT | +2.3 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2462 DOLA≈USDe | 1.2486 DOLA | **+19.6 bp** | $49,978,703 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99989** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 34.6% | $13,697,649 | 0 | 135,000,000 | 0.0% | 0.99963 | -11.6 bp | 0 | 0 |
| USDT/crvUSD | USDT | 48.6% | $54,360,949 | 42,163,143 | 135,000,000 | 77.6% | 0.99997 | -8.2 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 34.3% | $1,088,541 | 0 | 45,000,000 | 0.0% | 0.99961 | -11.8 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 42.2% | $14,795,184 | 0 | 9,000,000 | 0.0% | 0.99984 | -9.5 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 73.2% | $1,328,633 | 0 | 0 | 0.0% | 1.00079 | +8.2 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,335,478  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,409,429 | 23.3% | -10.0% | -2.4 bp | — | 0 |
| USDC | 31,933,403 | 19.9% | -13.4% | +2.3 bp | — | 0 |
| USDT | 90,992,647 | 56.8% | +23.4% | -4.5 bp | — | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$65,724,270  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 36,513,201 | 55.6% | +5.6% | -3.2 bp | — | 0 |
| RLUSD | 29,211,069 | 44.4% | -5.6% | -0.9 bp | — | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$54,360,949  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 26,420,550 | 48.6% | -1.4% | -0.7 bp | — | 0 |
| crvUSD | 27,940,400 | 51.4% | +1.4% | -1.3 bp | — | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,744,278  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 17,796,380 | 44.8% | -5.2% | -0.6 bp | — | 0 |
| USDC | 21,947,898 | 55.2% | +5.2% | -1.4 bp | — | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$30,485,850  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 9,910,598 | 32.5% | -17.5% | +1.9 bp | — | 0 |
| USDC | 20,575,253 | 67.5% | +17.5% | -4.2 bp | — | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,232  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,054,314 | 50.1% | +0.1% | -1.1 bp | — | 0 |
| USDtb | 10,020,918 | 49.9% | -0.1% | -1.0 bp | — | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$13,697,649  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,736,847 | 34.6% | -15.4% | +2.8 bp | — | 0 |
| crvUSD | 8,960,803 | 65.4% | +15.4% | -4.8 bp | — | 0 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,049  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,006,502 | 70.0% | +20.0% | -0.6 bp | — | 0 |
| USDT | 3,005,547 | 30.0% | -20.0% | +0.6 bp | — | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,165,251  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,927,325 | 42.9% | -7.1% | +4.9 bp | — | 0 |
| USDat | 5,237,927 | 57.1% | +7.1% | -7.0 bp | — | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$9,116,835  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 4,101,857 | 45.0% | -5.0% | +2.7 bp | — | 0 |
| USDC | 5,014,978 | 55.0% | +5.0% | -10.8 bp | — | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,800,852  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,776,646 | 42.9% | -7.1% | +1.4 bp | — | 0 |
| fxUSD | 5,024,206 | 57.1% | +7.1% | -3.5 bp | — | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,787,631  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,088,830 | 18.8% | -31.2% | +3.2 bp | — | 0 |
| USDT | 4,698,801 | 81.2% | +31.2% | -3.5 bp | — | 0 |

### trUSD/USDC  (`0xb723a224c9ACF3891B20437B4d55dd45600F5FA3`)

A = 1000  ·  TVL ~$5,156,141  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| trUSD | 2,730,048 | 52.9% | +2.9% | -2.2 bp | — | 0 |
| USDC | 2,426,093 | 47.1% | -2.9% | +0.2 bp | — | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,778,871  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,936,496 | 82.4% | +32.4% | -395.0 bp | — | 3 |
| USDC | 842,375 | 17.6% | -32.4% | +342.2 bp | — | 3 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,151,805  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 700,983 | 22.2% | -27.8% | +19.1 bp | — | 0 |
| USDf | 2,450,823 | 77.8% | +27.8% | -27.2 bp | — | 0 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,907  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,510,863 | 55.0% | +5.0% | -1.7 bp | — | 0 |
| USDC | 1,237,044 | 45.0% | -5.0% | -0.3 bp | — | 0 |

### rUSDY/USDC  (`0xe1fbaEc91b8A211db901AdF5ACc5b31f9A988279`)

A = 2000  ·  TVL ~$2,090,106  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| rUSDY | 1,072,676 | 51.3% | +1.3% | -4.3 bp | — | 0 |
| USDC | 1,017,430 | 48.7% | -1.3% | -3.7 bp | — | 0 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,468  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 720,112 | 41.1% | -8.9% | +0.2 bp | — | 0 |
| USDT | 1,030,357 | 58.9% | +8.9% | -2.3 bp | — | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,584,484  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 442,118 | 27.9% | -22.1% | +36.9 bp | — | 1 |
| USG | 1,142,366 | 72.1% | +22.1% | -46.6 bp | — | 1 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,097,566  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 675,124 | 61.5% | +11.5% | -4.5 bp | — | 0 |
| USDC | 422,443 | 38.5% | -11.5% | +2.4 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,055  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 803,979 | 80.3% | +30.3% | -1.5 bp | — | 0 |
| USDT | 197,076 | 19.7% | -30.3% | +1.5 bp | — | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
