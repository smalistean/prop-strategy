# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2, A3); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T06:12:44Z  ·  composition pools: 24, wrapper pools: 1 (discovery: api)  ·  stored 49 composition rows; stored 1 wrapper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $233,256,733 | +0.133 | DAI/USDC/USDT | -4.0 bp | 0 |
| USDC | $385,844,085 | -0.033 | DAI/USDC/USDT | +1.0 bp | 0 |
| USDe | $34,253,312 | -0.270 | FRAX/USDe | +84.0 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2461 DOLA≈USDe | 1.2486 DOLA | **+19.6 bp** | $49,978,147 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,531,054  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,669,640 | 23.5% | -9.9% | -1.5 bp | — | 0 |
| USDC | 37,638,020 | 23.4% | -9.9% | +1.0 bp | — | 0 |
| USDT | 85,223,394 | 53.1% | +19.8% | -4.0 bp | — | 0 |

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
| USDT | 27,002,020 | 49.7% | -0.3% | -0.9 bp | — | 0 |
| crvUSD | 27,358,863 | 50.3% | +0.3% | -1.1 bp | — | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,793,364  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 19,400,375 | 48.8% | -1.2% | -0.9 bp | — | 0 |
| USDC | 20,392,989 | 51.2% | +1.2% | -1.1 bp | — | 0 |

### FRAX/USDe  (`0x5dc1BF6f1e983C0b21EfB003c105133736fA0743`)

A = 250  ·  TVL ~$34,253,312  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FRAX | 26,370,442 | 77.0% | +27.0% | -86.3 bp | — | 1 |
| USDe | 7,882,870 | 23.0% | -27.0% | +84.0 bp | — | 1 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$30,486,035  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 8,879,805 | 29.1% | -20.9% | +2.9 bp | — | 0 |
| USDC | 21,606,229 | 70.9% | +20.9% | -5.3 bp | — | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,204  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,012,700 | 49.9% | -0.1% | -1.0 bp | — | 0 |
| USDtb | 10,062,504 | 50.1% | +0.1% | -1.1 bp | — | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$13,365,481  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 5,262,083 | 39.4% | -10.6% | +1.3 bp | — | 0 |
| crvUSD | 8,103,398 | 60.6% | +10.6% | -3.3 bp | — | 0 |

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
| USDC | 3,994,608 | 43.6% | -6.4% | +4.3 bp | — | 0 |
| USDat | 5,170,586 | 56.4% | +6.4% | -6.3 bp | — | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$9,109,749  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 4,197,355 | 46.1% | -3.9% | +1.2 bp | — | 0 |
| USDC | 4,912,394 | 53.9% | +3.9% | -9.3 bp | — | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,800,547  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,421,749 | 50.2% | +0.2% | -1.1 bp | — | 0 |
| fxUSD | 4,378,798 | 49.8% | -0.2% | -0.9 bp | — | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,601,220  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,077,565 | 19.2% | -30.8% | +3.0 bp | — | 0 |
| USDT | 4,523,655 | 80.8% | +30.8% | -3.4 bp | — | 0 |

### trUSD/USDC  (`0xb723a224c9ACF3891B20437B4d55dd45600F5FA3`)

A = 1000  ·  TVL ~$5,156,140  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| trUSD | 2,724,486 | 52.8% | +2.8% | -2.2 bp | — | 0 |
| USDC | 2,431,654 | 47.2% | -2.8% | +0.1 bp | — | 0 |

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

A = 1500  ·  TVL ~$1,879,212  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FRAX | 1,697,768 | 90.3% | +40.3% | -89.2 bp | — | 1 |
| USDC | 181,444 | 9.7% | -40.3% | +86.6 bp | — | 1 |

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
| USDQ | 803,835 | 80.3% | +30.3% | -1.5 bp | — | 0 |
| USDT | 197,220 | 19.7% | -30.3% | +1.5 bp | — | 0 |

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
