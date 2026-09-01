# USDC — issuer powers walkthrough (the collateral under everything)

**Date:** 2026-09-01 16:52 UTC · **Author:** Claude
**Why:** USDC is the collateral under the whole stack we trade — Variational settlement pools, the
Hyperliquid bridge's ~$329M, Binance tokenized-perp margin, the prop account. "Is my money really
mine?" answered in code rather than in marketing. Fourth in the contract-reading series
(`VARIATIONAL_CONTRACT_DD.md`, `HYPERLIQUID_BRIDGE_DD.md`, `HYPERLIQUID_HLP_DD.md`,
`ETHENA_USDE_DD.md`).
**Contracts (Ethereum mainnet, verified):** proxy `FiatTokenProxy`
`0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48` → implementation `FiatTokenV2_2`
`0x43506849D7C04F9138D1A2050bbF3A0c054402dd` (Solidity 0.6.12).

## Live state (2026-09-01, block 25,883,739)

| Role | Address | Power |
|---|---|---|
| owner | `0xfcb19e6a…ae3a` | appoints every other role |
| blacklister | `0x0a06be16…78f9` | freeze any address, permanently |
| pauser | `0x4914f61d…8566` | halt ALL transfers globally |
| masterMinter | `0xe982615d…de17` | grant/revoke minting allowances |
| **proxy admin** | `0x807a9628…95d2` | **replace the entire implementation** |

Total supply **$50.60B** · `paused = false`. Roles are held by four distinct addresses —
genuine separation of duties, which is good practice and worth crediting.

## What the code actually permits

**1. Blacklist = total, permanent freeze.** Every value-moving function carries
`notBlacklisted`: `transfer`, `transferFrom`, `approve`, `mint`, `burn`. A single call —
`blacklist(address)`, `onlyBlacklister` — and that address can never move its USDC again. There is
no timelock, no appeal path, no user-side recovery. It is not a transfer restriction; it is a
permanent immobilization until (and unless) Circle calls `unBlacklist`.

**2. Global pause.** `pause()`, `onlyPauser`, sets `whenNotPaused` false across transfer,
transferFrom, approve, mint and burn. **One key can freeze $50.6B of token movement** for everyone
simultaneously.

**3. Upgradeable proxy = the real power.** The admin can swap `FiatTokenV2_2` for arbitrary new
logic. Current code has **no seize function** — Circle can freeze but cannot directly take your
balance. That distinction is only as durable as the implementation: an upgrade could add seizure,
rewrite balances, or change any rule, with no user consent and no on-chain notice period. Every
guarantee below the proxy is provisional.

**4. Minting is allowance-gated.** `configureMinter` (masterMinter) grants each minter a capped
allowance decremented per `mint` — a sane containment design so one compromised minter cannot print
unbounded supply.

## Is the freeze power actually used? Yes, routinely

Empirical check via the event log — **23 `Blacklisted` events in ~11 days** (blocks 25,800,000 →
25,883,743), several in single consecutive-block bursts (one enforcement action freezing a cluster
of addresses):

```
0x4060cbf8…5279  block 25826668
0x56de1527…2ba7  block 25826677
0x6fac4d18…b9c0  block 25826684   ← consecutive blocks = one sweep
```

Separately, two well-known Tornado Cash addresses blacklisted in Aug-2022 now return
`isBlacklisted = false` — consistent with Circle reversing after OFAC delisted Tornado in 2025.
So the list tracks sanctions policy in **both** directions: it is a live compliance instrument,
not a dormant emergency lever.

## What this means for our stack (the part that matters)

- **Every USDC balance we touch is freezable by one Circle key.** Prop account collateral, XVF
  legs, Variational deposits, HL bridge holdings — all the same underlying asset, all subject to
  the same power.
- **Contract addresses can be blacklisted too**, and that is the systemic tail: blacklisting a
  *venue's* address (e.g. a bridge or settlement contract) would immobilize **everyone's** funds
  inside it at once, with no on-chain remedy. Concentration then bites twice — the HL bridge's
  ~$329M pooled in one address is efficient for the venue and a single point of policy failure for
  its depositors. Nothing suggests this is likely; it is the honest worst case, and it is not
  hedged by any of the venue-level protections we read (quorums, dispute windows, per-user pools —
  none of them survive the collateral itself being frozen).
- **Practical mitigation is diversification of settlement asset and venue, not clever contracts.**
  No amount of venue-side decentralization repairs a frozen base asset.

## Where USDC sits vs the others read in this series

| | token freeze? | global pause? | upgradeable? | backing |
|---|---|---|---|---|
| **USDC** | **yes, permanent** | **yes** | **yes (proxy)** | off-chain bank reserves |
| USDe (Ethena) | no | no (mint/redeem gate only) | no (immutable token) | off-chain CEX delta-neutral trade |
| HL bridge | n/a | yes (locker quorum) | no | pooled USDC on-chain |
| Variational pool | n/a | no | no (clone) | pooled USDC on-chain |

The instructive inversion: **USDe's token is far more censorship-resistant than USDC's**, yet its
backing is far more fragile (a live CEX trade vs bank reserves). Trust-minimization at the token
layer and robustness of backing are independent axes — a stablecoin can be strong on one and weak
on the other, and both USDC and USDe are exactly that, in opposite directions.

## Minor curiosity

The verified source's file paths leak a Circle developer's local checkout —
`/Users/aloysius.chan/Repositories/circlefin/stablecoin-evm-private-eurc-mainnet-eth/…` — showing
the USDC deployment was built from a private EURC repo. Harmless, mildly amusing, and a reminder
that verified source carries build-environment metadata.

## Sources

- Verified source via Blockscout Ethereum `api/v2/smart-contracts/<addr>`, 2026-09-01
- Live roles/state via `eth_call` / `eth_getStorageAt` (zeppelin-os proxy admin slot
  `0x10d6a54a…390b`) on `ethereum-rpc.publicnode.com`
- Blacklist events via Blockscout `api?module=logs&action=getLogs`, topic0
  `0xffa4e618…b855` (`Blacklisted(address)`)
