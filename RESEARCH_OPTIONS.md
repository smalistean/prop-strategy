# Research options — ordered queue (opened 2026-08-12, reordered 2026-08-12)

One item at a time, in the order below. Each open item gets its own pre-registration document written
**before** any backtest, and its result recorded in that same document whether it passes or fails.

## Standing rules, derived from what has already happened here

1. **Payments beat predictions.** Sixteen pre-registered tests have run and **none has cleared its
   bar.** Cash-and-carry came closest at Sharpe 1.29 against a 1.5 requirement, and it is the only
   family that got within reach — a counterparty is obliged to pay funding there, where every test
   requiring a price forecast to come true was refuted by a wide margin. Candidates are still ranked
   by whether the return comes from an obligation or from a forecast, because that is where the
   near-misses are, but nothing here has yet earned the word "works".
2. **Per-year decomposition is part of the primary result, not a follow-up.** Eleven recorded cases
   have an aggregate result produced by one period or one symbol. `APOLLO_V6_WALKFORWARD.md`: the
   whole +$14,000 is one half-year, the other eighteen months net -$697. BTC return autocorrelation
   z=-4.29 came almost entirely from 2020, with every later year between -1.70 and +0.11.
3. **A control variant is declared before running.** In `GERCHIK_V2_PREREGISTRATION.md` the control
   beat both variants it was meant to calibrate.
4. **Passing a numeric bar is not adoption.** The carry participation addendum reaches Sharpe 1.58
   and maxDD 2.7%, clearing both bars while the primary configuration fails, and is still not
   adopted: it does not fix the year it existed to fix, and adopting the variant that survived after
   seeing which one survived is selection, not evidence.
5. **Return is a pass condition, not a footnote.** The carry participation variant clears Sharpe 1.5
   at +3.9%/yr with 81% of capital idle, and is not worth trading.
7. **Verify the join before computing anything.** The carry result was reported as passing at Sharpe
   2.16 and was actually 1.29: `futures_funding_rate` holds two overlapping `rate_type` values and a
   direct `SUM` double-counted 63,075 payments for sixteen large caps. It was found only because the
   same query pattern was being reused for a new measurement. Check duplication, coverage and units
   on every table join before it feeds a result.
6. **An item needs a consumer.** Work that produces data nothing is waiting on does not get ranked,
   however cheap it is.

---

## Closed — do not re-open without new data and a fresh pre-registration

| Item | Document | Outcome |
| --- | --- | --- |
| Apollo V5/V6/V7 level strategy | `APOLLO_V7_PREREGISTRATION.md` | Refuted. Three fixes, none held |
| Gerchik G1/G2/G3 | `GERCHIK_V2_PREREGISTRATION.md` | Refuted. Control (G3) beat both variants |
| Cross-sectional momentum | `XSMOM_PREREGISTRATION.md` | Failed bar. Sharpe 0.72 against a 1.2 requirement |
| Positioning signals (3) | `POSITIONING_PREREGISTRATION.md` | All refuted. Best 53.4% against a 51.1% base rate |
| Spot cash-and-carry | `CARRY_PREREGISTRATION.md` | **Refuted.** Sharpe 1.29 against a 1.5 bar, +3.4%/yr, three of six years negative |
| Carry participation threshold | `CARRY_PREREGISTRATION.md` addendum | Not adopted. Clears the bar (Sharpe 1.58) but 2026 stays at -4.1% and returns +3.9%/yr |

---

# 1. Cross-venue funding spread — Hyperliquid against Binance  *(IN PROGRESS)*

**Mechanism: payment.** Both venues pay funding on the same coin under different formulas and
schedules. Long one venue and short the other in the same asset is delta-neutral, requires no spot
pair, and both legs can receive at once.

**Measured on live rates before starting:**

- Hyperliquid pays hourly. Binance pays 8-hourly on most symbols and **4-hourly on 443 of 747**.
- **127 of 232** Hyperliquid perps sit near +11% annualised, its interest-rate component, which does
  not track the premium in quiet markets. Binance's rate floats.
- Median absolute spread across 206 overlapping coins is **1.9% annualised**, below round-trip cost.
  24 coins exceed 20%, 6 exceed 50%. Any edge is in the tail, not the median.

