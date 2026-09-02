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

---

## Amendment A2 — pool discovery, per-coin aggregation, PostgreSQL storage (2026-09-02 06:20 UTC, declared before any aggregate reading)

### Why aggregate
The first FRAX/USDe reading (77% FRAX / 23% USDe, ~85 bp) is ambiguous by construction: a two-coin
pool cannot say whether FRAX is weak or USDe is strong. Only looking at USDe **across all the pools it
sits in, against many different counter-assets,** isolates USDe itself. A coin under genuine
redemption pressure is over-weighted everywhere at once; a single skewed pool is about the other coin.

### Discovery rule (frozen)
Pools are discovered from the Curve public API (`api.curve.finance/api/getPools/all/ethereum`) and
admitted only if **all** of:
- Ethereum mainnet, `isMetaPool = false`, `isBroken = false`, no coin `isBasePoolLpToken` — plain
  coin-vs-coin pools only, so "share" has one clear meaning;
- `usdTotal ≥ $1,000,000` — below this a pool is dust and only costs RPC calls (weighting already
  makes dust irrelevant);
- contains at least one **tracked coin** (USDT, USDC, USDe);
- **every** coin's API `usdPrice` lies in **[0.85, 1.03]**. The band is deliberately asymmetric:
  anything trading *rich* (>1.03) is yield-bearing or non-USD (sUSDe 1.25, sDAI, EURS 1.19, WBTC) and
  would make composition unreadable, so it is excluded; anything trading *cheap* stays in scope
  down to 0.85, because a coin depegging is exactly when we must not lose sight of its pools.
Two pools are **pinned** regardless of the API (3pool and FRAX/USDe) so the monitor never goes blind.
The API is used **only for discovery and static coin metadata** (symbol, decimals, address);
every balance, A and price impact is read **on-chain** as before. The last good pool list is cached
locally; if the API is unavailable the cache is used, and if there is no cache the pinned pools are.
The discovered universe is itself recorded every run (distinct pools per `observed_at`), so any
drift in what is being monitored is auditable after the fact.

### Aggregate metric (frozen)
For each tracked coin X, across every admitted pool P containing X:

`aggregate_excess(X) = Σ_P TVL_P × excess_{P,X} / Σ_P TVL_P`, where `excess_{P,X} = share_{P,X} − 1/N_P`.

Zero means balanced everywhere; positive means X is being sold into pools on average. It is
TVL-weighted by construction (3pool's $160M dominates USDT/USDC, as it should). **The same excess
thresholds apply to the aggregate as to a single pool: 0.32 / 0.42 / 0.52.** Price impact is not
aggregated (curves do not add); the deepest admitted pool's marginal impact is reported per coin.

### Overall level (frozen)
`max(per-pool level for pools ≥ $10M TVL, per-coin aggregate level)`. Thin pools still cannot raise
the overall level on their own, but they do contribute their (small) weight to the aggregates.

### Storage
Rows go to PostgreSQL table `curve_pool_composition` (migration V31), one row per
(observed_at, pool, coin), matching how every other time series in this repository is kept. The
7-day-delta trigger reads from that table. The CSV from the first version is retired.

### Honest limits added by this amendment
- The Curve API is now a discovery dependency (mitigated by the cache and the pinned pools).
- The 1.03 ceiling will drop any $1 coin that trades ≥3% *rich* — rare for a real stablecoin, and a
  coin trading rich is not a depeg risk to us, so this is accepted.
- The API's `usdPrice` is used only as an admission filter, never as the measurement.

---

## Amendment A3 — wrapper NAV discount as a separate metric (2026-09-02 09:05 UTC, declared before any reading)

### What it measures and why it is separate
sUSDe (Ethena's ERC-4626 staked share) is deliberately **excluded from the USDe composition
aggregate**: 1 sUSDe = 1.2461 USDe and rising, the pools that hold it run a rate oracle
(`stored_rates` = [1, NAV]) so their invariant is already NAV-scaled, and — decisive — a skew in an
sUSDe pool conflates two different stresses: doubt about the USDe peg, and people paying to skip the
up-to-90-day cooldown while USDe sits at $1. Folding it into composition would blur exactly the
distinction the dossier needs.

The same pool yields a **different** signal that is worth having on its own: the pool-implied price
of the wrapper versus what it redeems for.

`nav_discount_bp(P) = (implied_P / NAV − 1) × 10^4`, where `NAV = wrapper.convertToAssets(1e18)`
and `implied_P` = the pool's own `get_dy` for a near-marginal probe (0.1% of the wrapper balance,
floor 1,000 tokens) of wrapper → counter-asset, per unit. Negative = the wrapper trades **below**
redemption value = holders paying to exit ahead of the cooldown = Ethena redemption/cooldown stress.

### Admission (frozen)
Pools from the same Curve API pull where one coin's symbol is a configured wrapper (initially only
`sUSDe`), **every other coin** is a nominal-$1 stable inside the same asymmetric 0.85–1.03 band, no
metapool / LP coins, `usdTotal ≥ $1M`. The counter-asset must be ~$1 so that "counter per sUSDe" is
comparable to "USDe per sUSDe". Pools whose counter is itself yield-bearing (sDAI/sUSDe,
scrvUSD/sUSDe) are therefore out.

### Thresholds (frozen — design choices, not measured optima)
sUSDe has traded within a few tenths of a percent of NAV in normal conditions (+0.196% today).

| Level | nav_discount | Meaning |
|---|---|---|
| 0 | > −50 bp | normal (premium or negligible discount) |
| 1 watch | ≤ **−50 bp** | holders paying ≥0.5% to skip the cooldown |
| 2 de-risk | ≤ **−200 bp** | sustained redemption pressure |
| 3 act | ≤ **−500 bp** | redemption panic; treat as the dossier's level 3 for USDe |

