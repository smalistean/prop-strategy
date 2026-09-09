# Curve composition monitor

Composition and wrapper NAV read from each pool's own on-chain state; pools discovered per
`CURVE_MONITOR_PREREGISTRATION.md` (A2-A6); actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Stored in PostgreSQL `curve_pool_composition` / `curve_wrapper_nav_discount` / `curve_pegkeeper_state`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-09T06:15:03Z  ·  composition pools: 23, wrapper pools: 1 (discovery: api)  ·  stored 47 composition rows; stored 1 wrapper rows (A6 corrected 1); stored 5 pegkeeper rows

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

## Per-coin aggregate (TVL-weighted excess across every admitted pool holding the coin)

| Coin | Pools TVL | Aggregate excess | Deepest pool | its marginal impact | Level |
|---|---:|---:|---|---:|---:|
| USDT | $216,975,702 | +0.068 | DAI/USDC/USDT | -2.6 bp | 0 |
| USDC | $367,328,326 | -0.022 | DAI/USDC/USDT | -0.4 bp | 0 |
| USDe | $1,046,714 | -0.084 | USDT/USDe | +0.0 bp | 0 |

Aggregate excess isolates the coin itself: a coin under real redemption pressure is
over-weighted in *every* pool it sits in; a single skewed pool is about the other coin.

## Wrapper NAV discount (Ethena redemption/cooldown stress — A3, separate from composition)

| Pool | Wrapper | NAV (redeems for) | Pool-implied price | Discount to NAV | TVL | Level |
|---|---|---:|---:|---:|---:|---:|
| DOLA/sUSDe | sUSDe | 1.2472 DOLA≈USDe | 1.2499 DOLA | **+22.0 bp** | $60,745,299 | 0 |

Negative = holders paying to exit ahead of the up-to-90-day cooldown. This is a liquidity/
duration signal about the wrapper, not a USDe depeg — which is why it is kept apart.

### Counter-asset correction (A6 - the wrapper reading restated in dollars)

| Pool | Counter | Counter in $ (route) | Counter share in its pool | Raw discount | Discount in $ | Par capacity (PSM) | Reliable |
|---|---|---:|---:|---:|---:|---:|---|
| DOLA/sUSDe | DOLA | 0.99722 (-27.8 bp via sUSDS) | 78.0% | +22.0 bp | **-5.9 bp** | $0 | yes |

The level uses the dollar-restated discount: a counter-asset with no working par path (DOLA) enters the
sUSDe reading one for one (`DOLA_INVERSE_DD.md`). Par capacity = USDS actually redeemable from the DOLA PSM.

## crvUSD PegKeepers (A5 - the contract that rebalances the crvUSD pools we read)

Aggregate crvUSD price **0.99994** -> PegKeepers may only WITHDRAW (a counter-coin inflow is NOT damped; the share reading is free).

| Pool | Counter | Counter share | TVL | PK debt | Ceiling | PK LP share | Oracle price | Gap vs other PK pools | Provide allowed | Level |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| USDC/crvUSD | USDC | 44.3% | $14,403,361 | 0 | 135,000,000 | 0.0% | 0.99988 | -11.3 bp | 0 | 0 |
| USDT/crvUSD | USDT | 48.6% | $34,832,350 | 22,585,369 | 135,000,000 | 64.8% | 0.99997 | -10.4 bp | 0 | 0 |
| PYUSD/crvUSD _(thin)_ | PYUSD | 38.0% | $1,204,598 | 0 | 45,000,000 | 0.0% | 0.99973 | -12.8 bp | 0 | 0 |
| frxUSD/crvUSD | frxUSD | 47.7% | $15,267,627 | 0 | 9,000,000 | 0.0% | 0.99995 | -10.6 bp | 0 | 0 |
| GHO/crvUSD _(thin)_ | GHO | 76.4% | $1,339,686 | 0 | 0 | 0.0% | 1.00101 | +10.4 bp | 0 | 0 |

Gap = this pool's crvUSD oracle price minus the highest of the other PegKeeper pools; the Regulator blocks
`provide` above +3 bp (its `worst_price_threshold`) - Curve's own 'this pool's stablecoin is being sold' test.
Levels (tracked counter-coins only, pools >= $10M): 1 at >= +3 bp with the pool counter-heavy, 2 at >= +30 bp, 3 at >= +100 bp.

## Pools (deepest first)

### DAI/USDC/USDT  (`0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`)

