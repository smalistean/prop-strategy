# Curve 3pool (StableSwap) — where a depeg becomes visible first

**Date:** 2026-09-02 03:44 UTC · **Author:** Claude
**Why:** Curve is the venue that prices stablecoin-vs-stablecoin, so it is where a USDT, USDC or
USDe depeg would show up before anywhere else — a direct feed into the I53 depeg dossier, and the
natural follow-on to `USDT_ISSUER_POWERS_DD.md` and `ETHENA_USDE_DD.md`. Ninth in the
contract-reading series.
**Contract:** `TriPool` (DAI/USDC/USDT) `0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7`, verified,
**Vyper 0.2.4**, `proxy_type: None`, 847 lines. First non-Solidity contract in the series.

## Live state (2026-09-02)

| Field | Value |
|---|---|
| A (amplification) | **4000** |
| swap fee | 0.0150% |
| admin_fee | **100% of the swap fee** (raw `1e10` = `MAX_ADMIN_FEE`) |
| virtual_price | 1.039824 |
| balances | DAI $37.67M · USDC $37.62M · **USDT $85.22M** |

**The pool is 53.1% USDT against a balanced 33.3%.** More on why that matters below.

## The invariant: flat by construction

`get_D` solves the StableSwap invariant by Newton iteration (up to 255 rounds, converging to
±1 wei). Unlike Uniswap's `x·y=k`, StableSwap blends a constant-sum curve (flat, zero slippage)
with a constant-product curve (the safety net), and the amplification coefficient `A` sets how
far the flat region extends. At **A=4000** the flat region is very wide — which is the point,
and also the hazard.

## The quantitative finding: it holds the peg until it violently doesn't

Implementing `get_D`/`get_y` with the live A and simulating a $1M USDT→USDC swap at increasing
pool imbalance:

| USDT share of pool | effective rate | deviation |
|---|---|---|
| 33.3% (balanced) | 1.00000 | −0.0 bp |
| 50% | 0.99980 | −2.0 bp |
| **53.1% (today)** | **0.99974** | **−2.6 bp** |
| 60% | 0.99956 | −4.4 bp |
| 70% | 0.99893 | −10.7 bp |
| 80% | 0.99642 | −35.8 bp |
| 90% | 0.97154 | **−284.6 bp** |
| 95% | 0.80230 | **−1,977 bp** |
| 98% | 0.24006 | **−7,599 bp** |

At today's composition, even a **$10M** USDT→USDC swap costs only **3.7 bp**. The curve is
essentially flat to ~80% and then goes vertical: from 80% to 95% the price falls 20%.

### The consequence for depeg monitoring (I53)

**Price is a lagging indicator of a stablecoin depeg; pool composition is the leading one.** At
80% imbalance the quoted rate is still only 36 bp off — it looks fine on any price chart — while
the pool is one large seller away from a cliff. Anyone watching the *price* of USDT on Curve
learns nothing until it is already too late; anyone watching the *balance ratio* sees the
pressure building for days.

That is a concrete, cheap monitor: track the coin-share of the major Curve pools, not their
quoted rates. It needs no archive node — `balances(i)` is a single `eth_call` per pool.

Also worth stating plainly: the current 53.1% USDT weighting is **not** a distress signal. It is a
chronic, long-standing feature of 3pool (the market has always preferred to hold DAI/USDC and park
USDT here), and at 53% the price impact is 2.6 bp. It is the *direction of change*, not the level,
that would matter.

## Admin surface: the middle position of the whole series

Curve's owner can act, but every lever is constrained in code:

- **`ramp_A`** — A can be changed only gradually: at least `MIN_RAMP_TIME` (1 day), and by at most
  `MAX_A_CHANGE` (10×) per ramp. A cannot be yanked instantly, which matters because collapsing A
  would move the price curve under LPs' feet.
- **`commit_new_fee` → `apply_new_fee`** — separated by `ADMIN_ACTIONS_DELAY` = **3 days**, with
  `revert_new_parameters` available. Fee capped at `MAX_FEE` (0.5%), admin fee at 100%.
- **`kill_me`** — an emergency pause, but gated on `kill_deadline > block.timestamp`, where the
  deadline was set to deployment + `KILL_DEADLINE_DT` (**2 months**). 3pool has been live since
  2020, so **that window closed years ago and `kill_me` can never be called again.** A temporary
  training-wheel that permanently expired: a genuinely elegant piece of design.
- **No blacklist, no seize, no upgrade, no proxy.**

So Curve sits between Uniswap (nobody can do anything) and USDC/USDT (one key can freeze or
destroy): the owner can tune economics, slowly and visibly, but cannot touch anyone's funds.

## The LP economics footnote

`admin_fee` is at `MAX_ADMIN_FEE` — **100% of the 0.015% swap fee goes to the admin, so 3pool LPs
currently earn nothing from trading fees.** Confirmed in `exchange`:
`dy_admin_fee = dy_fee * admin_fee / FEE_DENOMINATOR`, deducted from the pool balance. The
`virtual_price` of 1.039824 is accumulated history (fees from when the admin share was lower, plus
imbalance gains), not current yield. Whatever LPs are earning today comes from CRV emissions, not
from the pool's own revenue — the same "passive side, thin compensation" pattern this series keeps
finding, in its starkest form yet.

## Where Curve sits in the series

| System | Freeze/seize | Pause | Upgrade | Owner economic levers |
|---|---|---|---|---|
| Uniswap V3 | no | no | no | protocol fee only |
| **Curve 3pool** | **no** | **expired** | **no** | A ramp (rate-limited), fee (3-day timelock) |
| Aave V3 | no seize | yes | yes | full risk params |
| USDC | permanent freeze | yes | yes | everything |
| USDT | **destroy** | yes | `deprecate` | everything, one key |

## Follow-on worth doing (cheap)

A composition monitor over the main stable pools (3pool, and the USDe/USDC and crvUSD pools) —
one `balances(i)` call each, logged daily. It would give the depeg dossier a real leading
indicator instead of a price chart, and it is a handful of `eth_call`s, not an indexer. Note this
as a *candidate*, not a committed build: it needs a pre-registered definition of what "pressure"
means before it is measured against outcomes.

## Sources

- Verified source via Blockscout Ethereum `api/v2/smart-contracts/0xbEbc4478…F1C7`, 2026-09-02
- Live state via `eth_call` (`A()`, `fee()`, `admin_fee()`, `get_virtual_price()`, `balances(i)`)
  on `ethereum-rpc.publicnode.com`
- Price table computed by reimplementing `get_D`/`get_y` in Python with the live A and balances
- Series companions: `USDT_ISSUER_POWERS_DD.md`, `USDC_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`,
  `UNISWAP_V3_DD.md`, `AAVE_LIQUIDATION_DD.md`, `HYPERLIQUID_BRIDGE_DD.md`,
  `HYPERLIQUID_HLP_DD.md`, `VARIATIONAL_CONTRACT_DD.md`
