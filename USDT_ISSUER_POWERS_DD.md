# Tether USDT — issuer powers (the asset our book is actually denominated in)

**Date:** 2026-09-01 18:58 UTC · **Author:** Claude
**Why:** every position we hold is USDT-margined — the prop challenge account, the tokenized-perp
collateral, both XVF legs, the open ONG pair. `USDC_ISSUER_POWERS_DD.md` asked "is my money really
mine" of USDC; this asks it of the coin our capital is actually denominated in. Eighth in the
contract-reading series.
**Contract:** `TetherToken` `0xdAC17F958D2ee523a2206206994597C13D831ec7`, verified, **Solidity
0.4.18 (2017)**, **`proxy_type: None`** — 447 lines, no proxy.

## Live state (2026-09-01)

| Field | Value |
|---|---|
| owner | `0xc6cde7c39eb2f0f0095f41570af89efc2c1ea828` |
| totalSupply | **$88,306,390,736** |
| paused | false |
| deprecated | false |
| basisPointsRate / maximumFee | **0 / 0** (the transfer-fee switch is off) |

## The headline: USDT can DESTROY your balance, not merely freeze it

```solidity
function destroyBlackFunds (address _blackListedUser) public onlyOwner {
    require(isBlackListed[_blackListedUser]);
    uint dirtyFunds = balanceOf(_blackListedUser);
    balances[_blackListedUser] = 0;
    _totalSupply -= dirtyFunds;
    DestroyedBlackFunds(_blackListedUser, dirtyFunds);
}
```

USDC's blacklist immobilises a balance permanently but **cannot delete it** — the current
`FiatTokenV2_2` has no seize function (Circle would have to upgrade the proxy to add one). USDT
ships the capability outright: blacklist an address, then zero its balance and reduce total supply.

**And it is used routinely, at scale.** Empirically (Blockscout log query, non-indexed params
decoded from `data`):

- The all-time `DestroyedBlackFunds` and `AddedBlackList` queries both hit the API's 1,000-event
  page cap — i.e. **≥1,000 of each**.
- In roughly the last 8 weeks alone (block 25,500,000 → latest): **29 destruction events totalling
  $6,605,495**, median $107,461, largest **$1,509,723**.

Two of those 29 are housekeeping rather than enforcement — $1.51M destroyed from the USDT contract's
*own* address and $1.03M from the zero address (tokens sent there by mistake and written off).
The rest are balances at real addresses being deleted.

## The second finding: a transfer fee that can be switched on

```solidity
function setParams(uint newBasisPoints, uint newMaxFee) public onlyOwner {
    require(newBasisPoints < 20);
    require(newMaxFee < 50);
    ...
}
```

Every `transfer`/`transferFrom` computes `fee = value * basisPointsRate / 10000`, capped at
`maximumFee`, and credits it to the owner. It is currently **0**, and the hard caps mean it can
never exceed **19 bp or 50 USDT per transfer** — a genuine, if unusual, transparency guarantee
baked into the code. But the switch exists and one key can flip it. USDC has no equivalent
mechanism at all.

## The third: one key holds everything

Every privileged function is `onlyOwner` — `pause`, `addBlackList`, `removeBlackList`,
`destroyBlackFunds`, `deprecate`, `issue`, `redeem`, `setParams`. Contrast USDC, which splits
owner / blacklister / pauser / masterMinter across **four distinct addresses**, and caps each
minter with a decrementing allowance. USDT's `issue` has no allowance cap: the owner mints
directly.

`deprecate(address)` is the upgrade path — it sets `deprecated = true` and forwards all calls to
`upgradedAddress`. Not a proxy, but the same effect: the rules can be replaced wholesale.

## The fourth: it is not ERC-20 compliant

`function transfer(address to, uint value) public;` — **no return value**, likewise `approve` and
`transferFrom`. This is the famous quirk that breaks naive integrations and is the reason
OpenZeppelin's `SafeERC20` exists. Also present: `onlyPayloadSize` modifiers, a 2017-era defence
against the short-address attack. This contract predates most of the conventions everything else
in this series takes for granted.

## Ranking the collateral we actually touch

| | Freeze | **Destroy** | Global pause | Transfer fee | Roles | Upgrade |
|---|---|---|---|---|---|---|
| **USDT** | yes | **yes, used ≥1,000×** | yes | **switchable (≤19 bp)** | **1 key** | `deprecate` |
| USDC | yes, permanent | no (today) | yes | none | 4 separated | proxy |
| USDe | no | no | no | none | mint/redeem gate | none |

USDT is the **most centrally controlled** asset in this series and the one holding the largest
share of our capital. That is not a prediction of anything: Tether's destructions overwhelmingly
track law-enforcement action against specific addresses, and an ordinary trading account is not the
target population. It is simply the honest description of the instrument.

## What follows for us

- **The venue tail from `USDC_ISSUER_POWERS_DD.md` is sharper here.** Blacklisting a *venue* address
  would immobilise everyone inside it; with USDT the same action can additionally *destroy* the
  balance. No venue-side protection we have read — HL's validator quorum, its dispute window,
  Variational's per-user pools — survives the base asset being zeroed.
- **Exchange balances are a claim, not the token.** Our Binance/Bybit USDT is an exchange liability;
  the on-chain USDT sits in exchange omnibus wallets. That indirection does not remove the risk, it
  relocates it: the exposure is to the venue's wallets being actioned, not our own address.
- **Practical mitigation is unchanged and boring:** diversify settlement asset and venue, keep
  on-venue balances near working size, and treat "the collateral itself" as a real line in the risk
  model rather than an axiom. Nothing in the strategy layer addresses it.

## Sources

- Verified source via Blockscout Ethereum `api/v2/smart-contracts/0xdAC17F95…1ec7`, 2026-09-01
- Live state via `eth_call` on `ethereum-rpc.publicnode.com`
- Event history via Blockscout `api?module=logs&action=getLogs`, topic0
  `0x61e6e66b…98c6` (`DestroyedBlackFunds`) and `0x42e16015…819dc` (`AddedBlackList`)
- Series companions: `USDC_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`, `UNISWAP_V3_DD.md`,
  `AAVE_LIQUIDATION_DD.md`, `HYPERLIQUID_BRIDGE_DD.md`, `HYPERLIQUID_HLP_DD.md`,
  `VARIATIONAL_CONTRACT_DD.md`
