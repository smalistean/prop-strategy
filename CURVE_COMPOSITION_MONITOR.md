# Curve composition monitor

Composition read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` A2; actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition`. Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T05:38:44Z  ·  pools admitted: 24 (discovery: api)  ·  stored 49 rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $233,249,040 | +0.133 | DAI/USDC/USDT | -4.0 bp | 0 |
| USDC | $385,836,419 | -0.037 | DAI/USDC/USDT | +1.0 bp | 0 |
| USDe | $34,253,312 | -0.270 | FRAX/USDe | +84.0 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,523,375  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,669,640 | 23.5% | -9.9% | -1.5 bp | — | 0 |
| USDC | 37,630,340 | 23.4% | -9.9% | +1.0 bp | — | 0 |
| USDT | 85,223,395 | 53.1% | +19.8% | -4.0 bp | — | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$65,429,911  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 36,219,231 | 55.4% | +5.4% | -3.1 bp | — | 0 |
| RLUSD | 29,210,681 | 44.6% | -5.4% | -0.9 bp | — | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$54,360,884  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 27,001,513 | 49.7% | -0.3% | -0.9 bp | — | 0 |
| crvUSD | 27,359,371 | 50.3% | +0.3% | -1.1 bp | — | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,793,305  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 20,666,290 | 51.9% | +1.9% | -1.2 bp | — | 0 |
| USDC | 19,127,015 | 48.1% | -1.9% | -0.8 bp | — | 0 |

### FRAX/USDe  (`0x5dc1BF6f1e983C0b21EfB003c105133736fA0743`)

A = 250  ·  TVL ~$34,253,312  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FRAX | 26,370,442 | 77.0% | +27.0% | -86.3 bp | — | 1 |
| USDe | 7,882,870 | 23.0% | -27.0% | +84.0 bp | — | 1 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$30,486,134  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 8,606,763 | 28.2% | -21.8% | +3.2 bp | — | 0 |
| USDC | 21,879,371 | 71.8% | +21.8% | -5.6 bp | — | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,204  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,012,700 | 49.9% | -0.1% | -1.0 bp | — | 0 |
| USDtb | 10,062,504 | 50.1% | +0.1% | -1.1 bp | — | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$13,365,564  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,886,294 | 36.6% | -13.4% | +2.1 bp | — | 0 |
| crvUSD | 8,479,270 | 63.4% | +13.4% | -4.1 bp | — | 0 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,053  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,083,427 | 70.7% | +20.7% | -0.6 bp | — | 0 |
| USDT | 2,928,626 | 29.3% | -20.7% | +0.6 bp | — | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,165,195  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,994,497 | 43.6% | -6.4% | +4.3 bp | — | 0 |
| USDat | 5,170,698 | 56.4% | +6.4% | -6.3 bp | — | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$9,109,752  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 4,188,893 | 46.0% | -4.0% | +1.4 bp | — | 0 |
| USDC | 4,920,859 | 54.0% | +4.0% | -9.5 bp | — | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,800,500  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,421,692 | 50.2% | +0.2% | -1.1 bp | — | 0 |
| fxUSD | 4,378,807 | 49.8% | -0.2% | -0.9 bp | — | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,601,206  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,121,234 | 20.0% | -30.0% | +2.8 bp | — | 0 |
| USDT | 4,479,972 | 80.0% | +30.0% | -3.1 bp | — | 0 |

### trUSD/USDC  (`0xb723a224c9ACF3891B20437B4d55dd45600F5FA3`)

A = 1000  ·  TVL ~$5,156,138  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| trUSD | 2,713,224 | 52.6% | +2.6% | -2.1 bp | — | 0 |
| USDC | 2,442,914 | 47.4% | -2.6% | +0.0 bp | — | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,779,133  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,948,516 | 82.6% | +32.6% | -405.7 bp | — | 3 |
| USDC | 830,617 | 17.4% | -32.6% | +353.0 bp | — | 3 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,151,757  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 700,934 | 22.2% | -27.8% | +19.1 bp | — | 0 |
| USDf | 2,450,823 | 77.8% | +27.8% | -27.2 bp | — | 0 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,907  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,510,750 | 55.0% | +5.0% | -1.7 bp | — | 0 |
| USDC | 1,237,157 | 45.0% | -5.0% | -0.3 bp | — | 0 |

### rUSDY/USDC  (`0xe1fbaEc91b8A211db901AdF5ACc5b31f9A988279`)

A = 2000  ·  TVL ~$2,090,106  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| rUSDY | 1,072,676 | 51.3% | +1.3% | -4.3 bp | — | 0 |
| USDC | 1,017,430 | 48.7% | -1.3% | -3.7 bp | — | 0 |

### FRAX/USDC  (`0xDcEF968d416a41Cdac0ED8702fAC8128A64241A2`)

A = 1500  ·  TVL ~$1,879,162  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FRAX | 1,697,745 | 90.3% | +40.3% | -89.2 bp | — | 1 |
| USDC | 181,417 | 9.7% | -40.3% | +86.6 bp | — | 1 |

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
| USDC | 442,147 | 27.9% | -22.1% | +36.8 bp | — | 1 |
| USG | 1,142,338 | 72.1% | +22.1% | -46.6 bp | — | 1 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,097,545  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 609,175 | 55.5% | +5.5% | -2.5 bp | — | 0 |
| USDC | 488,370 | 44.5% | -5.5% | +0.5 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,055  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 803,860 | 80.3% | +30.3% | -1.5 bp | — | 0 |
| USDT | 197,195 | 19.7% | -30.3% | +1.5 bp | — | 0 |

### USDC/USDSM  (`0xAC216046AB7Df980F1B8C5e254c922ef7e0a2d11`)

A = 1000  ·  TVL ~$1,000,042  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 482,647 | 48.3% | -1.7% | -0.3 bp | — | 0 |
| USDSM | 517,395 | 51.7% | +1.7% | -1.7 bp | — | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