Same $10M TVL gate as composition: thinner wrapper pools are informational only. Overall level =
max over composition pools, per-coin aggregates, and admitted wrapper pools.

### Storage
`curve_wrapper_nav_discount` (migration V32), one row per (observation, pool). Named generically so
other wrappers (e.g. sDAI vs DAI) can be added later under the same rule.

### Limits stated in advance
- The counter-asset's own peg contaminates the reading: DOLA at 0.9978 makes sUSDe look ~22 bp
  *richer* than it is. At the 50 bp threshold this is tolerable; it is why the first threshold is not
  smaller.
- A discount to NAV is a **liquidity/duration** signal about the wrapper. It is Ethena-specific
  stress, not a USDe depeg — which is exactly why it is reported separately.

## Amendment A4 — 2026-09-02 13:35 UTC: admission requires a live par-redemption path; FRAX pools removed

**What triggered it.** Each of the three readings so far raised LEVEL 1 from the same source:
FRAX/USDe at 77/23 and FRAX/USDC at 90/10, FRAX priced 0.9914 by the API. `FRAX_LEGACY_FRXUSD_DD.md`
read the token: legacy FRAX has no issuer redemption (FraxPoolV3 holds zero collateral, the v1 pool
is paused, the 94.5% "collateral ratio" is AMO self-accounting), the 1:1 migration to frxUSD ended
with FIP-430 (2025-04-21) and the Fraxtal bridge pair no longer maps to it, and the only exit is the
secondary market — at −87 to −91 bp with every pool 77–94% FRAX. That is a structural discount, not
redemption pressure, and it would hold the overall level at 1 indefinitely while saying nothing
about USDT, USDC or USDe.

**Rule change (post-hoc, disclosed).** A coin is admissible only if it has a live par path: issuer
redemption open at least to whitelisted parties, or a permissionless mint/redeem module. The price
band cannot catch this case — 0.9914 is inside 0.85–1.03 by design, so that a depegging coin stays in
scope. Implementation: `EXCLUDED_COINS = {"FRAX": …}` in the script, checked before the band; any
pool containing an excluded coin leaves both the composition and the wrapper universe. FRAX/USDe is
no longer pinned. Adding a coin to the list requires a DD note naming the missing path.

**Consequence disclosed: USDe composition coverage drops to zero.** FRAX/USDe ($34M) was the only
USDe pool above the $1M admission; the next largest are USDT/USDe ($0.9M) and USDe/USDC ($0.5M). The
report now prints a coverage-gap line for any tracked coin with no admitted pool. USDe stress is
watched through A3 (sUSDe discount to NAV) and the API price band until a USDe pool ≥ $1M appears.
The $10M level gate is not lowered for it — that would reintroduce the thin-pool noise A1 exists for.

**Effect on the readings so far.** The three LEVEL 1 readings (2026-09-01 – 2026-09-02) are
reclassified as structural-FRAX, not stablecoin stress; they stay in the table as recorded. The USDT
(+0.13) and USDC (−0.04) aggregates never depended on a FRAX pool above the level gate.

## Amendment A5 — 2026-09-02 14:17 UTC: crvUSD PegKeeper state, and the Regulator's relative-gap test as a trigger

**What triggered it.** Two admitted pools, USDC/crvUSD ($13.7M) and USDT/crvUSD ($54M), have a
PegKeeper — a Curve-owned contract that adds crvUSD one-sided when the counter-stable is heavy and
removes it when crvUSD is heavy, one fifth of the imbalance per profitable call. `CRVUSD_PEGKEEPER_DD.md`
read the code and the event history: the USDT PegKeeper owns 77.6% of that pool's LP, held exactly
25% of its ceiling for six weeks (the Regulator's cooperative cap), rose to 73M in the Aug 19–24 crvUSD
demand burst, and pulled 26.7M crvUSD out on Aug 26–28 — a ~15 pp rise in the USDT share with no USDT
moving. The share reading in a PegKeeper pool is therefore the PegKeeper's, not the market's, whenever
the PegKeeper may act.

**Rule (pre-registered before any stored reading).** The Regulator refuses to provide crvUSD into a
pool whose crvUSD oracle price exceeds every other PegKeeper pool's by more than `worst_price_threshold`
= 3 bp — the signature of that pool's counter-coin being sold — and whenever the aggregate crvUSD price
is below $1. A5 stores that state per PegKeeper (`curve_pegkeeper_state`, V33: debt, ceiling, idle
crvUSD, LP share, oracle price, gap to the highest other pool, provide/withdraw allowance, aggregate
price) and alerts on the gap for tracked counter-coins in pools ≥ $10M:

| Level | gap_bp (this pool − max of the other PegKeeper pools) |
|---|---|
| 1 watch | ≥ **+3 bp** with the pool counter-heavy (share > 50%) — the Regulator's own block condition, live |
| 2 de-risk | ≥ **+30 bp** |
| 3 act | ≥ **+100 bp** |

Same ladder as the composition impact thresholds. The report also states, per run, whether PegKeepers
may currently provide (counter-coin inflows into these pools damped) or only withdraw (reading free).
Readings today: gaps −8 to −12 bp on the tracked pools, aggregate 0.99989 → withdraw-only mode; level 0.

**Limits stated in advance.** The gap is measured against a set that currently includes the
decommissioned GHO/crvUSD pool (ceiling 0, $1.3M), which sets the "max of others" today — a
false-negative risk if it drifts rich, a false-positive risk if it drifts cheap; it is reported, not
filtered, so that the number equals what the Regulator sees. The 3 bp threshold is Curve's, chosen for
gating not alerting; whether it leads composition is not measured — the table exists so it can be.
