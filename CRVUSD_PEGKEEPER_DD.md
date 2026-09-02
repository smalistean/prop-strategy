# crvUSD PegKeepers — the contract that rebalances two of the pools we watch

**Date:** 2026-09-02 14:17 UTC · **Author:** Claude
**Why:** the Curve composition monitor admits USDC/crvUSD ($13.7M) and USDT/crvUSD ($54M). Both have
a PegKeeper: a Curve-owned contract that mints crvUSD into the pool when crvUSD is rich and burns it
when crvUSD is cheap. If it rebalances the pool, it rebalances *our reading*. Thirteenth in the
series; second driven by the tooling.
**Contracts (Ethereum, verified Vyper 0.3.10):** `PegKeeperRegulator`
`0x36a04CAffc681fa179558B2Aaba30395CDdd855f`; five `PegKeeperV2` (USDC `0x9201da0D…`, USDT
`0xFb726F57…`, PYUSD `0x3fA20eAa…`, frxUSD `0x338cb2d8…`, GHO `0x53876b15…`); `AggregatorStablePrice`
`0x18672b1b0c623a30089A280Ed9256379fb0E4E62`; crvUSD `ControllerFactory`
`0xC9332fdCB1C491Dcc683bAe86Fe3cb70360738BC` (debt ceilings).

## The answer first

**Yes, the PegKeeper drives the composition of those two pools, and it owns 77.6% of the USDT one.**
In normal conditions it pulls the pool back toward 50/50, one fifth of the imbalance per call, so a
slow drift never shows in our share reading. **But it is forbidden to absorb a depegging
counter-coin**: the Regulator refuses to provide crvUSD into any pool whose crvUSD price sits more
than **3 bp** above every other PegKeeper pool — exactly the signature of *that pool's* stablecoin
being sold — and refuses whenever the aggregate crvUSD price is below $1. In the stress regime the
monitor cares about, the damping switches off and the pool behaves like any other. The Regulator's
own test is therefore the earlier signal, and it is now measured and stored (amendment A5).

## 1. Mechanism, from `PegKeeperV2.update()`

Permissionless. Each call reads the pool balances (`balance_peg` = the counter-stable, scaled;
`balance_pegged` = crvUSD) and does one of two things:

- **counter-stable > crvUSD** (crvUSD rich): `_provide(min(imbalance/5, regulator.provide_allowed()))`
  — one-sided `add_liquidity` of crvUSD the PegKeeper already holds (pre-minted up to its ceiling),
  `debt += amount`. Comment in the source: *"this dumps stablecoin"*.
- **crvUSD > counter-stable** (crvUSD cheap): `_withdraw(min(imbalance/5, regulator.withdraw_allowed()))`
  — `remove_liquidity_imbalance` taking out crvUSD only, `debt -= amount`. *"this pumps stablecoin"*.

Then `assert new_profit > initial_profit, "peg unprofitable"`: profit is LP value minus debt, so the
PegKeeper acts only when one-sided liquidity earns the imbalance bonus. The caller receives
`caller_share` of the LP gained (20% on USDC/USDT, 50% on the others); the rest stays as protocol
profit. A call that is not profitable reverts, so nobody calls it — which is why `update` is a
*damped* corrector, not a hard peg.

## 2. The Regulator: three gates on `provide`, one on `withdraw`

```
provide_allowed(pk):
  0 if killed
  0 if aggregator.price() < 1                                  # crvUSD must be rich overall
  0 if this pool's oracle price > max(other PK pools) + worst_price_threshold   # contrary-coin depeg
  else  max_ratio(others' debt ratios) * (debt + idle) - debt   # cooperative debt cap
withdraw_allowed(pk):
  0 if killed;  0 if aggregator.price() > 1;  else unlimited
```

Live parameters: `worst_price_threshold = 3 bp`; `alpha = 0.5`, `beta = 0.25`;
`price_deviation = 1.0` — the spam-attack range check `|get_p − price_oracle| < deviation` is set so
wide it is always true, i.e. **switched off** by the admin. `is_killed = 0`.

**The cooperative cap** `max_ratio = (alpha + beta · Σ√r_i)²` over the *other* PegKeepers' debt
ratios: alone, a PegKeeper may hold at most `0.5² = 25%` of its ceiling; it may go higher only when
the others are in debt too, i.e. when crvUSD demand is broad rather than one pool's counter-coin
being dumped. The history in §4 shows this cap binding to the dollar.

**The aggregate price** is not a median: each of five pools is weighted by its LP supply (EMA,
50,000 s) times `exp(−(p − p_avg)² / σ²)` with `σ = 10 bp`. A pool whose price sits far from the
average — the one whose counter-coin is depegging — gets weight ≈ 0. Pairs: USDC/crvUSD,
USDT/crvUSD, PYUSD/crvUSD, frxUSD/crvUSD, GHO/crvUSD; USDT/crvUSD is 63% of the weight today.

## 3. State on 2026-09-02 14:17 UTC

