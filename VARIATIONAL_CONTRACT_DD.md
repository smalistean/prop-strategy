# Variational Omni — on-chain due diligence (before any farming deposit)

**Date:** 2026-08-31 19:43 UTC · **Author:** Claude
**Purpose:** answer the two open risks in the board #9 (Variational/Omni airdrop-farm) scoping —
custody model and reward mechanics — by reading the deployed contracts, not the marketing.
**Method:** verified source pulled from Blockscout Arbitrum; bytecode confirmed via `eth_getCode`
on `arb1.arbitrum.io/rpc`. Chain: Arbitrum One. USDC-only collateral, off-chain matching.

## Contracts (from official docs, verified on-chain)

| Docs name | Address | On-chain reality |
|---|---|---|
| Settlement Pool Factory | `0x0F820B9afC270d658a9fD7D16B1Bdc45b70f074C` | Verified contract; clones a per-user `SettlementPool` via EIP-1167 minimal proxy |
| Oracle | `0x84BE56470d45b7f6629A66A219a38681F6BA6172` | Verified contract; the privileged off-chain operator's on-chain entry point |
| "Core OLP Vault" | `0x74bbbb0e7f0bad6938509dd4b556a39a4db1f2cd` | **EOA — zero bytecode** (`eth_getCode` → `0x`). A plain wallet, not a vault contract |
| Protocol Treasury | `0x5e91b40467fb8902c46a7b6cb90482363188d645` | Fee sink |

## How deposit / withdraw actually work (`SettlementPool.sol`, 332 loc)

Each account gets a cloned `SettlementPool` holding its USDC. Access modifiers on the
fund-moving functions are the whole story:

- **Deposit — user-callable.** `depositUSDC(amount, transferUuid)` is `onlyParties` (the pool
  creator / added parties). Standard ERC20 `approve` + `transferFrom`. Dup-transfer guard via
  `transfers_processed[transferUuid]`.
- **Withdraw — NOT user-callable.** Every exit path — `withdrawUSDC`, `withdrawUSDCNoEvent`,
  the internal `_withdrawUSDCOnBehalf`, and `withdrawFees` — is **`onlyOracle`**. There is **no
  user-callable withdraw function anywhere in the contract**, and no emergency-exit, timelock,
  or owner-bypass escape hatch (read all 332 lines specifically for one; none exists).
- The Oracle address itself is mutable: `SettlementPoolFactory.setOracleAddress` is `onlyOwner`.

## Two findings that change the farming risk

1. **Semi-custodial, not self-custodial.** Marketing says "non-custodial, funds in your own
   settlement pool." The code says: you can deposit, but funds leave only when Variational's
   Oracle backend sends them. Backend down / censoring / gone ⇒ USDC is stuck in the pool with
   no on-chain recovery. This is the F17 "the venue is a position" risk made concrete: not "the
   operator might misbehave" but "the contract gives you no exit without them."
2. **The counterparty is a wallet.** `IOracleActions.transferFromOLPToPool` shows the OLP is the
   house/liquidity side you trade against and are paid from. On-chain it is an **EOA with no
   code** — no transparent reserves, no on-chain accounting, no auditable risk logic. Winnings
   are paid from a private wallet governed entirely off-chain.

Neither is a bug/exploit. The contract is small and clean (OZ `ReentrancyGuard`, `Clones`,
dup guards). Minor hygiene smells only: a `forge-std/console.sol` debug import left in
production, TODO comments in `depositUSDC`/pool logic, an unused `randNonce`. The point is the
**trust model is centralized**, and the contracts prove it rather than the docs stating it.

## Reward mechanics: answered by absence

There is **no on-chain reward / points / claim contract**. Points are computed entirely
off-chain by Variational's backend from trading activity; the VAR token does not exist yet
(no TGE). So the volume→points→allocation mapping is **not verifiable in code** — the airdrop is
a promise enforced by nothing on-chain. Normal for a pre-TGE farm, but it means EV rests on
trust in the team's future distribution, not on anything readable today.

## Consequence for the pilot (updates board #9 plan)

- **The week-1 priority is a withdrawal round-trip, not the points math.** Deposit small
  ($1k), trade a little, then immediately request a withdrawal and confirm the Oracle honors
  it and funds land back in-wallet — *before* scaling. A successful exit is the load-bearing
  data point; points EV is secondary.
- **Never park more than one week's working capital.** No self-service exit means the standing
  rule (withdraw profits weekly, keep only working capital on-venue) is not optional here — it
  is the only mitigation for a loss mode with no on-chain stop.
- EV still gated by the ~21% "token never launches" prior (own Polymarket-derived estimate) and
  now also by "withdrawals depend on a centralized operator." Size accordingly.

## Sources

- Docs mainnet contracts: https://docs.variational.io/technical-documentation/mainnet-contracts
- Points docs (off-chain, weekly Fri 00:00 UTC, season ends ≤ Q3 2026):
  https://docs.variational.io/omni/rewards/points
- Verified source: Blockscout Arbitrum `api/v2/smart-contracts/<addr>`; bytecode via
  `eth_getCode` on `https://arb1.arbitrum.io/rpc` (2026-08-31).
