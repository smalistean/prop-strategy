# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T14:46:24Z  ·  composition pools: 21, wrapper pools: 1 (discovery: api)  ·  stored 43 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: NORMAL - no action

**Coverage gap (A4):** no admitted composition pool holds USDe - every pool with it is below the $1M admission or contains an excluded coin (FRAX). Monitored through the wrapper NAV metric and the API price band only.

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $233,251,788 | +0.158 | DAI/USDC/USDT | -4.5 bp | 0 |
| USDC | $383,544,591 | -0.048 | DAI/USDC/USDT | +2.5 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2462 DOLA≈USDe | 1.2486 DOLA | **+19.6 bp** | $49,978,738 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99723 (-27.7 bp via sUSDS) | 78.0% | +19.6 bp | **-8.2 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99989** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 33.6% | $13,697,715 | 0 | 135,000,000 | 0.0% | 0.99960 | -11.8 bp | 0 | 0 |
| USDT/crvUSD | USDT | 48.6% | $54,360,951 | 42,163,143 | 135,000,000 | 77.6% | 0.99997 | -8.1 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 33.6% | $1,088,544 | 0 | 45,000,000 | 0.0% | 0.99960 | -11.8 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 43.1% | $14,795,179 | 0 | 9,000,000 | 0.0% | 0.99985 | -9.3 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 73.8% | $1,328,639 | 0 | 0 | 0.0% | 1.00078 | +8.1 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,335,672  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,409,429 | 23.3% | -10.0% | -2.5 bp | — | 0 |
| USDC | 31,433,598 | 19.6% | -13.7% | +2.5 bp | — | 0 |
| USDT | 91,492,646 | 57.1% | +23.7% | -4.5 bp | — | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$65,724,270  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 36,513,201 | 55.6% | +5.6% | -3.2 bp | — | 0 |
| RLUSD | 29,211,069 | 44.4% | -5.6% | -0.9 bp | — | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$54,360,951  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 26,404,485 | 48.6% | -1.4% | -0.7 bp | — | 0 |
| crvUSD | 27,956,465 | 51.4% | +1.4% | -1.3 bp | — | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,744,308  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 17,390,904 | 43.8% | -6.2% | -0.5 bp | — | 0 |
| USDC | 22,353,405 | 56.2% | +6.2% | -1.5 bp | — | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$30,485,969  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 9,707,542 | 31.8% | -18.2% | +2.1 bp | — | 0 |
| USDC | 20,778,427 | 68.2% | +18.2% | -4.4 bp | — | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,232  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,054,314 | 50.1% | +0.1% | -1.1 bp | — | 0 |
| USDtb | 10,020,918 | 49.9% | -0.1% | -1.0 bp | — | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$13,697,715  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,607,515 | 33.6% | -16.4% | +3.1 bp | — | 0 |
| crvUSD | 9,090,200 | 66.4% | +16.4% | -5.1 bp | — | 0 |

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
| BOLD | 4,101,483 | 45.0% | -5.0% | +2.7 bp | — | 0 |
| USDC | 5,015,352 | 55.0% | +5.0% | -10.8 bp | — | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,800,852  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 3,778,785 | 42.9% | -7.1% | +1.4 bp | — | 0 |
| fxUSD | 5,022,067 | 57.1% | +7.1% | -3.5 bp | — | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,791,594  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,107,959 | 19.1% | -30.9% | +3.1 bp | — | 0 |
| USDT | 4,683,634 | 80.9% | +30.9% | -3.4 bp | — | 0 |

### trUSD/USDC  (`0xb723a224c9ACF3891B20437B4d55dd45600F5FA3`)

A = 1000  ·  TVL ~$5,156,141  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| trUSD | 2,730,447 | 53.0% | +3.0% | -2.2 bp | — | 0 |
| USDC | 2,425,694 | 47.0% | -3.0% | +0.2 bp | — | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,778,871  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,936,496 | 82.4% | +32.4% | -395.0 bp | — | 3 |
| USDC | 842,375 | 17.6% | -32.4% | +342.2 bp | — | 3 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,151,820  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 695,351 | 22.1% | -27.9% | +19.5 bp | — | 0 |
| USDf | 2,456,468 | 77.9% | +27.9% | -27.6 bp | — | 0 |

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

A = 325  ·  TVL ~$1,584,482  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 442,746 | 27.9% | -22.1% | +36.7 bp | — | 1 |
| USG | 1,141,736 | 72.1% | +22.1% | -46.5 bp | — | 1 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,097,566  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 674,776 | 61.5% | +11.5% | -4.5 bp | — | 0 |
| USDC | 422,790 | 38.5% | -11.5% | +2.3 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,055  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 804,215 | 80.3% | +30.3% | -1.5 bp | — | 0 |
| USDT | 196,839 | 19.7% | -30.3% | +1.5 bp | — | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
