# Legacy FRAX and frxUSD — a stablecoin with no redemption path, read from the contracts

**Date:** 2026-09-02 13:35 UTC · **Author:** Claude
**Why:** every Curve composition reading so far raised LEVEL 1 from the same place — FRAX/USDe at
77/23 and FRAX/USDC at 90/10, FRAX priced 0.9914 by the API. Before the monitor keeps flagging it,
find out what FRAX is now. Twelfth in the contract-reading series; the first driven by a signal the
tooling produced rather than by curiosity.
**Contracts (Ethereum, verified via Blockscout):** legacy FRAX `FRAXStablecoin`
`0x853d955aCEf822Db058eb8505911ED77F175b99e` (2020, non-upgradeable); frxUSD proxy
`0xCAcd6fd266aF91b8AeD52aCCc382b4e165586E29` → `FrxUSD` v3.0.0
`0x0000000048d2c8baf31742f6765383278bada4d5`; six `FrxUSDCustodian*` minters; Fraxtal L1 bridge
`0x34C0bD5877A5Ee7099D0f5688D65F4bB9158BDE2`; Fraxtal frxUSD predeploy
`0xFc00000000000000000000000000000000000001` (read on Fraxtal).

## The answer first

**Legacy FRAX cannot be redeemed by anyone, the promised 1:1 upgrade to frxUSD is over, and the only
exit is the secondary market.** Its −87 to −91 bp price is therefore a *structural* discount — the
market's price for a token with no par path and a yield vault capped at 1.24%/yr — not redemption
pressure on a stablecoin. The monitor's per-coin aggregate had already attributed the skew to FRAX
rather than USDe; this read says the skew is permanent, so FRAX has to leave the universe
(pre-registration amendment A4).

## 1. Legacy FRAX: what is left of the mechanism

Read live from the token:

| | |
|---|---|
| totalSupply | **219,320,046** |
| global_collateral_ratio | 94.5%, `collateral_ratio_paused = 1`, last refresh 2023-06-19 |
| minting_fee / redemption_fee | 0.95% / 0.45% (inert — nothing calls them) |
| `frax_price()` / `fxs_price()` | **revert** (the old oracle path is dead) |
| globalCollateralValue | $518,738,287 — see below |
| owner / DEFAULT_ADMIN_ROLE | GnosisSafe `0xb1748c79…`; timelock `0x8412ebf4…` |

Only addresses in `frax_pools_array` can `pool_mint` / `pool_burn_from`. Six of 25 slots are live:

| Registered pool | collatDollarBalance | Redeemable by a holder? |
|---|---:|---|
| `Pool_USDC` (v1) | $500 | `mintPaused = redeemPaused = 1` |
| `FraxPoolV3` | $100 | **no collateral registered** (`collateral_information(0)` empty, `freeCollatBalance = 0`) |
| `ConvexAMO_V1_Recoverer` | $0 | AMO, not a redemption venue |
| `UniV3LiquidityAMO` | $0 | AMO |
| `RariFuseLendingAMO` | $17,426,205 | AMO on Rari Fuse, defunct since the 2022 exploit — a stale book value, not verified |
| `FraxAMOMinter` | $501,311,482 | the AMO ledger; its `collatDollarBalance` is the sum of the AMOs' own valuations |

So the "94.5% collateral ratio" is AMO self-accounting: the contracts that report $518M of collateral
are the protocol's own market-operation ledgers, and none of them has a `redeem` a holder can call.
There is no freeze or blacklist in the 2020 token; the one live power is the multisig's ability to
register a new pool, i.e. to mint.

**Where the 219M sits** (top holders, Blockscout):

| Holder | Legacy FRAX |
|---|---:|
| Fraxtal L1 bridge escrow | **99,525,769** (92,923,303 recorded under the pair FRAX → Fraxtal frxUSD; 6.6M under a pair I did not identify) |
| sFRAX vault (`0xA663B02C…`) | 65,471,813 |
| Curve FRAX/USDe | 26,379,013 |
| FXB bond contract | 6,046,781 |
| Curve FRAX/frxUSD | 3,868,340 |
| two Uniswap V3 pools | 3,139,476 |
| Curve FRAX/USDC | 1,697,772 |
| Fraxferry, Fraxswap, FPI, others | < 1M each |

