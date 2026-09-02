# Stablecoin depeg dossier + trigger checklist (idea I53)

**Date:** 2026-09-02 04:35 UTC · **Author:** Claude
**What this is:** the I53 deliverable — reserve/redemption facts for the three stablecoins we
touch, plus a written trigger checklist, so that during a wobble we act from a prepared page
instead of researching under pressure. Substance comes from the contract reads
(`USDC_ISSUER_POWERS_DD.md`, `USDT_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`, `FRAX_LEGACY_FRXUSD_DD.md`, `CRVUSD_PEGKEEPER_DD.md`,
`CURVE_STABLESWAP_DD.md`); this document is the operational assembly.
**Not a backtest.** No thresholds here are measured optima — they are design choices anchored to
the Curve curve's geometry, and they are labelled as such.

## 1. Where our money actually is

| Bucket | Asset | Whose capital | Depeg exposure |
|---|---|---|---|
| Prop challenge account (~49.2k) | USDT | **the firm's**, not ours | indirect — affects the USD value of a future payout, not our capital |
| XVF baseline book (~$4.5k) | USDT across Binance/Bybit/HL | **ours** | direct |
| Open ONG narrow pair (~$211 notional) | USDT | **ours** | direct, tiny |
| Weekend fade (when live) | USDT-margined tokenized perps | ours | direct |
| Variational farming (if pursued) | **USDC** on Arbitrum | ours | direct, and semi-custodial (see its DD) |

**The single most useful framing:** the prop account is the firm's balance sheet. Our own USDT
exposure is small (low thousands). A depeg is therefore a *payout-value* and *own-book* problem,
not a 50k problem. Sizing the response to the 50k would be the mistake.

## 2. What actually backs each, and who can do what to you

### USDT — the asset most of our book is denominated in
- **Backing:** Tether's own reserves (T-bills, cash equivalents, plus other assets). Off-chain,
  attested rather than audited. Not verifiable from any contract.
- **Powers:** one `onlyOwner` key can **destroy** a blacklisted balance outright (`destroyBlackFunds`
  zeroes it and reduces supply) — used ≥1,000 times all-time, **29 events / $6.6M in ~8 weeks**.
  Also global pause, a switchable transfer fee (≤19 bp), uncapped `issue`, and `deprecate`.
- **Depeg history:** repeated small wobbles (2018, 2022 to ~$0.95 intraday), always recovered.
- **The honest read:** the largest, most centrally controlled, and most battle-tested of the three.

### USDC — the collateral under Variational and much of DeFi
- **Backing:** cash and short T-bills at regulated banks. **The failure mode is banking, not code**:
  in March 2023 USDC fell to ~$0.87 because $3.3B sat at Silicon Valley Bank over a weekend.
