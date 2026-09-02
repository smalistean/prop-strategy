# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2, A3, A4); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T13:33:30Z  ·  composition pools: 21, wrapper pools: 1 (discovery: api)  ·  stored 43 composition rows; stored 1 wrapper rows

## Overall: NORMAL - no action

**Coverage gap (A4):** no admitted composition pool holds USDe - every pool with it is below the $1M admission or contains an excluded coin (FRAX). Monitored through the wrapper NAV metric and the API price band only.

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $233,246,417 | +0.156 | DAI/USDC/USDT | -4.4 bp | 0 |
| USDC | $383,591,321 | -0.049 | DAI/USDC/USDT | +2.3 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2462 DOLA≈USDe | 1.2486 DOLA | **+19.6 bp** | $49,978,651 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,334,280  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,524,559 | 23.4% | -9.9% | -2.4 bp | — | 0 |
| USDC | 31,814,297 | 19.8% | -13.5% | +2.3 bp | — | 0 |
| USDT | 90,995,424 | 56.8% | +23.4% | -4.4 bp | — | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$65,724,270  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 36,513,588 | 55.6% | +5.6% | -3.2 bp | — | 0 |
| RLUSD | 29,210,682 | 44.4% | -5.6% | -0.9 bp | — | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$54,360,949  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 26,422,800 | 48.6% | -1.4% | -0.7 bp | — | 0 |
| crvUSD | 27,938,149 | 51.4% | +1.4% | -1.3 bp | — | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,796,683  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 18,080,006 | 45.4% | -4.6% | -0.6 bp | — | 0 |
| USDC | 21,716,677 | 54.6% | +4.6% | -1.4 bp | — | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$30,485,807  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 9,873,480 | 32.4% | -17.6% | +1.9 bp | — | 0 |
| USDC | 20,612,327 | 67.6% | +17.6% | -4.2 bp | — | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,232  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,054,314 | 50.1% | +0.1% | -1.1 bp | — | 0 |
| USDtb | 10,020,918 | 49.9% | -0.1% | -1.0 bp | — | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$13,697,646  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,744,455 | 34.6% | -15.4% | +2.7 bp | — | 0 |
| crvUSD | 8,953,191 | 65.4% | +15.4% | -4.8 bp | — | 0 |

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

A = 300  ·  TVL ~$9,116,803  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 4,139,074 | 45.4% | -4.6% | +2.2 bp | — | 0 |
| USDC | 4,977,729 | 54.6% | +4.6% | -10.3 bp | — | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,800,852  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,775,723 | 42.9% | -7.1% | +1.4 bp | — | 0 |
| fxUSD | 5,025,129 | 57.1% | +7.1% | -3.5 bp | — | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,787,616  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,135,344 | 19.6% | -30.4% | +2.9 bp | — | 0 |
| USDT | 4,652,272 | 80.4% | +30.4% | -3.2 bp | — | 0 |

### trUSD/USDC  (`0xb723a224c9ACF3891B20437B4d55dd45600F5FA3`)

A = 1000  ·  TVL ~$5,156,140  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| trUSD | 2,725,342 | 52.9% | +2.9% | -2.2 bp | — | 0 |
| USDC | 2,430,798 | 47.1% | -2.9% | +0.1 bp | — | 0 |

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

A = 1500  ·  TVL ~$1,097,567  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 675,787 | 61.6% | +11.6% | -4.5 bp | — | 0 |
| USDC | 421,780 | 38.4% | -11.6% | +2.4 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,055  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 804,467 | 80.4% | +30.4% | -1.5 bp | — | 0 |
| USDT | 196,587 | 19.6% | -30.4% | +1.5 bp | — | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
