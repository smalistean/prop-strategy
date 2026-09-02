# GMX — the pool as counterparty (the HLP comparison, in readable Solidity)

**Date:** 2026-09-02 04:10 UTC · **Author:** Claude
**Why:** `HYPERLIQUID_HLP_DD.md` measured HLP's returns from an API because HLP is HyperCore-native
with no source to read. GMX runs the same economic role — a pool that is the counterparty to every
trader — **in Solidity**, so the mechanism can be read rather than inferred. Eleventh in the
contract-reading series and the closing entry on the "who is the passive counterparty" thread.
**Contracts (Arbitrum, verified):** V1 `Vault` `0x489ee077994B6658eAfA855C308275EAd8097C4A`
(1,995 loc — the readable mechanism); V2 `DataStore` `0xFD70de6b91282D8017aA4E741e9Ae325CAb992d8`
and the ETH/USD GM market `0x70d95587d40A2caf56bd97485aB3Eec10Bee6336` (the live product).

## Status first: V1 is retired, V2 is live

Read live from the V1 Vault: **`isLeverageEnabled = 0`** — leverage trading is switched off — and
`poolAmounts[WETH]` is down to **6.58 WETH** (~$16k) against `guaranteedUsd` of $4,327. V1 is wound
down. It remains the best *teaching* read because the whole model sits in one contract; V2 splits
the same logic across a modular DataStore/Handler architecture.

Live V2 size, for scale (token balances held by the ETH/USD GM market):

| | |
|---|---|
| GMX V2 ETH/USD pool | **12,535 WETH (~$30.3M) + $30.5M USDC ≈ $60.9M** |
| Hyperliquid HLP (2026-09-01) | **$188.6M** |

## The mechanism: LPs are mechanically short trader PnL

`_reduceCollateral` is the whole thesis in ten lines:

```solidity
// trader in profit -> paid OUT of the pool
if (hasProfit && adjustedDelta > 0) {
    ...
    _decreasePoolAmount(_collateralToken, tokenAmount);
}
// trader at a loss -> loss goes INTO the pool
if (!hasProfit && adjustedDelta > 0) {
    position.collateral = position.collateral.sub(adjustedDelta);
    ...
    _increasePoolAmount(_collateralToken, tokenAmount);
}
```

There is no matching engine and no other trader on the far side. **Every position's profit is a
withdrawal from the LP pool and every loss is a deposit into it.** GLP/GM holders are the house by
construction, not by choice — they cannot decline a trade, quote a wider spread, or hedge inventory.

`liquidatePosition` completes it: the liquidated position's remaining collateral is credited to the
pool via `_increasePoolAmount`, while a **fixed `liquidationFeeUsd` = $5** is paid out of the pool
to whoever called the liquidation.

## The contrast with HLP that actually matters

Both are "the passive side", but they are not the same job:

| | GMX GLP/GM | Hyperliquid HLP |
|---|---|---|
| Role | **passive counterparty** to every position | **active market maker + liquidator** |
| Can it manage inventory? | **no** — automatic other side | yes, it quotes and skews |
| Liquidation revenue | keeps residual collateral, **pays $5 fee out** | **performs** the liquidation, keeps the edge |
| Crash behaviour | depends entirely on trader positioning | **profits** (+$41M in Oct-2025, ~30% of all-time PnL) |
| Measured yield | fees + borrowing fees − net trader PnL | ~6.7% APR, 12:1 gross gain/loss |

The measured HLP result — *net long crashes* — is a property of being the **liquidator**. GMX's
pool has no such edge: it is long or short the market purely as the mirror of trader positioning.
If traders are net long into a rally, GM holders simply pay. Their compensation is fees plus
borrowing fees, not a structural crash edge.

That distinction is the sharpest thing this series produced about passive-side economics: **being
the counterparty and being the liquidator are different businesses**, and only the second one gets
paid for chaos.

## Two design notes worth keeping

- **V1 filled at the oracle price with zero price impact.** That is what made it elegant (no
  slippage) and what made it exploitable — a trader who can move the reference price extracts
  directly from the pool. V2 introduced price impact for exactly this reason. It is the same
  lesson as `CHAINLINK_ORACLE_DD.md` from the other side: a venue that trades *at* an oracle is
  only as sound as that oracle.
- **`maxLeverage = 100x` with a flat $5 liquidation fee.** The fee does not scale with position
  size, so liquidating a large position is enormously profitable per unit of gas and liquidating a
  small one may not cover costs — a structural bias toward large positions being liquidated
  promptly and dust being left to rot.

## Closing the series through-line

Eleven contracts, one question — *who cannot refuse the flow, and what are they paid?*

| Venue | Passive side | Compensation |
|---|---|---|
| Uniswap V3 | LPs | 0.05% fee (minus adverse selection) |
| Curve 3pool | LPs | **0% of swap fees today** (admin_fee at max) |
| Aave | liquidator bots | 4.5–5% collateral bonus |
| **GMX** | **GM/GLP holders** | **fees + borrowing fees − trader PnL** |
| Hyperliquid | HLP | liquidation edge + fees (~6.7% APR) |
| CEX perps | insurance fund → ADL | fees, then socialised |
| Tokenized perp weekend | **us** | **~147 bp/weekend** |

The ranking that falls out: the passive seat pays best where the counterparty most needs an exit
and fewest others will provide it. GMX pays its pool a spread for standing still; HLP pays itself
for doing the liquidating; the weekend fade pays us for supplying an anchor that literally nobody
else can supply until Monday.

## Sources

- Verified source via Blockscout Arbitrum for V1 `Vault` and V2 `DataStore`, 2026-09-02
- Live state via `eth_call` on `arb1.arbitrum.io/rpc` (`isLeverageEnabled`, `poolAmounts`,
  `guaranteedUsd`, `globalShortSizes`, `liquidationFeeUsd`, `maxLeverage`; ERC20 `balanceOf` for
  the V2 GM market). Selectors computed with the locally implemented, self-tested Keccak-256.
- Series companions: `HYPERLIQUID_HLP_DD.md`, `CHAINLINK_ORACLE_DD.md`, `AAVE_LIQUIDATION_DD.md`,
  `CURVE_STABLESWAP_DD.md`, `UNISWAP_V3_DD.md`, `USDT_ISSUER_POWERS_DD.md`,
  `USDC_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`, `HYPERLIQUID_BRIDGE_DD.md`,
  `VARIATIONAL_CONTRACT_DD.md`