**Why it may be less contested than Binance carry:** two venues, margin on each with no
cross-margining, on-chain custody, and Hyperliquid has only existed since 2023. That is a barrier to
entry, not an absence of competitors. Desks do run this.

**What is being underwritten:** venue and bridge risk on Hyperliquid capital. It cannot be hedged and
is a plausible source of the spread.

**Status (2026-08-12): measured, and now awaiting forward evidence.**

Data complete: `hyperliquid_funding_rate` 4.45M rows and `hyperliquid_kline` 180,838 daily rows,
both 232 coins from 2023-05.

Exploratory result in `CROSS_VENUE_FUNDING_MEASUREMENT.md`: a 10-position book entering above a 20%
annualised spread returns **+7.1%/yr on two-leg capital at a Sharpe-like 1.46**, t = 2.58 over 162
weeks, all four years positive, and basis drift — the term that could have killed it — costs about
zero. The spread itself has 0.463 lag-1 weekly autocorrelation.

**It is not evidence.** Every cut was chosen while looking at the data, and the universe is fully
survivorship-biased. `CROSS_VENUE_FUNDING_PREREGISTRATION.md` fixes the parameters and is evaluated
only on weeks after 2026-08-17, over at least 52 weeks, against Sharpe >= 1.0.

This is the best candidate the project has produced, and the reason to be stricter rather than
looser.

**Limitations recorded before results exist:**

- History starts **2023-05-12**, found by binary search. 3.2 years against Binance's 6.5.
- The universe comes from the `meta` endpoint, which lists currently listed coins. There is no
  historical listing equivalent to the Binance S3 archive, so **delisted Hyperliquid perps are absent
  and any result is conditioned on survival to today.**
- A week-over-week funding autocorrelation of +0.568 was measured on 14 coins over 3 weeks. **It
  supports nothing.** n=28, and the sampled coins are mostly pinned at the +11% component, so the
  figure largely measures a constant. The test that matters is autocorrelation of the **spread**, on
  the tail coins.

**Next step:** measure spread persistence on the tail coins, then write the pre-registration.

---

# 2. ~~Carry leverage and margin efficiency~~ — WITHDRAWN 2026-08-12

This item existed because spot carry returned +5.7%/yr at **Sharpe 2.16**, which made the low return
look like a sizing choice: at 4x, roughly +23%/yr against about 12% drawdown, blocked only by the
spot leg consuming full notional.

**The Sharpe was wrong.** Corrected for the funding double-count, it is **1.29** — below the
strategy's own pre-registered bar — with 47% losing weeks and the entire cumulative result coming
from 2021. Levering that does not produce a business; it produces a levered coin flip.

Nothing replaces it at position 2. The margin-efficiency question is worth revisiting only if some
carry variant clears a quality bar first.

---

# 3. Delta-neutral perpetual-DEX vault liquidity provision

**Mechanism: payment.** Hyperliquid runs HLP, a vault anyone can deposit USDC into; it market-makes
and absorbs liquidations, taking the other side of trader flow, and depositors receive a share of its
profit and loss. GMX GM pools work similarly — LPs are the counterparty to traders. Traders pay fees
continuously and, historically, lose on net.

The vault carries inventory, so it has direction. The intent is to hedge that on Binance and keep only
the fee and PnL stream.

**Two problems, stated before any test:**

- **The hedge may not be implementable.** You do not control the vault's positions and they change
  faster than they can be observed. "Delta-neutral" is an aspiration here, not something that can be
  executed, and the honest first question is how large the unhedged residual is.
- **The return may be steady with rare large losses.** A vault that is short volatility pays a smooth
  stream and then gives back a multiple of it at once. Detecting that requires a long history and
  HLP's is short.

Public daily PnL history exists for HLP, so the shape question is answerable before committing
capital. Capacity is limited by vault size, which also limits how many competitors fit.

---

# 4. Options-implied forward against perpetual funding

**Mechanism: payment, with a convergence date.** Put-call parity on Deribit gives the forward price
from any call/put pair at the same strike and expiry:

```
F = K + (C - P) x S
```

That is the options market's price for holding BTC to that date. Perpetual funding is a second,
independent measurement of the same quantity. Where they disagree, the difference is collectable by
going long one and short the other, delta-neutral.

