# Curve composition monitor — pre-registration

**Author:** Claude · **Frozen:** 2026-09-02 04:50 UTC, before the monitor produced any reading.
**Parent:** `STABLECOIN_DEPEG_DOSSIER.md` §7 (which required that this be pre-registered before
being judged against any outcome) and `CURVE_STABLESWAP_DD.md` (the geometry the thresholds come
from).
**Code:** `scripts/curve-composition-monitor.py`, runner `scripts/curve-monitor.sh`.

## What is being monitored, and why

Curve's StableSwap curve is flat until it is vertical: a coin can grow from 33% to 80% of a pool
while price moves ~36 bp, then move 20% between 80% and 95%. **Composition therefore leads price.**
This monitor records composition daily so that a drift is visible days before any price chart shows
one.

## Pools (verified on-chain 2026-09-02, coins read from each pool's own `coins(i)`)

| Pool | Address | Coins |
|---|---|---|
| 3pool | `0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7` | DAI, USDC, USDT |
| USDe/USDC | `0x02950460E2b9529D0E00284A5fA2d7bDF3fA4d72` | USDe, USDC |
| USDe/DAI | `0xF36a4BA50C603204c3FC6d2dA8b78A7b69CBC67d` | USDe, DAI |

These cover all three stablecoins in the dossier. Pools are read generically (loop `coins(i)` until
it reverts), so nothing is hardcoded about coin order or count.

## Metrics recorded per pool, per run

1. **Composition** — each coin's share of pool value, from `balances(i)` normalised by each token's
   `decimals()`. This is the leading indicator.
2. **Realised price impact** — the pool's **own** `get_dy` for a $1M swap of each coin into the
   next coin. Using the pool's on-chain math (rather than reimplementing it) makes this exact and
   correct for both classic and newer pool types.
3. `A` where exposed.

## Alert levels (pinned before any reading)

Composition thresholds are stated **relative to the pool's balanced share** (1/N), because a 65%
share means different things in a 2-coin and a 3-coin pool. `excess = share − 1/N`:

| Level | Condition | Meaning |
|---|---|---|
| 0 normal | excess < 0.32 and impact < 30 bp | nothing |
| **1 watch** | excess ≥ **0.32** (i.e. ≥65% in 3pool, ≥82% in a 2-coin pool), **or** +10pp share in 7 days, **or** impact ≥ 30 bp | re-read the dossier; journal it; no position change |
| **2 de-risk** | excess ≥ **0.42** (≥75% / ≥92%), **or** impact ≥ 100 bp | stop opening in that asset; move own capital off-venue |
| **3 act** | excess ≥ **0.52** (≥85% / ≥100%), **or** impact ≥ 300 bp | flatten own-capital positions in that asset; withdraw what can be withdrawn |

The 65/75/85% figures come from `CURVE_STABLESWAP_DD.md`'s computed curve for 3pool (the knee sits
near 80%); the `excess` formulation is the generalisation of those same points to other pool
widths. The impact thresholds mirror the dossier's price triggers.

## What this monitor does NOT establish

- **It is not evidence that composition drift precedes depegs.** That hypothesis is reasoned from
  the curve's geometry, not measured. The real-depeg sample is tiny (USDC/SVB 2023, UST 2022,
  a handful of minor events), so a proper test may never be adequately powered.
- **Thresholds are geometric design choices, not optimised values.** They were fixed here before
  any reading precisely so they cannot be tuned to whatever the first outputs happen to show.
- **A level-1 alert is not a prediction.** 3pool has sat at ~53% USDT chronically; a persistent
  reading at some level is a property of that pool, not a warning. Only *change* is informative,
  which is why the 7-day delta is a trigger in its own right.

## How it would be evaluated, if we ever do

Any future claim that this monitor has predictive value requires: a pre-declared depeg-event list,
the composition series leading each event, and a comparison against the base rate of the same
composition readings that were *not* followed by a depeg. Until that exists, the monitor is
**decision support for a written checklist**, not a signal.

---

## Amendment A1 — implementation corrections (2026-09-02 05:05 UTC, after the first readings)

Three changes were made **after** seeing initial output. All are disclosed here because the
pre-registration is worthless if deviations are silent. **None of them touches the composition
thresholds**, which remain exactly as frozen above.

1. **Probe size (bug fix).** The spec said a "$1M swap". Two of the pools hold under $500k total,
   so a $1M probe was larger than the pool and reported ~−7,700 bp "impact" for perfectly balanced
   pools — a guaranteed daily false alarm. Replaced with a **near-marginal probe: 0.1% of the
   source coin's balance, floored at $1,000**. This measures the marginal price (which is what the
   dossier's price triggers actually refer to) and is comparable across pools of any depth.
2. **Minimum-TVL gate (new).** Pools below **$10M TVL** are still reported but are marked
   informational and **cannot raise the overall alert level**. A $165k pool cannot carry a signal
   about a multi-billion-dollar stablecoin.
3. **Pool selection (corrected).** The two USDe pools originally listed (`USDe/USDC` $498k,
   `USDe/DAI` $165k) turned out to be negligible venues. Replaced with **FRAX/USDe**
   (`0x5dc1BF6f1e983C0b21EfB003c105133736fA0743`, ~$34M) — the deepest USDe pool paired against
   another nominal-$1 asset. Pools pairing against **sUSDe are deliberately excluded**: sUSDe
   accrues yield, so its share and price drift for reasons unrelated to any peg, which would make
   composition unreadable.

**Honest status of the first reading:** with the corrections in place the monitor returns level 0
for 3pool and **level 1 for FRAX/USDe** (77% FRAX / 23% USDe, ~85 bp dislocation). That alert is
driven by the *impact* rule, not the composition rule, and its direction indicates **FRAX trading
at a discount to USDe** — i.e. weakness in FRAX, an asset we do not hold. Whether 77/23 is chronic
for this pool is unknown until history accumulates, which is exactly why level 1 means "look and
journal", not "act".
