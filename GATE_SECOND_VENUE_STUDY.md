# Gate.io as a second execution venue for the weekend fade — measured, split verdict

**Date:** 2026-09-09 04:40 UTC · **Author:** Claude · **Question (user, 2026-09-09):** Binance's decision bar is thin on
PAYP, JPM, EWJ, QCOM, AXTI, AAOI, NOK; can another CEX take the overflow? A venue survey (Binance, Bybit,
Hyperliquid HIP-3, Gate, MEXC, Bitget, OKX; weekend of 2026-09-05) showed Gate as the only venue listing
all 24 names and the deepest alternative on most thin ones, so Gate was studied.

## Rule, declared before any outcome was read

Gate is admissible for a name only if **(a)** per-event net on Gate correlates > 0.8 with Binance and the
mean difference is within ±30 bp; **(b)** ≥ 80% of Binance-triggered events also trigger on Gate at Gate's
own decision bar; **(c)** the name's median Gate decision-bar quote volume over the ledger weekends is
≥ $5,000; **(d)** round-trip fees ≤ 15 bp.

## Result

Measured twice independently (own script + panel implementation), verified by re-fetching 18 events
from both APIs: zero discrepancies.

| Criterion | Result |
|---|---|
| (a) tracking | **PASS** — 48 comparable events, corr(net) 0.981, mean diff −1 bp; live universe n=41: corr 0.998, diff −10 bp |
| (b) same trigger | **PASS** — 43/48 = 90% (thin names 22/26 = 85%) |
| (c) liquidity | **FAIL** for PAYP ($122 median bar, n=16), JPM ($1,215), EWJ ($2,216); pass for NOK $8,112, QCOM $7,078, AXTI $6,181, AAOI $6,275 |
| (d) fees | PASS at published VIP0 (5+5 = 10 bp); exactly 15 bp if the contract-default 7.5 bp taker applies; account rate unmeasured |