**sFRAX** is the one place legacy FRAX still earns anything: 65.18M assets over 56.16M shares
(1.1607 per share, accrued since 2023), weekly cycles, and `maxDistributionPerSecondPerAsset =
3.94e-10` → a **1.24%/yr cap** on what the vault can pay. A 0.9% discount is most of a year of that.

## 2. The upgrade path and how it closed

- **FIP-419 (2024-12-21)** promised Ethereum holders they could "upgrade to frxUSD and sfrxUSD
  1-to-1 at any time through an upgrade contract" and that legacy FRAX would "retain [its] ability
  to upgrade 1-to-1 at any time in the future". No such contract exists among frxUSD's minters (§3).
  What actually carried the migration was the Fraxtal route: the OP-stack hard fork renamed
  Fraxtal's FRAX to frxUSD, so bridging legacy FRAX to Fraxtal yielded frxUSD, which the LayerZero
  adapter (a frxUSD minter) then delivered to Ethereum as the new token. The L1 bridge still holds
  **92.9M legacy FRAX against that pair** — the fossil of the migration.
- **FIP-430 (2025-04-21)**: "The migration from Legacy FRAX Dollar to frxUSD … will come to an
  end"; the DAO "no longer guarantees 1-to-1 migration"; holders may swap on Curve/Balancer/Uniswap.
  Stated reason: payment-stablecoin legislation (GENIUS/STABLE) — an AMO-driven token cannot be a
  charter-eligible stablecoin, so frxUSD is isolated under FinresPBC / Frax Inc. and legacy FRAX
  keeps its AMO model. FXBs were switched back to redeem in legacy FRAX for the same reason.
- **On-chain today:** Fraxtal frxUSD's `remoteToken()` = `0xCAcd…` (the *new* Ethereum frxUSD), not
  legacy FRAX. Under OP-stack bridge rules a deposit only finalizes when the L2 token names the L1
  token as its counterpart, so bridging legacy FRAX no longer produces frxUSD (rule from the
  standard-bridge spec; not re-executed here). The 92.9M escrow is stranded unless governance
  re-maps it.

If a costless 1:1 path existed, the FRAX/frxUSD pool could not sit at 94% FRAX with a 91 bp gap.
It does — that is the market confirming the path is gone.

## 3. frxUSD: the replacement, and who can actually redeem it

`FrxUSD` v3.0.0 behind a TransparentUpgradeableProxy (proxy admin owned by Timelock `0xb898ad29…`).
Token **owner** is GnosisSafe `0xffffff4f…` (3-of-5 per Llama Risk) with, **untimelocked**:
`addMinter/removeMinter`, `freeze/thaw` (+ a freezer list), `pause`, and `burn(address, amount)` —
the owner can burn any holder's balance directly, without blacklisting first. Ethereum supply
97,902,090; Fraxtal 13.2M; other chains via LayerZero.

**Minters (8 live):** six custodian contracts, the `FraxOFTMintableAdapter` (mints whatever LayerZero
delivers; peers set by the owner), and the Timelock itself (governance can mint with no reserve
entering a custodian).

| Custodian (impl) | Reserve token | Held on-chain | mintCap | Fees | Notes |
|---|---|---:|---:|---|---|
| `FrxUSDCustodianUsdc` | USDC | **$18** | 400M | 0 / 0 | cumulative `frxUSDMinted` 255.3M; USDC "shuffled to RWA" |
| `FrxUSDCustodian` | BUIDL (BlackRock) | 15.79M | 100k | 0 / 0.01% | |
| `FrxUSDCustodianWithReceiver` | WTGXX (WisdomTree) | 14.55M | 25M | 0 / 0.01% | |
| `FrxUSDCustodianWithOracle` | USTB (Superstate) | 2.275M tokens ≈ $25.5M at the implied $11.20 | 5M | 0 / 0.01% | |
| `FrxUSDCustodian` | USDB | $2 | 100k | 0 / 0 | |
| `FrxUSDCustodian` | AUSD (Agora) | $0 | 100k | 0 / 0.01% | |

