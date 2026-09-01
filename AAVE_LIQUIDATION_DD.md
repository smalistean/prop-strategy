# Aave V3 — liquidation engine walkthrough (the cascade mechanism, in code)

**Date:** 2026-09-01 17:15 UTC · **Author:** Claude
**Why:** forced liquidation is the flow this repo keeps circling — the stop-run/OI-flush studies,
the Oct-2025 cascade, and the mechanism HLP *profits* from as Hyperliquid's liquidator
(`HYPERLIQUID_HLP_DD.md`). Aave is where that machinery is fully readable, unlike any CEX.
Fifth in the contract-reading series.
**Contracts (Ethereum mainnet, verified):** Pool proxy `0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2`
→ `PoolInstance` `0x728a138A4823392C2EFA55e028d434F526fE03CF` (Solidity 0.8.27); logic in
`LiquidationLogic.sol` (679 loc), `GenericLogic.sol`, `ValidationLogic.sol`.

## The trigger: one number, computed from oracle prices

```
healthFactor = Σ(collateral_i × price_i × liquidationThreshold_i) / Σ(debt_j × price_j)
```
`HEALTH_FACTOR_LIQUIDATION_THRESHOLD = 1e18` — **HF < 1 and anyone may liquidate you.**
Every price in that formula comes from `IPriceOracleGetter(oracle).getAssetPrice()` (Chainlink).
The entire liquidation system is a function of an oracle read: the borrower's position, the
liquidator's profit, and the cascade's timing are all downstream of a feed. That is the system's
single most important dependency and its classic attack surface (oracle manipulation / staleness
is the root of most large lending-protocol exploits).

## Live risk parameters (decoded from the Pool, 2026-09-01)

| Asset | LTV | Liquidation threshold | Liquidation bonus | Liquidator premium |
|---|---|---|---|---|
| WETH | 80.5% | 83.0% | 105.0% | **5.0%** |
| WBTC | 73.0% | 78.0% | 105.0% | **5.0%** |
| USDC | 75.0% | 78.0% | 104.5% | **4.5%** |

The gap between LTV (max borrow) and liquidation threshold is the borrower's buffer — ~2.5pp on
WETH. Thin by design.

## How much gets seized: the close factor

From `LiquidationLogic`:
- `DEFAULT_LIQUIDATION_CLOSE_FACTOR = 50%` — normally a liquidator may repay at most **half** the
  borrower's debt in one call.
- `CLOSE_FACTOR_HF_THRESHOLD = 0.95e18` — **but if HF ≤ 0.95, 100% of the debt is liquidatable.**
- Full liquidation also applies to dust (`MIN_BASE_MAX_CLOSE_FACTOR_THRESHOLD = $2,000` of
  collateral or debt), and a partial liquidation must leave ≥ `MIN_LEFTOVER_BASE` ($1,000) —
  preventing unclosable dust positions that would become bad debt.

**This is the accelerant.** A position at HF 0.99 loses half its debt; a position at HF 0.94 can be
fully closed in a single transaction. The rule exists to stop bad debt accruing — but it means the
largest forced sales fire exactly when prices are worst.

## The liquidator's economics

`_calculateAvailableCollateralToLiquidate`: the liquidator repays debt worth X and receives
collateral worth `X × liquidationBonus` (105% for WETH). The 5% premium is **paid by the
borrower**, out of their collateral, and it is the entire economic engine of the liquidation-bot
race. The protocol skims a configurable `liquidationProtocolFee` off that bonus — Aave takes a cut
of the pain. Aave V3.3 additionally tracks and burns residual **bad debt** (`_burnBadDebt`,
`executeEliminateDeficit`) when a liquidation cannot cover the position.

## Why cascades happen (the reflexive loop, now explicit)

1. Price falls → oracle updates → many positions cross HF < 1 **simultaneously** (they share the
   same price inputs).
2. Bots liquidate, receiving collateral at a 5% discount, and typically **sell it immediately** to
   lock the spread.
3. That selling pushes the price further down → more positions cross the threshold → step 1.
4. Below HF 0.95 the close factor jumps to 100%, so the size of forced selling *increases* as the
   move deepens.

That is the on-chain twin of the CEX liquidation cascade the repo has studied and the flow HLP
harvested for +$41M in Oct-2025. Same shape everywhere: a price threshold triggers forced selling,
and whoever absorbs it is paid a premium.

## The difference that matters for us: on-chain liquidation levels are PUBLIC

On a CEX, liquidation prices are private to the venue — the "liquidation heatmaps" sold as data
products are *estimates* inferred from open interest. On Aave, every account's collateral, debt,
and health factor are readable on-chain, so **the exact liquidation level of every position is
computable**, and so is the aggregate: how much collateral sits within X% of its liquidation price.
That is a genuine, non-inferred leading indicator with no CEX equivalent.

### New idea logged — I92: on-chain liquidation-overhang indicator
**Mechanism:** aggregate health factors across Aave (and comparable lenders) into a distribution of
"collateral value that liquidates if price drops X%". Unlike CEX heatmaps this is exact, not
inferred.
**Hypothesis:** periods of high liquidation overhang precede larger/faster drawdowns and sharper
cascade dynamics, on-chain first and possibly spilling into CEX perps (the venues share the same
marginal traders and assets).
**Test:** requires an indexer/archive node or a subgraph pull to snapshot user positions over time —
**not currently in our data stack**, so this is a Tier-B pull, not a Tier-A SQL pass. Pre-register
the overhang definition (asset set, price-shock grid, aggregation) before looking at outcomes.
**Caveat:** DeFi lending is a modest slice of total crypto leverage; the spillover leg is the
speculative half of the hypothesis and must be tested separately from the on-chain-only leg.

## Contract-series comparison: who is the forced counterparty?

| System | Who absorbs the liquidation | Their compensation |
|---|---|---|
| Aave | any bot, permissionlessly | 4.5–5% collateral bonus |
| Hyperliquid | HLP vault (+ ADL as backstop) | the liquidated position's edge |
| CEX perps | venue insurance fund, then ADL | fund fees / socialized losses |

Aave is the only one where the role is open to anyone and the compensation is a published constant.
That transparency is what makes it the right place to *learn* the mechanism — and, per I92,
potentially to measure it.

## Sources

- Verified source via Blockscout Ethereum `api/v2/smart-contracts/<addr>`, 2026-09-01
- Live risk parameters decoded from `Pool.getConfiguration(asset)` bitmap (LTV bits 0–15,
  liquidation threshold 16–31, bonus 32–47) via `ethereum-rpc.publicnode.com`
- Companion notes: `HYPERLIQUID_HLP_DD.md`, `USDC_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`,
  `HYPERLIQUID_BRIDGE_DD.md`, `VARIATIONAL_CONTRACT_DD.md`