**Verdict: admissible for NOK, QCOM, AXTI, AAOI; not admissible for PAYP, JPM, EWJ.** The split runs
against the question: the three names Gate was wanted for are the three it fails on, and on the four it
passes Binance already fills the Plan-S allocation on 8 of 12 events. Bar-close arithmetic at Plan S
(75% basket, 20% cap, order ≤ 10% of the decision bar on each venue, Gate only as overflow and only on
Gate's own trigger): Binance left $16,875 of unfilled allocation over 26 thin-name events; Gate's bars
would have absorbed **$1,472 (8.7%)**, worth **+$41 over the 21-weekend ledger ≈ +$1.9 per traded
weekend**, 60% of it one AXTI event. Plan S itself is +$61 per weekend.

## What the study established about Gate

- Gate tracks Binance because its stock-perp **index is derivative**: a median-guarded average of other
  venues' perp last prices (Binance, Bybit, Bitget, OKX, Hyperliquid, its own) and their indexes, plus at
  most one vendor feed; PAYP's four constituents are three crypto-venue prices plus Infoway. Not depth.
- 47 of 95 ledger events have no Gate history: the deep names (SPY QQQ COIN TSLA MSTR PLTR HOOD AAPL META
  CRCL AMZN) were **renamed and force-settled** 2026-08-30 → 09-09 (…X_USDT → …_USDT), open positions
  settled at a 30-minute index average on a weekday morning with ~2 days' notice; `in_delisting` did not
  lead the announcement. Three such events in four weeks.
- Funding: 8h, mostly zero prints on stock perps (the Binance +22 bp tailwind does not transfer); JPM's
  cap is ±0.02%/8h and binds. Fee schedule changed three times in 2026. API keys without an IP allowlist
  expire after 90 days. Announcements are only readable in a browser (Akamai blocks scripts).
- Live book 2026-09-09 04:01 UTC (weekday, weak proxy): PAYP spread 28.5 bp, $10k within ±50 bp;
  JPM 23.7 bp, $5.7k; EWJ 19.3 bp, $9.4k — against 1–11 bp and $18k–$96k on Binance.

## Standing decision

Not adopted. Gate adds ~$1.9 per weekend at the cost of a second account, a second manual exit in the
same minute, an index that is partly Binance's own perp, and a venue that force-settled 11 of these 24
names' predecessors last week. **PAYP-sized markets get PAYP-sized orders, on whichever venue.**
Re-open only if a pre-registered re-test of (c) over *all* weekends (not ledger weekends, which are
biased upward — on all weekends only NOK clears $5,000 today) passes for a name Binance is thin on.
Scripts and per-event outputs: scratchpad `gate_tracking_mine.json`, panel scripts under the same
directory (run wf_a65a7fd4-d1b).

## Addendum (2026-09-09 04:54 UTC) — venues as an AGGREGATE, not a substitute

The user's actual proposal: Binance takes what its bar allows; every other venue listing the name takes a
slice of the remainder, each under its own 10%-of-decision-bar cap. Measured on the 26 thin-name ledger
events (PAYP 8, JPM 2, NOK 2, EWJ 4, QCOM 4, AXTI 4, AAOI 2) with each venue's own decision-bar quote volume
(Binance, Gate, OKX, Bitget, Bybit, Hyperliquid HIP-3, MEXC; bars present: 26/26/15/14/12/8/4). Venue
tracking is measured only for Gate (corr 0.98) and Bybit (+3.9 bp); the others are assumed to track —
their indexes are, like Gate's, blends of other venues' perps.

| Account | Plan-S allocation on these events | filled by Binance | headroom | filled by the aggregate | value over the ledger | if a venue of infinite depth existed |
|---|---:|---:|---:|---:|---:|---:|
| $10k (basket 75%, cap 20%) | $36,699 | $19,824 | $16,875 | **$5,152 (31%)** | **−$35** (−$1.7/weekend) | +$254 (+$12/weekend) |
| $50k (same rule ×5) | $183,495 | $53,341 | $130,153 | $25,192 (19%) | **+$1,010** (+$48/weekend), of which **+$993 is one event** (AXTI 2026-08-14, +1,439 bp) | +$2,824 |

**62% of the headroom is PAYP ($10,526 of $16,875), and PAYP trades nowhere else**: Gate's eight decision
bars summed to $2,430 (a $243 cap); OKX, Bitget, Bybit, HL and MEXC do not list it. OKX contributes the
most aggregate capacity ($119k of cap over the events) and none of it to PAYP. The sign of the $10k
figure is noise (the extra size would have landed on two losing events); the size of it is the finding:
at this account size the aggregate is worth about nothing, and even a venue of unlimited depth would add
only +$12 a weekend, because Plan S's 20% cap, not liquidity, is what bounds the position. At $50k the
aggregate's whole contribution is a single AXTI weekend.

**Standing decision unchanged: not built.** Aggregation would need 4–6 accounts, 4–6 simultaneous manual
exits at 11:00 NY, per-venue tracking studies (only Gate and Bybit exist), and per-venue announcement
reading, for an expected value that at $10k rounds to zero and at $50k is one event. Re-open when the
own account is large enough that the per-name cap is routinely bound by Binance's bar on names other
than PAYP — the headroom table above is the test. Data: scratchpad `aggregate_venues.json`.

## Addendum (2026-09-09 05:13 UTC) — capacity: how much capital the fade can use

Same 21 weekends and 95 events, Plan-S rule at every equity (basket 75%, cap 20%/name, order ≤ 10% of
the decision bar), edge assumed unchanged inside the cap (the 10% rule is the boundary of what is
measured, not a measurement of slippage). "+aggregate" adds the six other venues' bars for the seven
thin names only; deep names use Binance's bar alone, so that column understates capacity above ~$300k.
Binance decision-bar quote volume over the 95 events: median $64.8k, p25 $19.1k, p10 $4.0k, min $6.

| equity | Binance-only P/L | ROE / annualised | executed share of allocation | marginal annualised return on the added capital | +aggregate ROE / ann. |
|---:|---:|---|---:|---:|---|
| $5k | +$636 | 12.7% / 21% | 87% | — | 12.0% / 20% |
| $10k | +$1,261 | 12.6% / 20% | 80% | +20% | 12.3% / 20% |
| $20k | +$2,130 | 10.7% / 17% | 72% | +14% | 12.8% / 21% |
| $50k | +$4,697 | 9.4% / 15% | 60% | +14% | 11.4% / 19% |
| $100k | +$8,505 | 8.5% / 14% | 49% | +11% | 10.2% / 17% |
| $200k | +$14,984 | 7.5% / 12% | 37% | +10% | 8.5% / 14% |
| $300k | +$18,478 | 6.2% / 10% | 29% | +6% | 6.9% / 11% |
| $500k | +$22,130 | 4.4% / 7% | 21% | +3% | 4.9% / 8% |
| $1M | +$23,652 | 2.4% / 4% | 12% | +0.5% | 2.7% / 4% |

Unconstrained reference (no cap): +14.4% over the ledger, +23%/yr. **Reading:** the strategy is
capacity-bound from the first dollar (80% of the allocation executes at $10k), degrades gently to about
$200k (marginal capital still earns ~10%/yr), and is effectively full around **$300–500k**, where added
capital earns 3–6%/yr and total dollar P/L plateaus near **$22–24k per 21 traded weekends
(≈ $35–40k/yr)**. At $50k the cap already binds on 39 of 95 events (PAYP 8, OPENAI 4, HOOD 3, EWJ 3,
AXTI 3, …). Aggregating venues moves the knee out by roughly $20–30k of equity and adds ~1–2 points of
ROE in the $20k–$150k range; it does not change the ceiling.