This is structurally stronger than item 1 in one respect: **expiry forces convergence on a known
date.** The cross-venue funding spread has no such mechanism and can stay dislocated indefinitely.

**Measured on the live BTC chain (index 63,426), 2026-08-12:**

| Expiry | Days | Implied forward, mark | Implied forward, executable |
| --- | ---: | ---: | ---: |
| 14AUG26 | 1.6 | +9.1% | **-34.3%** |
| 16AUG26 | 3.6 | +8.3% | **-288.5%** |
| 28AUG26 | 15.6 | +5.1% | -0.7% |
| 25SEP26 | 43.6 | +4.5% | +3.4% |
| 25DEC26 | 134.6 | +4.5% | **+4.2%** |

"Executable" sells the call at bid and buys the put at ask — the actual synthetic short forward, not
the mid. Binance BTCUSDT funding at the same moment: **+6.5% annualised**.

**Three findings, all measured rather than assumed:**

1. **Short expiries are unusable.** At 3.6 days the executable forward is -288% annualised. The
   bid-ask spread on two option legs swamps the trade. It survives only at one-to-three-month
   expiries, where annualising divides a fixed spread cost across many days.
2. **The BTC spread is about 2 points.** Perp funding +6.5% against an executable implied forward of
   +4.2% at 135 days, so roughly +2.3%/yr gross — before Deribit option fees, perp fees, and margin
   on two venues. Not enough as it stands. Whether altcoin chains are wider is unmeasured, and
   Deribit lists few of them.
3. **Historical option quotes are not available from Deribit.** `get_last_trades_by_instrument`
   returns no trades for the strikes that matter, and parity needs quotes rather than trades.
   `get_instruments?expired=true` returns only 42 instruments, all expiring the same day — a
   24-hour window, not history. `get_tradingview_chart_data` returns `no_data` for expired
   instruments. **Superseded — see below.**

### Correction (2026-08-12): historical quotes DO exist, free

Tardis.dev publishes the **first day of every month** without an API key, and Deribit's own
`options_chain` dataset is included.

- **Coverage 2019-04-01 through 2026-08-01**, verified by request: roughly **89 monthly snapshots**.
  (HEAD returns 404 on every date including ones that download successfully, so it is not a coverage
  signal; GET is.)
- **Tick-level within each day**, roughly 1.2-1.9 GB compressed per day.
- Schema carries exactly what parity and executability need:
  `bid_price, bid_amount, bid_iv, ask_price, ask_amount, ask_iv, mark_price, mark_iv,
  underlying_index, underlying_price, open_interest, delta, gamma, vega, theta, rho`.
- **All nine chains are present**, including the USDC altcoin ones: BTC, ETH, HYPE_USDC, BTC_USDC,
  ETH_USDC, AVAX_USDC, SOL_USDC, XRP_USDC, TRX_USDC.

**This changes the item's cost, not its rank.** Monthly spacing far exceeds the ~14-day decorrelation
implied by Binance funding autocorrelation of 0.417, so 89 monthly snapshots are close to
independent — more effective observations than 180 days of live recording would produce. Binance
funding history already covers 2020-2026 across 833 symbols, so the other side of the comparison
needs no new import.

**What monthly snapshots cannot do:** show how a position behaves *between* observations. They give
entry conditions and month-over-month persistence, not the path to expiry. The live recorder is
therefore kept running rather than cancelled — it supplies the contiguous hourly series that the
monthly files cannot.

Ranked fourth because of (2) and (3), not because the mechanism is weak — it is the cleanest
mechanism in the queue.

## Status: ACCUMULATING since 2026-08-12

Because of (3), the data cannot be obtained retrospectively, so recording started before the item
came up in the queue. Nothing here is testable for months. That is the point of starting now.

- `V10__create_deribit_option_quote.sql`, `DeribitChainSnapshotApplication.java`,
  `scripts/deribit-snapshot.sh`.