- **Powers:** blacklist = permanent freeze (cannot destroy today, but the proxy is upgradeable so
  that could change); global pause; four separated role keys (better practice than USDT's one).
- **The tell to watch:** bank-exposure news over a **weekend**, when redemption is closed. The SVB
  depeg happened precisely because the mint/redeem window was shut while the news was live.

### USDe — the one whose risk we already understand
- **Backing:** a live delta-neutral trade — long spot/LST, short perp on CEXes via custodians.
  **This is our XVF strategy at multi-billion scale.**
- **Depeg triggers:** (a) persistently negative funding making the backing bleed; (b) CEX or
  custodian failure; (c) redemption gating — only whitelisted MMs redeem at $1, so if
  `disableMintRedeem` fires, retail can only sell into the secondary market.
- **Exit friction:** sUSDe cooldown, admin-set up to **90 days**. In a panic the
  sUSDe→USDe→collateral path is throttled at two layers.
- **We can monitor this one with data we already collect:** aggregate perp funding across venues
  is the direct input to USDe's backing health, and `perp_funding_all` already holds it.

## 3. The leading indicator: composition, not price

From `CURVE_STABLESWAP_DD.md`, computed against live pool state at A=4000 (a $1M swap):

| Coin's share of the pool | price deviation |
|---|---|
| 33% (balanced) | ~0 bp |
| **53% (3pool USDT today — normal)** | **2.6 bp** |
| 70% | 10.7 bp |
| **80% — the knee** | **35.8 bp** |
| 90% | 285 bp |
| 95% | 1,977 bp |

**A stablecoin's price on Curve tells you nothing until it is already too late.** The pool absorbs
selling almost losslessly to ~80%, then goes vertical. The balance ratio moves days earlier.
This is the single most actionable finding in the dossier and it costs one `balances(i)` call.

Caveat that must travel with it: 3pool's 53% USDT weighting is **chronic and normal**, not a
signal. Only *change* matters, not level.

## 4. Trigger checklist

Thresholds are anchored on the curve's convexity (below the knee / at the knee / past it), **not**
on a measured event study. Written down in advance so they are not renegotiated mid-panic.

| Level | Condition | Action |
|---|---|---|
| **0 — normal** | coin ≤65% of its Curve pool, price within 20 bp | nothing |
| **1 — watch** | coin **>65%** of pool, **or** ≥10pp rise in a week, **or** price >30 bp off | Re-read this page. Check the venue's own funding/withdrawal notices. Note it in the journal. No position change. |
| **2 — de-risk** | coin **>75%** of pool, **or** price >100 bp off, **or** (USDe) aggregate funding negative for >7 days | Stop opening new positions in that asset. Move own-capital balances off-venue or into the other stablecoin. Do not start a fade weekend. |
| **3 — act** | coin **>85%** of pool (past the knee), **or** price >300 bp off, **or** issuer pauses mint/redeem | Flatten own-capital positions denominated in that asset and withdraw what can be withdrawn. Accept the exit cost — past the knee the cost of waiting rises faster than the spread. |

**Per-venue reality of "act":**
- **Own CEX balances** — withdrawable, but that is exactly when queues form. Assume slippage and delay.
- **Prop challenge account** — **no action possible.** Capital cannot be removed; it is the firm's.
  The only lever is to stop trading. Accept this in advance rather than discovering it at level 3.
- **Variational (if funded)** — withdrawals are `onlyOracle`; there is **no self-exit**. Level 2 is
  effectively the last point at which leaving is under our control. Treat level 1 as level 2 there.
- **XVF pairs** — closing needs both legs; a rejected close can leave a single unpaired leg that no
  close mode can flatten. Budget time and attention, don't attempt it at the last minute.

## 5. The subtlety that changes the maths: being flat is not being safe

If USDT depegs 5% while we hold USDT-margined perps:

- **Flat in USDT** → our USDT balance is unchanged; its USD value falls ~5%. **Full loss.**
- **Long crypto or tokenized-stock perps (USDT-quoted)** → the quoted price rises roughly in
  proportion, because it takes more USDT to buy the same asset. The long gains in USDT terms,
  **partially offsetting** the collateral's lost purchasing power.

So a USDT depeg penalises *idle* USDT and largely spares *deployed* long exposure. This inverts the
usual instinct to "go to cash" — in a USDT-denominated book, USDT **is** the risk asset. It also
means the weekend fade (long tokenized perps) is incidentally hedged against exactly this, and the
worst posture is sitting flat in USDT on a venue we cannot withdraw from.

Stated carefully: this holds for a *quote-currency* depeg with a USD-referenced index. It does not
protect against the venue itself failing, and it is not a reason to hold positions we would not
otherwise hold.

## 6. What this dossier does not claim

- No thresholds are empirically optimised — they are geometric choices from one curve, on one pool.
- Composition-as-leading-indicator is **reasoned, not measured**. A pre-registered study (does
  composition drift actually precede depegs?) has not been run; the sample of real depegs is tiny.
- Reserve facts are from issuer disclosure and contract code, not audit.
- Nothing here is a prediction that any of these three will depeg.

## 7. The composition monitor — built (2026-09-02)

`bash scripts/curve-monitor.sh` — read-only, runs daily via LaunchAgent
`com.smalistean.propstrategy.curve-monitor` (09:15 local), stores every reading in PostgreSQL
`curve_pool_composition` (migration V31; `curve_coin_aggregate` view), and writes
`CURVE_COMPOSITION_MONITOR.md` with the alert level per §4. Pools are discovered from the Curve API
under a frozen admission rule (nominal-$1 coins only, asymmetric 0.85–1.03 price band so a
depegging coin stays in scope, no metapools, ≥$1M, 3pool pinned, and — A4 — no coin without a live
par-redemption path) and every balance is
read on-chain. The per-coin **aggregate excess** (TVL-weighted across all admitted pools) is what
answers the question a single pool cannot: first reading showed FRAX/USDe at 77/23 — the aggregate
resolved it as FRAX weakness (+0.28 across two pools) with USDe on the scarce side (−0.27).
Reading the token (`FRAX_LEGACY_FRXUSD_DD.md`) then showed that weakness is structural — legacy FRAX
has no redemption path and its 1:1 migration ended — so FRAX pools were removed (A4); USDe now has no
admitted composition pool on Curve Ethereum and rests on the sUSDe NAV metric (A3).
Two admitted pools (USDC/crvUSD, USDT/crvUSD) are rebalanced by crvUSD PegKeepers
(`CRVUSD_PEGKEEPER_DD.md`): their share reading is damped whenever the PegKeeper may provide, and the
Regulator's own block test — this pool's crvUSD price > every other PegKeeper pool + 3 bp — is stored and
alerted on as A5 (`curve_pegkeeper_state`). Design and every post-hoc correction are disclosed in
`CURVE_MONITOR_PREREGISTRATION.md` (amendments A1–A5). Still true: composition-precedes-depeg is reasoned, not measured.

## Sources

`USDC_ISSUER_POWERS_DD.md`, `USDT_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`,
`CURVE_STABLESWAP_DD.md`, `VARIATIONAL_CONTRACT_DD.md`; live own-book exposure from
`XVF_LIVE_BOOK.md`; idea origin I53 in `data/ideas/prop-strategy-ideas.md` (gaevoy_adderivs).
