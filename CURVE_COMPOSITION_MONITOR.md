# Curve composition monitor

Snapshot read from each pool's own on-chain state. Thresholds frozen in
`CURVE_MONITOR_PREREGISTRATION.md`; actions in `STABLECOIN_DEPEG_DOSSIER.md`.
Regenerate with `bash scripts/curve-monitor.sh`.

**As of:** 2026-09-02T04:59:28Z  |  history rows: 10

## Overall: LEVEL 1 WATCH - re-read the dossier, journal it, no position change

### 3pool  (0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7)

A = 4000  ·  TVL ~$160,513,272  ·  pool level: **0**

| Coin | Balance | Share | Excess over balanced | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| DAI | 37,669,640 | 23.5% | -9.9% | -1.5 bp | — | 0 |
| USDC | 37,620,363 | 23.4% | -9.9% | +1.0 bp | — | 0 |
| USDT | 85,223,269 | 53.1% | +19.8% | -4.0 bp | — | 0 |

### FRAX/USDe  (0x5dc1BF6f1e983C0b21EfB003c105133736fA0743)

A = 250  ·  TVL ~$34,253,314  ·  pool level: **1**

| Coin | Balance | Share | Excess over balanced | marginal impact | 7d share change | Level |
|---|---:|---:|---:|---:|---:|---:|
| FRAX | 26,370,614 | 77.0% | +27.0% | -86.3 bp | — | 1 |
| USDe | 7,882,700 | 23.0% | -27.0% | +84.0 bp | — | 1 |

---

**Reading it:** a coin that is *over*-weighted is the one being sold into the pool, and
its marginal impact is negative — it is the cheap side. A positive impact means that coin
trades at a premium. An alert names a *pool* dislocation, not necessarily a problem with
the coin we hold: check which side is over-weighted before acting.

Composition leads price: the StableSwap curve is flat to ~80% imbalance and vertical
beyond it, so a share drift is visible days before any price chart moves. A persistent
level (3pool has sat near 53% USDT for years) is not a warning; **change is.**