- **4,116 instruments per snapshot** across 9 chains, hourly, roughly 4GB/year.
- Deribit lists more than BTC and ETH: **BTC, ETH** (inverse, quoted in the base coin) and
  **BTC_USDC, ETH_USDC, SOL_USDC, XRP_USDC, TRX_USDC, HYPE_USDC, AVAX_USDC** (linear, quoted in
  USDC). The altcoin chains matter because altcoin perp funding is far more variable than BTC's
  +6.5%, so the spread there may be much wider than the 2.3% measured on BTC.
- Snapshots are hour-truncated with `ON CONFLICT DO NOTHING`, so a scheduler firing early, late or
  twice still yields one row per instrument per hour.

**Obstacle already visible in the first snapshot.** Share of instruments with a live bid: BTC 91%,
ETH 93%, SOL_USDC 53%, AVAX_USDC 50%, TRX_USDC 38%, **XRP_USDC 37%**. The chains most likely to carry
a wide spread are the ones where two thirds of strikes have no bid at all. A parity calculation on
mark prices would not show this, which is why bid and ask are stored as NULL when absent rather than
as zero.

**A missed hour is a permanent hole** — past quotes cannot be fetched later. The wrapper script logs
every run including failures for that reason.

---

# 5. Cross-venue listing gap

A coin trades on Hyperliquid before Binance lists it, or the reverse. During that window only one
venue has a market, and when the second lists it the two prices must converge.

**Last because there is no measurement at all**: not how often it happens, not how large the gap is,
not how long it lasts. It is a guess rather than a hypothesis with evidence. It needs the same
two-venue data as item 1, so inspecting it costs nothing extra once that exists.

---

## Parked — reasons stated

**Widen `futures_metric_snapshot` from 15 to 833 symbols.** The importer's default symbol list is
still the old universe, so the table covers 15 of 833 symbols across 7.4M rows, and the three
positioning signals were tested on roughly 2% of the panel. But those signals are refuted, and
widening the data does not license re-testing them — that would need a fresh pre-registration with
the bar fixed in advance. Nothing else is waiting on this data. It is cheap, and cheap is not a
reason. Park until something needs it.

**Carry capacity above $10M.** A liquidity-floor study found that on identical period coverage (286
rebalances, 31 in 2026) raising the floor from $10m to $50m median daily volume **improved** every
measure: funding unchanged at +9.1%, net +5.7% -> +6.2%, Sharpe 2.16 -> 2.95, maxDD 3.1% -> 1.0%,
2026 -4.9% -> -0.6%. This refuted the prediction made before running it. **All of those numbers came
from the defective funding query and have not been recomputed** — the direction of the finding
(higher liquidity floor helps) may survive, but the levels do not, and re-running it is not worth
doing while the strategy itself is refuted. Floors of $250m and $1bn are
uninterpretable because `CarryHarvestApplication.java:116` drops a period entirely when fewer than 10
names are eligible: 233 of 286 periods survive at $250m, and only 3 of 31 in 2026. Fixing that would
make roughly $50M and $200M of capital measurable. **Parked because it is irrelevant at $50k of
capital** — it matters only when managing other people's money. The primary result is unaffected: at
the $10m floor mean eligibility is 129 and nothing is skipped.

**Perp-only carry with a BTC beta hedge.** `CARRY_PERP_HEDGE_PREREGISTRATION.md` was written to reach
high-funding perps that have no spot pair, hedging with BTC at an estimated beta. Item 1 reaches the
same coins with a **same-asset** hedge and does not take on the residual beta risk. Spot carry's
basis leg cost -0.1% annualised over five years precisely because the hedge was the same asset. Keep
the document; run it only if item 1 fails for a reason that does not also apply here.

---

## Rejected without testing, with reasons

| Candidate | Reason |
| --- | --- |
| On-chain AMM arbitrage | Competes against MEV searchers with colocation and priority-fee access |
| Liquidation front-running | Adversarial and infrastructure-bound; same problem |
| Airdrop and points farming | Time-limited by design, not a repeatable process |
| CEX-to-CEX latency arbitrage | Requires colocation, which is not available here |

---

## Dead item still open in the task list

Task #5, the Apollo hook-trigger entry family, is still marked pending. Zero of the 20 labelled
examples in `GERCHIK_LABELLED_EXAMPLES.md` and `APOLLO_LABELLED_EXAMPLES.md` show the pattern, and
the surrounding Apollo work is refuted. It should be closed rather than built.