On-chain in these six contracts: **≈ $56M against 97.9M Ethereum frxUSD.** The rest of the claimed
backing (103.7% CR per Llama Risk, July 2025; "over 90% USTB and BUIDL") lives outside these
contracts and is asserted at frax.com/transparency, which I did not read.

**Access.** `deposit`/`redeem` are plain ERC-4626 with no whitelist in the code; the only limits are
`maxDeposit`/`maxRedeem` = min(holder balance, reserve on hand). Tested with a real holder (the
frxUSD/crvUSD pool, 6.25M frxUSD):

| Custodian | maxWithdraw for that holder |
|---|---:|
| USDC | **18 USDC** |
| BUIDL | 6,245,601 BUIDL |
| USTB | 557,688 USTB |
| WTGXX | 6,245,601 WTGXX |

BUIDL, USTB and WTGXX are transfer-restricted to investors allow-listed by Securitize, Superstate and
WisdomTree (documented; their allow-lists were not re-read here), so a non-whitelisted holder's
redeem into them fails at the reserve token. **Par redemption open to the public today: $18.** The
peg arbitrage belongs to whitelisted institutions — the same structure as USDe's whitelisted
minter/redeemer set, with issuer powers closer to USDT's (plus direct owner burn).

## 4. What the pools show, for the record (2026-09-02, near-marginal and $100k probes)

| Pool | Composition | Legacy FRAX buys (marginal) | $100k clip |
|---|---|---:|---:|
| FRAX/frxUSD ($4.1M) | 94.0% FRAX | 0.99095 frxUSD (**−91 bp**) | −147 bp |
| FRAX/USDC ($1.9M) | 90.4% FRAX | 0.99098 USDC (−90 bp) | −194 bp |
| FRAX/USDe ($34M) | 77.0% FRAX | 0.99135 USDe (−87 bp) | −87 bp |
| frxUSD/crvUSD ($14.8M) | 42.2% frxUSD | frxUSD at 1.00006 crvUSD (par) | — |

Everyone is selling legacy FRAX into every pool that will take it, and nobody is buying it back at
par because nobody can turn it into par. That is a one-way composition drift, not the two-sided
flight the composition trigger was designed for.

## 5. Consequence for the monitor (amendment A4)

The admission rule had a price band (0.85–1.03, deliberately loose so a depegging coin stays in
scope) but no test for *whether par exists at all*. FRAX passes the band at 0.9914 and would keep the
overall level at 1 forever. A4 adds the missing criterion — a coin is admissible only with a live par
path (issuer redemption at least for whitelisted parties, or a permissionless mint/redeem module) —
and removes both FRAX pools. Disclosed cost: FRAX/USDe was the only USDe pool above $1M, so **USDe
composition coverage on Curve Ethereum is now nil**; USDe rests on the sUSDe NAV metric (A3) and the
API price band until a USDe pool ≥ $1M appears.

## 6. Series through-line

Eleven contracts asked "who is the passive counterparty and what are they paid". This one asks the
question underneath every stablecoin note: **who can turn the token into a dollar, and is that
window open?** USDC: Circle account holders. USDT: Tether's verified customers. USDe: whitelisted
minters, after cooldown. frxUSD: whitelisted RWA investors, plus a public USDC window holding $18.
Legacy FRAX: **no one** — which is what a 0.991 price means when the band says "still a dollar".

## Not verified here

The BUIDL/USTB/WTGXX allow-lists; the RariFuse AMO's $17.4M; reserves held outside the six custodian
contracts; the L2 token behind the other 6.6M in the bridge escrow; the OP-stack pair check by
execution (taken from the spec).

## Sources

On-chain reads via public RPC and Blockscout verified sources (addresses above); Curve API prices.
FIP-419 https://gov.frax.finance/t/fip-419-launch-frax-usd-provide-upgrade-path-to-frax-stablecoin/3543 ·
FIP-430 https://gov.frax.finance/t/fip-430-preparation-for-frxusd-payment-stablecoin-charter-compliance/3698 ·
docs.frax.com/frxusd (contracts, mint-and-redeem) · Llama Risk, "Pegkeeper Onboarding Review: Frax
frxUSD" https://llamarisk.com/research/pegkeeper-onboarding-frxusd · Chaos Labs, "frxUSD Token
Review" https://chaoslabs.xyz/posts/frxusd-token-review.