A = 4000  ·  TVL ~$160,329,781  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 45,639,356 | 28.5% | -4.9% | -1.5 bp | +5.0 pp | 0 |
| USDC | 44,986,320 | 28.1% | -5.3% | -0.4 bp | +4.6 pp | 0 |
| USDT | 69,704,105 | 43.5% | +10.1% | -2.6 bp | -9.6 pp | 0 |

### USDC/RLUSD  (`0xD001aE433f254283FeCE51d4ACcE8c53263aa186`)

A = 2000  ·  TVL ~$62,906,046  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 33,523,335 | 53.3% | +3.3% | -2.7 bp | -2.1 pp | 0 |
| RLUSD | 29,382,711 | 46.7% | -3.3% | -1.3 bp | +2.1 pp | 0 |

### PYUSD/USDC  (`0x383E6b4437b59fff47B619CBA855CA29342A8559`)

A = 5000  ·  TVL ~$39,005,280  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| PYUSD | 17,485,421 | 44.8% | -5.2% | -0.6 bp | -3.9 pp | 0 |
| USDC | 21,519,859 | 55.2% | +5.2% | -1.4 bp | +3.9 pp | 0 |

### USDT/crvUSD  (`0x390f3595bCa2Df7d23783dFd126427CCeb997BF4`)

A = 2000  ·  TVL ~$34,832,350  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 16,936,613 | 48.6% | -1.4% | -0.7 bp | -1.0 pp | 0 |
| crvUSD | 17,895,736 | 51.4% | +1.4% | -1.3 bp | +1.0 pp | 0 |

### USDC/USDtb  (`0xC2921134073151490193AC7369313c8e0b08e1E7`)

A = 800  ·  TVL ~$20,075,710  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 10,586,757 | 52.7% | +2.7% | -2.4 bp | +2.9 pp | 0 |
| USDtb | 9,488,953 | 47.3% | -2.7% | +0.4 bp | -2.9 pp | 0 |

### USDG/USDC  (`0xc061caa073f3d95F80f8e5428d32D2d76F5e1622`)

A = 3000  ·  TVL ~$20,029,235  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDG | 8,415,636 | 42.0% | -8.0% | +0.1 bp | +12.9 pp | 1 |
| USDC | 11,613,600 | 58.0% | +8.0% | -2.1 bp | -12.9 pp | 0 |

### USDC/crvUSD  (`0x4DEcE678ceceb27446b35C672dC7d61F30bAD69E`)

A = 2000  ·  TVL ~$14,403,361  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 6,380,519 | 44.3% | -5.7% | +0.2 bp | +4.9 pp | 0 |
| crvUSD | 8,022,843 | 55.7% | +5.7% | -2.2 bp | -4.9 pp | 0 |

### BOLD/USDC  (`0xEFc6516323FbD28e80B85A497B65A86243a54B3E`)

A = 300  ·  TVL ~$10,299,799  ·  pool level: **1**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| BOLD | 5,969,711 | 58.0% | +8.0% | -15.2 bp | +11.9 pp | 1 |
| USDC | 4,330,088 | 42.0% | -8.0% | +7.0 bp | -11.9 pp | 0 |

### USAT/USDT  (`0x0Bdb2c3AF83EE1d3196FA64d3162e54624B5f6b0`)

A = 20000  ·  TVL ~$10,012,053  ·  pool level: **0**

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USAT | 7,083,719 | 70.8% | +20.8% | -0.6 bp | +0.0 pp | 0 |
| USDT | 2,928,334 | 29.2% | -20.8% | +0.6 bp | -0.0 pp | 0 |

### USDC/USDat  (`0xF4d0CF32908b2C7f1021339c43Df0F77f06896d7`)

A = 500  ·  TVL ~$9,269,454  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,335,454 | 46.8% | -3.2% | +1.6 bp | +3.2 pp | 0 |
| USDat | 4,934,000 | 53.2% | +3.2% | -3.6 bp | -3.2 pp | 0 |

### USDC/fxUSD  (`0x5018BE882DccE5E3F2f3B0913AE2096B9b3fB61f`)

A = 1200  ·  TVL ~$8,558,638  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 4,141,590 | 48.4% | -1.6% | -0.5 bp | -1.9 pp | 0 |
| fxUSD | 4,417,048 | 51.6% | +1.6% | -1.5 bp | +1.9 pp | 0 |

### USDC/USDT  (`0x4f493B7dE8aAC7d55F71853688b1F7C8F0243C85`)

A = 10000  ·  TVL ~$5,835,133  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 1,857,366 | 31.8% | -18.2% | +0.8 bp | +12.6 pp | 1 |
| USDT | 3,977,767 | 68.2% | +18.2% | -1.1 bp | -12.6 pp | 0 |