| PegKeeper pool | TVL | counter share | debt | ceiling | idle crvUSD | PK's LP share | caller share | last action |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| USDC/crvUSD | $13.7M | USDC 34.6% | 0 | 135M | 135.0M | 0% | 20% | 2026-08-25 13:53 UTC |
| **USDT/crvUSD** | $54.4M | USDT 48.6% | **42,163,143** | 135M | 92.8M | **77.6%** | 20% | 2026-08-30 23:54 UTC |
| PYUSD/crvUSD | $1.1M | 34.3% | 0 | 45M | 45.0M | 0% | 50% | 2026-08-25 |
| frxUSD/crvUSD | $14.8M | 42.2% | 0 | 9M | 9.0M | 0% | 50% | 2026-08-25 |
| GHO/crvUSD | $1.3M | 73.8% | 0 | **0** (decommissioned) | 0 | 0% | 50% | 2026-05-17 |

Aggregate crvUSD price **0.99989 < 1** → `provide_allowed = 0` for every PegKeeper, `withdraw_allowed`
unlimited: the system is in unwind mode. Relative gaps (pool's crvUSD oracle price minus the highest
of the others): USDC −11.7 bp, USDT −8.2, PYUSD −11.8, frxUSD −9.6; GHO +8.2 bp (blocked, moot at
ceiling 0). Note the fragility: the "max of others" that every pool is measured against is currently
set by the decommissioned $1.3M GHO pool.

## 4. History of the USDT PegKeeper (Provide/Withdraw events, last ~62 days)

| Date (UTC) | provided | withdrawn | debt at end of day |
|---|---:|---:|---:|
| 2026-07-04 → 07-10 | 33,750,000 | 0 | **33,750,000** |
| … six weeks flat … | | | 33,750,000 |
| 2026-08-19 | 8,535,678 | 5,118,542 | 37,167,136 |
| 2026-08-20 | 4,837,313 | 0 | 42,004,449 |
| 2026-08-21 | 16,128,486 | 0 | 58,132,935 |
| 2026-08-22 | 3,978,032 | 0 | 62,110,967 |
| 2026-08-24 | 11,308,247 | 0 | 73,419,215 |
| 2026-08-25 | 7,732,657 | 10,725,284 | 70,426,587 |
| 2026-08-26 → 08-28 | 0 | 26,721,938 | 43,704,648 |
| 2026-08-30 → 08-31 | 0 | 1,541,505 | **42,163,143** |

**33,750,000 is exactly 0.25 × 135,000,000**: with every other PegKeeper at zero debt, the cap held
the USDT PegKeeper at 25% of its ceiling for six weeks to the dollar. It could only pass that on
Aug 19–24 because the USDC PegKeeper was also building debt (0 → 37.2M on Aug 19–21, fully unwound
on Aug 25) — a broad crvUSD-demand burst, not a USDT event. Then both unwound: the USDC PegKeeper to
zero, the USDT one from 73.4M to 42.2M in a week.

What that did to *our* pool: on Aug 26–28 the PegKeeper pulled 26.7M crvUSD out of USDT/crvUSD. A
composition monitor running then would have read the USDT share rising ~15 pp in three days —
"USDT inflow" — with not one dollar of USDT having moved. The monitor's first reading came four days
later at 48.6% USDT and looked calm. Both readings are the PegKeeper, not the market.

## 5. What this means for the monitor (amendment A5)

- For a PegKeeper pool, the share reading is **damped while `provide_allowed > 0`** (a counter-coin
  inflow is met with fresh crvUSD) and **free while it is 0**. The report now states which.
- The Regulator's relative-gap test is a maintained, on-chain, pre-parameterised "this pool's
  stablecoin is being sold" detector. A5 stores it per pool and uses Curve's own 3 bp as level 1
  (with the pool counter-heavy and above the $10M gate), 30 bp level 2, 100 bp level 3 — the same
  ladder as the composition impact thresholds.
- PegKeeper debt and idle crvUSD are stored so the next unwind is recognisable as one.

## 6. Series through-line

The twelve earlier reads asked who the passive counterparty is. Here the passive counterparty to a
crvUSD imbalance is *the protocol itself*, paid in LP bonus, with a rulebook that tells it when to
stop being passive: never absorb a coin that only its own pool says is cheap. That rule is the most
useful single line of code in the series for the I53 dossier, because it is a depeg detector that
someone else maintains, with $135M of skin in it.

## Not verified here

PYUSD and frxUSD PegKeeper histories; the aggregator's frxUSD/crvUSD entry `0x3f28e80f…` reports
the same coins and balances as the PegKeeper's pool `0x13e12BB0…` (one pool behind two addresses,
mechanism unresolved); Curve's docs page for PegKeepers 404s and the governance forum blocks
fetches, so the rationale is taken from the code and the 2023 Curve newsletter only; the
ChainSecurity audit was not read.

## Sources

Verified sources via Blockscout (addresses above); event logs via drpc; Curve newsletter
"June 16, 2023: The Peg Keepers" https://curve.substack.com/p/june-16-2023-the-peg-keepers.
