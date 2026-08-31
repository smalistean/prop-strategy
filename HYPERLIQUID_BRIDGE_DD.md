# Hyperliquid — Arbitrum bridge (Bridge2) walkthrough

**Date:** 2026-08-31 20:10 UTC · **Author:** Claude
**Why:** learning deep-dive into our own live venue (XVF legs sit on HL; we've been ADL'd there),
and the custody chokepoint for every dollar on Hyperliquid. Companion to
`variational-contract-dd.md` — same method, very different trust model.
**Contract:** `Bridge2` at `0x2Df1c51E09aECF9cacB7bc98cB1742757f163dF7` (Arbitrum One),
verified, Solidity 0.8.9. Source pulled from Blockscout; live state via Arbitrum RPC.

## Scope note

Hyperliquid's exchange itself — the perp/spot orderbook, clearing, funding, liquidations, ADL,
and the **HLP vault** — runs on **HyperCore, a custom Rust L1, not Solidity**. There is no
contract source to read for those; their behavior is observable only via the public info API
(that's the separate I83 HLP decomposition). The one high-stakes piece that IS readable Solidity
is this bridge — the gateway where USDC crosses between Arbitrum and HyperCore.

## Live state (2026-08-31, Arbitrum RPC)

- **USDC held: ~$329.3M** in this single contract — all of Hyperliquid's bridged collateral,
  pooled in one address. A concentrated honeypot by design.
- `paused()` = false; `epoch()` = 7 (validator set has rotated 7 times).

## Deposit (dead simple, by design)

There is no per-user vault. You **send USDC directly to the bridge address** (a plain ERC20
transfer); HyperCore validators observe the on-chain Transfer off-chain and credit your HL
account. The `Deposit` event is declared but never emitted — deposits are tracked via the raw
ERC20 Transfer, not a bridge event. `depositWithPermit` / `batchedDepositWithPermit` are the
gasless variant: an EIP-2612 `permit` signature lets a relayer pull your USDC without you paying
gas. Deposits only work `whenNotPaused`.

## Withdrawal (two-phase, fraud-proof — the heart of the design)

Withdrawing is NOT unilateral and NOT instant. Three steps:

1. **`requestWithdrawal`** — must carry signatures from the **hot validator set** whose combined
   power exceeds **2/3 of total validator power** (`3 * cumulativePower > 2 * totalValidatorPower`
   — classic BFT quorum). The signed message is EIP-712 over (user, destination, usd, nonce).
2. **Dispute period** — the request then sits for a window that must clear **both** a wall-clock
   check (`block.timestamp > requestedTime + disputePeriodSeconds`) **and** an Arbitrum-block
   check (enough L2 blocks elapsed, via the `ArbSys(0x64).arbBlockNumber()` precompile). Belt-and-
   suspenders against block-time manipulation. The on-chain value is a few minutes and is
   changeable only by the cold validator set.
3. **`finalizeWithdrawal`** — after the window, a **finalizer** calls it and the USDC is
   transferred out. Also gated `whenNotPaused`.

The dispute window is the whole security idea: a fraudulent withdrawal request is visible
on-chain for the window before money moves, giving honest actors time to stop it.

## The security model, by role

- **Validators** (hot + cold sets, each with powers). >2/3 power signs withdrawals and routine
  validator-set updates (hot set); sensitive ops require the **cold set** (higher security,
  presumably offline keys).
- **Lockers** — any locker can `voteEmergencyLock`; once votes reach `lockerThreshold`, the whole
  bridge **pauses** (halts deposits AND withdrawal finalization). This is how a detected fraud
  gets frozen inside the dispute window before it finalizes.
- **Cold-set-only powers**: `invalidateWithdrawals` (cancel a fraudulent request during its
  dispute window — the actual fraud-proof), `changeDisputePeriodSeconds`, `changeBlockDuration`,
  `changeLockerThreshold`, and `emergencyUnlock` (rotate to a fresh validator set and unpause —
  recovery if hot keys are compromised).
- **Finalizers** — can trigger finalize/validator-set-finalize after the window (a liveness role,
  not a trust role; they can't move unauthorized funds).

So the flow under attack: bad `requestWithdrawal` appears → lockers `voteEmergencyLock` to pause →
cold validators `invalidateWithdrawals` → `emergencyUnlock` with a new validator set. All within
the dispute window, all requiring quorums.

## What this teaches (and the honest risk)

This is the canonical **optimistic validator-bridge** pattern, and a genuinely strong one:
withdrawal authorization is a decentralized **2/3 BFT quorum** with a **dispute window** and an
**emergency freeze**, plus a hot/cold key split. That is materially better than the bridges that
produced the biggest hacks (e.g. Ronin, where an attacker simply collected a majority of validator
keys and there was no dispute window to stop them).

But the trust root is still **"the validators don't collude and aren't compromised"**:
- Get >2/3 of hot-key power → sign fraudulent withdrawals; the only backstop is lockers freezing
  and the cold set invalidating **within the dispute window**. If the same compromise reaches the
  lockers/cold set, the backstop fails. Bridge security = validator key security, full stop.
- **$329M pooled in one address** is a maximal-value target.
- **No user self-exit** — same structural fact as Variational: you cannot unilaterally pull your
  funds; you depend on the validator set signing your withdrawal. The difference is *decentralized
  quorum + fraud-proof window* here vs *a single off-chain Oracle* at Variational. For custody
  risk that difference is large and in HL's favor — but it is not self-custody.

## Contrast with Variational (the point of doing both)

| | Variational SettlementPool | Hyperliquid Bridge2 |
|---|---|---|
| Custody | per-user cloned pool | one pooled contract (~$329M) |
| Who authorizes withdrawal | a **single Oracle** (off-chain operator) | **>2/3 validator quorum** |
| Fraud protection | none in code | dispute window + locker freeze + cold-set invalidate |
| User self-exit | none | none (but quorum-gated, not one key) |
| Verdict | trust one backend | trust a 2/3 BFT validator set |

## Follow-ons

- **I83 (HLP vault)** is the natural next step and needs the **info API**, not a contract read —
  decompose HLP returns into calm-period market-making carry vs event-day losses (Oct-10). The
  bridge tells you custody is quorum-safe; HLP tells you whether depositing into the house vault
  is real yield or short-vol.
- Our live XVF/ADL experience sits on HyperCore, above this bridge; the bridge is not where ADL
  happens (that's the L1 clearing), but it IS where our collateral enters and exits.

## Sources

- Verified source: Blockscout Arbitrum `api/v2/smart-contracts/0x2Df1c51E...163dF7`
- Live state: `eth_call` / `balanceOf` on `https://arb1.arbitrum.io/rpc`, 2026-08-31
- HL bridge address confirmed via Arbiscan / docs