### apxUSD/USDC  (`0x6F63deEDc9870D6c16FC644C6654748352cdc87c`)

A = 100  ·  TVL ~$4,778,506  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| apxUSD | 3,815,724 | 79.9% | +29.9% | -305.2 bp | -2.8 pp | 3 |
| USDC | 962,782 | 20.1% | -29.9% | +252.6 bp | +2.8 pp | 2 |

### USDC/USDf  (`0x72310DAAed61321b02B08A547150c07522c6a976`)

A = 1000  ·  TVL ~$3,153,509  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 774,137 | 24.5% | -25.5% | +14.7 bp | +2.3 pp | 0 |
| USDf | 2,379,372 | 75.5% | +25.5% | -22.3 bp | -2.3 pp | 0 |

### FIDD/USDC  (`0xE47E8Ced9D94AA43C922627782E29b41a93202AF`)

A = 3000  ·  TVL ~$2,747,924  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 1,442,195 | 52.5% | +2.5% | -1.3 bp | -2.5 pp | 0 |
| USDC | 1,305,729 | 47.5% | -2.5% | -0.7 bp | +2.5 pp | 0 |

### USDx/USDT  (`0xE521BA88837B2726E5343ccA91adA6f19F356C45`)

A = 1000  ·  TVL ~$2,168,123  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDx | 902,024 | 41.6% | -8.4% | +2.5 bp | — | 0 |
| USDT | 1,266,099 | 58.4% | +8.4% | -4.6 bp | — | 0 |

### rUSDY/USDC  (`0xe1fbaEc91b8A211db901AdF5ACc5b31f9A988279`)

A = 2000  ·  TVL ~$2,090,853  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| rUSDY | 1,119,266 | 53.5% | +3.5% | -4.7 bp | +2.2 pp | 0 |
| USDC | 971,587 | 46.5% | -3.5% | -3.3 bp | -2.2 pp | 0 |

### FIDD/USDT  (`0x8273Cb2cF9AF3228fD14AF25B5B1De2A9676C372`)

A = 3000  ·  TVL ~$1,750,493  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FIDD | 831,891 | 47.5% | -2.5% | -0.7 bp | +6.4 pp | 0 |
| USDT | 918,602 | 52.5% | +2.5% | -1.3 bp | -6.4 pp | 0 |

### USDC/USG  (`0x97BA10115da528c113462EDE9C20D7adc806D93f`)

A = 325  ·  TVL ~$1,747,462  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 461,368 | 26.4% | -23.6% | +42.7 bp | -1.5 pp | 1 |
| USG | 1,286,094 | 73.6% | +23.6% | -52.8 bp | +1.5 pp | 1 |

### OUSD/USDC  (`0x6d18E1a7faeB1F0467A77C0d293872ab685426dc`)

A = 1500  ·  TVL ~$1,097,561  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| OUSD | 651,141 | 59.3% | +9.3% | -3.7 bp | +3.8 pp | 0 |
| USDC | 446,420 | 40.7% | -9.3% | +1.6 bp | -3.8 pp | 0 |

### USDT/USDe  (`0x5B03CcCAb7BA3010fA5CAd23746cbf0794938e96`)

A = 10000  ·  TVL ~$1,046,714  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDT | 611,283 | 58.4% | +8.4% | -0.7 bp | — | 0 |
| USDe | 435,431 | 41.6% | -8.4% | +0.0 bp | — | 0 |

### USDQ/USDT  (`0x5a8C7623FEe10542614e492c670a67e3DfE922F8`)

A = 20000  ·  TVL ~$1,001,054  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDQ | 801,525 | 80.1% | +30.1% | -1.5 bp | -0.2 pp | 0 |
| USDT | 199,529 | 19.9% | -30.1% | +1.5 bp | +0.2 pp | 0 |

### USDC/USDSM  (`0xAC216046AB7Df980F1B8C5e254c922ef7e0a2d11`)

A = 1000  ·  TVL ~$1,000,075  ·  pool level: **0**  ·  _below $10M TVL — informational, cannot raise the overall level_

| Coin | Balance | Share | Excess | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| USDC | 568,517 | 56.8% | +6.8% | -3.9 bp | +8.6 pp | 0 |
| USDSM | 431,558 | 43.2% | -6.8% | +1.8 bp | -8.6 pp | 0 |

---

**Reading it:** the over-weighted coin is the one being sold into the pool, and its marginal
impact is negative — it is the cheap side. A positive impact means that coin trades at a
premium. Check which side is over-weighted before acting on any pool-level alert.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical beyond
it. A persistent level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
