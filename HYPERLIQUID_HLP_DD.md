# Hyperliquid HLP vault — return decomposition (idea I83)

**Date:** 2026-09-01 15:54 UTC · **Author:** Claude
**Question (I83):** is depositing into HLP real market-making/liquidation carry, or a hidden
short-volatility trap that blows up in a crash? Answered from the vault's actual PnL history.
**Method:** `POST api.hyperliquid.xyz/info {"type":"vaultDetails"}` for
`0xdfc24b077bc1425ad1dea75bcb6f8158e10df303`. HLP is HyperCore-native (no Solidity to read);
this is a data investigation, the companion to `HYPERLIQUID_BRIDGE_DD.md` (which covered custody).

## What HLP is

The Hyperliquidity Provider — a community-owned vault that market-makes every perp, **performs
liquidations**, supplies USDC, and accrues platform fees. Depositors put in USDC, get transferable
shares, earn a pro-rata cut of PnL in real time. **`leaderCommission = 0`** (confirmed): no
operator fee skim — it really is community-owned.

## Snapshot (2026-09-01)

- Current TVL: **$188.6M** · all-time cumulative PnL: **$137.9M** · current APR: **~6.7%**
- Series: 2023-05-10 → 2026-09-01, but only 98 points ⇒ **~12-day buckets** (see caveat).

## Decomposition (all-time, per ~12-day interval)

- **88% of intervals positive**, median interval +$0.62M — steady, boring carry.
- **Gross gains $150.1M vs gross losses only $12.2M** over 3.3 years — losses are ~8% of gains.
- The 6 worst intervals: **2025-11-12 −$4.66M, 2025-03-12 −$3.96M (the JELLY event),
  2026-04-15 −$2.28M**, then sub-$0.6M. Losses are rare and, at this resolution, bounded.

## The non-obvious finding: HLP is LONG crashes, not short them

The single best interval is **2025-10-15: +$41.4M** — cumulative PnL jumped $80.3M → $121.8M in
the two-week window containing the **October-2025 market-wide crash** (the same crash referenced
across the video batch — the −$20B liquidation cascade). HLP is the **liquidator/counterparty**,
so when over-leveraged traders got force-closed, HLP *harvested* it. That one crash window is
**~30% of HLP's entire all-time PnL.**

So the naive "short-vol vault that dies in a crash" model is **wrong for market-wide crashes** —
HLP is a net beneficiary of them. TVL confirms the reflexive cycle: it swelled to **~$500–580M**
in late 2025 chasing those crisis-era returns, then bled back to **$188.6M** as APR normalized
to ~7%.

## Where the real tail actually lives

Not in broad crashes — in **idiosyncratic single-asset squeezes where HLP is the trapped
counterparty.** The clearest is **JELLY, March 2025** (the −$3.96M net interval): a thin memecoin
was squeezed against HLP's short book; public reports put the *intraday* mark-to-market loss
around $10–13M before Hyperliquid delisted/settled JELLY and the vault recovered to a small net
interval loss. That is the true risk profile: short gamma on a *specific name* the vault is forced
to warehouse, not short vol on the market. Rare, and so far bounded — but it is the scenario a
depositor is actually underwriting.

## Verdict for I83

- **Real yield, not a fake-carry trap.** ~6.7% APR, 88% positive intervals, 12:1 gross gain/loss,
  zero operator commission. Genuine market-making + liquidation carry.
- **Crash-resilient — actually crash-*seeking*.** As the liquidator, HLP profits from cascades
  (Oct-2025: +$41M). A depositor is NOT short the next BTC flush; HLP is on the winning side of it.
- **The underwritten risk is a JELLY-type single-coin squeeze** against HLP's book — rare,
  historically recovered, but the genuine tail. Depositing HLP ≈ selling insurance against
  idiosyncratic thin-market blowups, paid ~7%/yr, with the market-crash scenario in your favor.

## Caveats (honest limits)

- **~12-day buckets** net intraday event drawdowns against same-window gains — the JELLY intraday
  low (~$12M) is invisible here (shows only −$3.96M net). A true event-day tail study needs daily
  snapshots or the fills feed; this endpoint won't give it.
- APR is trailing and regime-dependent (crisis windows inflate it; calm carry is lower).
- Custody sits behind the same bridge trust model as everything on HL (see bridge DD), plus
  vault-share redemption timing — not instant self-custody.

## Sources

- `vaultDetails` for `0xdfc24b077bc1425ad1dea75bcb6f8158e10df303`, api.hyperliquid.xyz, 2026-09-01
- HL docs (protocol vaults); companion custody note `HYPERLIQUID_BRIDGE_DD.md`
