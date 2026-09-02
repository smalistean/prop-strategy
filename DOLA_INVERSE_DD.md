# DOLA (Inverse Finance) — the counter-asset our sUSDe reading is quoted in

**Date:** 2026-09-02 14:44 UTC · **Author:** Claude
**Why:** amendment A3 measures sUSDe against its redemption NAV inside the only admitted wrapper pool,
DOLA/sUSDe ($50M). Every basis point of that reading is "sUSDe in DOLA", so DOLA's own peg enters it
one for one. Fourteenth in the contract-reading series; third driven by the tooling.
**Contracts (Ethereum, verified):** DOLA `0x865377367054516e17014CcdED1e7d814EDC9ce4` (Solidity 0.5.16,
2020); PSM `0x4dfd662622d766304cb539e66f893c4defa19398` + `PSMFed` `0x40051061…`; FiRM `Market`
contracts (largest `0xb427fC22…`); Curve DOLA/sUSDe `0x744793B5…`, DOLA/sUSDS `0x8b83c4aA…`.

## The answer first

**DOLA trades 22–28 bp below a dollar, has no working redemption path today, and is coupled to sUSDe
through a leverage loop — so the A3 number is not what it looks like.** The raw reading says sUSDe
trades at a **+19.6 bp premium** to NAV; priced through DOLA's real-dollar value it is a **−8.2 bp
discount**. Today the error is 28 bp and flips the sign. In an sUSDe stress it would run the other
way: 44M DOLA is borrowed against DOLA-sUSDe LP tokens, so an sUSDe drop liquidates those loops,
liquidators must buy DOLA, DOLA goes to a premium, and the DOLA-denominated reading would show a
*deeper* sUSDe discount than exists. Amendment A6 restates the metric in dollars.

## 1. What DOLA is

A 2020 mint/burn ERC-20 with an `operator` (the Inverse Timelock `0x926df14a…`) that adds and removes
`minters` and may mint directly. Anyone burns their own. No freeze, pause or blacklist. Supply
**102,868,118**.

Supply is managed by "Feds" — contracts governance authorises to mint (expand) and burn (contract),
executed by a Fed Chair multisig within DAO limits. Per the docs three are live: the **FiRM Fed**
("provides nearly all circulating DOLA"), the legacy **Frontier Fed** (managing the unbacked borrows
left by the 2022 exploits), and the **PSM Fed** (§3).

**Where the 102.9M sits** (top holders, on-chain):

| Holder | DOLA | What it is |
|---|---:|---|
| Curve DOLA/sUSDe | 36,818,195 | the A3 pool; 78% DOLA |
| FiRM markets, unborrowed | 39,787,920 | pre-minted by the Fed, **not circulating** until borrowed |
| DolaSavings (sDOLA) | 14,366,998 | staked DOLA earning DBR-funded yield |
| Curve DOLA/sUSDS | 4,972,692 | 78% DOLA |
| Arbitrum L1 gateway | 1,924,094 | bridged out |

Circulating DOLA is therefore ≈ 63M, of which two Curve pools hold 42M.

## 2. What backs it: FiRM loans, mostly against DOLA itself

`totalDebt()` across the eleven markets holding idle DOLA: **54,858,762 borrowed**. By collateral:

| Collateral | Borrowed | CF |
|---|---:|---:|
| DOLA-sUSDe Curve LP | 36,924,376 | 92% |
| yvCurve-DOLA-sUSDe | 7,012,288 | 92% |
| DOLA-sUSDS Curve LP | 4,907,275 | 90% |
| sDOLA | 4,325,502 | 87% |
| wstETH / WBTC / cbBTC / WETH | 915,220 | 85% |

**97% of borrowed DOLA is collateralised by positions that contain DOLA** — Curve LP of the very
pools that give DOLA its price, at 90–92% collateral factors. The DOLA/sUSDe pool is largely that
loop: borrow DOLA against the LP, add it to the pool, post the new LP, borrow again. The docs'
"every DOLA in circulation is backed by collateral" is true in the accounting sense; the collateral
is DOLA-and-sUSDe, and its value in a stress moves with the thing it backs.

The functioning peg mechanism is the borrower: when DOLA is under $1, repaying a $1 debt with a
$0.997 token is the arbitrage, and repayment burns. When DOLA is over $1, borrowing more is. That is
Frax-2020's AMO model with a lending book as the sink.

## 3. The redemption path that isn't running

The docs: DOLA "can be redeemed through the PSM" — 1:1 against USDS, 0 bp to mint, 20 bp to sell,
reserves held as sUSDS. On-chain on 2026-09-02 14:44 UTC:

| | |
|---|---|
| PSM `collateral` / `vault` | USDS / sUSDS — wired as documented |
| `buyFeeBps` / `sellFeeBps` | 0 / 20 |
| `getTotalReserves()` | **0**; USDS, sUSDS and DOLA balances all **0** |
| `PSMFed.supplyCap` / `supply` | 10,000,000 / **0** |
| `DOLA.minters(PSMFed)` | **false** — the Fed cannot mint |
| `Buy`/`Sell` events, last ~590k blocks (~82 days) | **none** |

The PSM exists, is parameterised, and has never been funded or used. Whatever "went live with a 10M
cap" meant off-chain, DOLA has **no par path a holder can exercise today**. The "minimum total
supply" check (100k vault shares) is an inflation-attack guard, not a redemption limit.

## 4. Price and pools (2026-09-02 14:44 UTC)

There is no DOLA/USDC-type pool above $300k on Curve Ethereum any more. DOLA's dollar price has to
be read through a yield wrapper whose NAV is known:

| Pool | TVL | DOLA share | DOLA → counter (marginal) | counter NAV | DOLA in dollars |
|---|---:|---:|---:|---:|---:|
| DOLA/sUSDS | $6.5M | 78.0% | 0.89983 sUSDS | 1.10823 USDS | **0.99723 (−27.7 bp)** |
| DOLA/sUSDe | $49.9M | 77.7% | 0.80067 sUSDe | 1.24618 USDe | 0.99778 (−22.2 bp) |

Both pools are 78% DOLA: DOLA is the coin being sold, same one-way pattern as legacy FRAX, milder.

**What it does to A3:** sUSDe → DOLA marginal 1.24862 vs NAV 1.24618 = **+19.6 bp raw**;
× DOLA 0.99723 USD → sUSDe implied 1.24516 USD = **−8.2 bp**. Sign flipped by the counter-asset.

## 5. Bad debt

DL News, 2025-07-28: the protocol carried "$12 million in bad debt" from the April and June 2022
Frontier exploits and the March 2023 Euler attack; it raised $2.6M by selling 104,000 INV at 25 DOLA
each, leaving "$3.4 million" to be covered by borrowing from 40acres. The live figure on the
transparency page did not render (JS shell); not verified here.

## 6. Consequence for the monitor (amendment A6)

- The wrapper NAV reading is **restated in dollars**: implied sUSDe price in counter × counter's
  dollar price, where the counter's dollar price is read from its deepest pool against a wrapper
  with a live par path (DOLA → sUSDS × `convertToAssets`; USDS is 1:1 with DAI at Sky and DAI
  redeems through the PSM). Raw and corrected are both reported; the level uses the corrected value.
- A **counter-asset health line**: DOLA's dollar price, its share in its own pools, and the PSM's
  actual reserves (par capacity). If the counter deviates more than 100 bp from a dollar the wrapper
  reading is flagged unreliable and cannot raise the level — at that point it is a DOLA event.
- A4 said a coin without a par path leaves the universe. DOLA stays only in this corrected,
  flagged form, because it is the sole $10M+ sUSDe pool on Curve Ethereum; that exception is written
  down as such.

## 7. Series through-line

Who can turn DOLA into a dollar? Nobody, today: the PSM is empty. What holds the price near a dollar
is the 55M of debt that must be repaid in DOLA — the same force that holds a fractional stablecoin
together until the moment the collateral behind that debt is the stablecoin's own liquidity pool.
Two of the three stablecoins the tooling leans on turned out to have no par path (legacy FRAX, DOLA)
and one has a public par window holding $18 (frxUSD). The par path is the question; the price band
never asks it.

## Not verified here

The current bad-debt figure; the FiRM Fed and Frontier Fed contract addresses (not in the docs read,
`minters` is not enumerable); DBR economics behind sDOLA's yield; the PSM controller's `onBuy`/`onSell`
rules (hooks only, no limits readable); Arbitrum/Base/Optimism DOLA liquidity.

## Sources

On-chain reads via public RPC and Blockscout verified sources (addresses above); event logs via
drpc; Curve API. Inverse docs: DOLA Feds
https://docs.inverse.finance/inverse-finance/inverse-finance/products/dola-feds, DOLA
https://docs.inverse.finance/inverse-finance/inverse-finance/products/tokens/dola.md, PSM
https://docs.inverse.finance/inverse-finance/inverse-finance/products/peg-stability-module.md;
DL News 2025-07-28 https://www.dlnews.com/articles/defi/inverse-finance-lures-defi-investors-to-plug-bad-debt/.
