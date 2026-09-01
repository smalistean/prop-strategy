# Ethena USDe / sUSDe — contract walkthrough (your XVF trade, tokenized)

**Date:** 2026-09-01 16:05 UTC · **Author:** Claude
**Why:** USDe is a $-stablecoin backed by a **delta-neutral perp funding-carry position** — i.e.
the XVF trade this repo runs, institutionalized at multi-billion scale. Reading it shows how the
trade is productized and, more usefully, **exactly where it can depeg** (feeds I53 stablecoin-depeg
dossier). Companion to the Variational / HL bridge / HLP notes; same method (verified source +
on-chain state), a very different design.
**Contracts (Ethereum mainnet, all verified, Solidity 0.8.19):**
USDe `0x4c9EDD5852cd905f086C759E8383e09bff1E68B3` · EthenaMinting
`0x2CC440b721d2CaFd6D64908D6d8C4aCC57F8Afc3` · sUSDe (StakedUSDeV2)
`0x9D39A5DE30e57443BfF2A8307A4256c8797A3497`.

## The economic model (what the code implements)

- **Mint:** a whitelisted market maker delivers collateral (stETH / WETH / USDT) and receives
  newly minted USDe. The collateral is immediately routed to **custodian wallets**, which post it
  as margin on CEXes and open a **short perp** of the same size → the backing is delta-neutral
  (long spot/LST + short perp).
- **Yield (sUSDe):** the **funding carry** from those short perps + the stETH staking yield, paid
  to stakers. This is XVF's funding capture, at scale.
- So USDe is not "dollars in a bank." It is backed by a live delta-neutral trade held largely
  **off-chain at custodians and CEXes**.

## USDe token (`USDe.sol`, 35 lines — strikingly minimal)

- Plain `ERC20Burnable` + `ERC20Permit`. **No blacklist, no pause, no freeze on the token.** More
  censorship-resistant at the token layer than USDC.
- Entire supply flows through a single `minter` (the EthenaMinting contract); `setMinter` is
  `onlyOwner`; `renounceOwnership` is disabled (there is always an owner who can swap the minter).
- Trust-minimized token, but supply is fully controlled by the issuance layer below.

## EthenaMinting (`EthenaMinting.sol`, 551 lines — where the trust actually sits)

- **Mint/redeem are permissioned:** `mint` is `onlyRole(MINTER_ROLE)`, `redeem` is
  `onlyRole(REDEEMER_ROLE)` — only whitelisted MMs. Retail never mints; it buys USDe on the
  secondary market. Orders are **RFQ**: Ethena's server signs an `Order` (price/amount), the MM
  submits it on-chain; `verifyOrder` checks the EIP-712 signature, `_deduplicateOrder` prevents
  replay, per-block caps (`maxMintPerBlock`/`maxRedeemPerBlock`) rate-limit.
- **Backing leaves the contract to custodians:** on mint, `_transferCollateral` sends collateral
  to `route.addresses`, which must be in `_custodianAddresses`; `transferToCustody`
  (`COLLATERAL_MANAGER_ROLE`) moves assets to custody wallets. The chain hands off to off-exchange
  custody (Copper / Ceffu-type), which mirrors margin to CEXes. **The collateral does not stay
  on-chain and the short-perp leg is not on-chain — neither is verifiable from this contract.**
- **Kill switch:** `disableMintRedeem` (`GATEKEEPER_ROLE`) sets both caps to 0 instantly, halting
  mint AND redeem. `DEFAULT_ADMIN_ROLE` manages supported assets, custodians, and caps.

## sUSDe staking (`StakedUSDeV2.sol` — yield + exit friction + seizure)

- ERC-4626 vault; stake USDe, share price rises as a rewarder vests USDe yield in (a slice is
  routed to an insurance fund).
- **Cooldown gate:** `cooldownDuration` is admin-set, 0–**90 days** max. While it is > 0, the
  standard ERC-4626 `withdraw`/`redeem` are **disabled** — you call `cooldownAssets`/`cooldownShares`
  (moves your assets to a `USDeSilo` and starts a timer) and can only `unstake` after `cooldownEnd`.
  So sUSDe holders **cannot instantly exit** — historically ~7 days, adjustable up to 90 by admin.
- **The staking layer CAN censor even though the token cannot:** base `StakedUSDe` has
  `SOFT_/FULL_RESTRICTED_STAKER_ROLE` (blacklist) and `redistributeLockedAmount` — the admin can
  **burn a fully-restricted address's sUSDe and reassign it**. Compliance powers live at the
  staking layer, not the token.

## The depeg surface (the actual payoff for I53 / XVF)

Reading the code localizes exactly where USDe breaks — and it's the same risk set as your own XVF
book, scaled to billions:

1. **Negative funding** (economic, not in code): the yield is short-perp funding carry. A
   persistent negative-funding regime means the backing *bleeds* — the short leg costs money. This
   is XVF's core dependency; at scale it's mitigated by an insurance fund, not eliminated.
2. **CEX / custodian counterparty** (the off-chain trust): backing sits as CEX margin via
   custodians. A CEX failure or custodian compromise impairs backing you cannot check on-chain.
   Same "on-chain wrapper, off-chain trust" pattern as Variational — at multi-$B scale.
3. **Redemption gating:** only whitelisted MMs redeem at ~$1; the peg holds because they arbitrage
   (mint when >$1, redeem when <$1). If `disableMintRedeem` fires during stress, that arb is off
   and retail can only sell on the secondary market at a discount.
4. **Exit friction:** sUSDe cooldown (up to 90 days) plus MM-gated redemption means in a panic the
   sUSDe→USDe→collateral path is throttled at two layers — reflexive selling pressure with slow
   relief.

## Where USDe sits vs the others we read (the inversion)

| | token freeze? | who issues/redeems | backing location |
|---|---|---|---|
| USDC | **yes** (blacklist + pause) | Circle | on-chain reserves + bank |
| USDe | **no** (token can't freeze) | whitelisted MMs, RFQ | **off-chain** (custodians + CEX perps) |
| sUSDe | n/a | — | seizure powers exist at staking layer |

USDe is the **most trust-minimized token** we've read (no freeze) wrapped around the **most
off-chain-dependent backing** (a live delta-neutral trade on CEXes). The opposite of USDC, which
freezes freely but holds cash reserves.

## Verdict

USDe is the institutional version of XVF, and reading it is the clearest possible statement of what
that trade's risks are: it pays a real funding-carry yield and holds its peg **as long as** funding
stays net-positive-enough, CEX/custodian counterparties stay solvent, and Ethena keeps redemption
open. Elegant and genuinely decentralized where it can be (the token); but the depeg triggers —
negative funding and off-chain counterparty failure — are precisely the risks your own XVF book
carries, which is why running XVF is itself the best intuition for what could unwind USDe.

## Caveats

- Live `cooldownDuration` not re-confirmed here (public RPC call failed mid-run); code cap is 90
  days, historically set to ~7 — verify before relying on an exact number.
- V1 minting contract read; Ethena has iterated (V2/routers) — the model is unchanged but exact
  addresses/roles should be re-checked against `docs.ethena.fi/key-addresses` before any use.

## Sources

- Verified source via Blockscout Ethereum `api/v2/smart-contracts/<addr>`, 2026-09-01
- Ethena docs (key addresses, mechanism); companion notes: `VARIATIONAL_CONTRACT_DD.md`,
  `HYPERLIQUID_BRIDGE_DD.md`, `HYPERLIQUID_HLP_DD.md`
