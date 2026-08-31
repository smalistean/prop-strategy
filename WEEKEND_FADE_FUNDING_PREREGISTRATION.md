# Weekend fade with funding — pre-registration (re-implementation of idea board #1 + I87 extension)

**Author:** Claude · **Frozen:** 2026-08-30 (UTC), before any outcome query was run.
**Status:** design locked; results appended below the line after execution.

## Why this study

Idea board #1 measured the tokenized-stock-perp weekend fade at +90.7 bp/event
(de-clustered, n=16 weekends, t=1.57) with a ~9 bp all-in taker cost, **price only**.
The video-batch finding I87 (Ostium founder; first-pass query 2026-08-30) shows
weekend funding magnitude on these perps is 1.5–3x weekday. A long position held
over the weekend pays or receives that funding; the board's ledger has never
included it. This study re-implements the event study with funding P&L in the
ledger. The original implementation was ad-hoc and is not preserved, so results
here are **not byte-comparable** to the board's +90.7 bp; this is a
re-implementation with pinned definitions, not an independent replication —
the sample largely overlaps.

## Universe

**Primary ("US-hours"): 27 symbols** — all Binance tokenized US equity/ETF perps
with local 1h klines + funding: SPY, QQQ, EWJ, EWY, COIN, TSLA, MSTR, PLTR, HOOD,
AAPL, AMZN, META, INTC, MU, CRCL, NVDA, LLY, JPM, QCOM, TSM, PAYP, SNDK, AAOI,
AXTI, NOK, OPENAI, SPCX (USDT-suffixed). Each contributes from its first complete
weekend after listing to the end of its local kline coverage.

**Exploratory ("metals/energy extension", reported separately, never pooled):**
XAU, XAG, XPT, XPD, COPPER, CL, BZ, NATGAS. Their underlying (CME Globex) reopens
Sunday evening, so the reversion anchor differs (see definitions).

## Definitions (all timestamps UTC; 1h bars keyed by `open_time`; price = `close_price`)

US DST in sample: 2025-11-02 → 2026-03-08 is winter; 2026-03-08 → 2026-11-01 is summer.

**Equities/ETFs:**
- `P_fri` = close of the bar with open_time Friday **19:00** (summer) / **20:00** (winter) — the bar ending at the 20:00/21:00 UTC equity close.
- `P_entry` = close of the bar with open_time Sunday **19:00** (i.e. price at Sunday 20:00 UTC), year-round.
- **Event**: `P_entry / P_fri − 1 ≤ −50 bp`. Position: long at `P_entry`.
- `P_exit` = close of the bar with open_time Monday **14:00** (summer) / **15:00** (winter) — 30–90 minutes after the 13:30/14:30 UTC equity open.

**Metals/energy (exploratory):**
- `P_fri` = close of bar open_time Friday **20:00** (summer) / **21:00** (winter) — Globex close 21:00/22:00 UTC.
- `P_entry` = same Sunday 19:00 bar close as equities.
- `P_exit` = close of bar open_time Sunday **23:00** (summer) / Monday **00:00** (winter) — one hour after the Globex Sunday reopen (22:00/23:00 UTC).

**Funding**: funding P&L (long) = **−Σ funding_rate** over `funding_time ∈ (entry_ts, exit_ts]`, where entry_ts = Sunday 20:00 UTC and exit_ts = the exit bar's close time. No cadence assumption — rows are summed as stored.

**Costs**: 9 bp per round trip (board's frozen all-in taker figure). Net = price return + funding − 9 bp.

**De-clustering**: one observation per weekend per group = the mean across that weekend's triggered symbols. Stats on the weekend series: n, mean, median, SD, t = mean/(SD/√n), min; funding contribution reported as the mean of the funding component.

**Holiday exclusions (pre-listed)**: weekends adjacent to full US market closures inside the sample — 2026-01-17/19 (MLK Mon), 2026-02-14/16 (Presidents Mon), 2026-04-03/06 (Good Friday), 2026-05-23/25 (Memorial Mon), 2026-07-03/06 (Independence observed Fri). Applied to both groups.

**Secondary cut (pre-declared, context only):** the same ledger on *all* weekends (no −50 bp filter), to show whether the conditioning matters.

## What would change the board entry

- If the funding component leaves the net mean materially positive → board #1's economics survive the I87 critique; note funding as a small tax or bonus.
- If funding flips the net mean to ≈0 or negative → the fade as specified is weaker than recorded; the board entry gets a written correction with these numbers.
- Either way the sample stays too small for promotion; this study updates the ledger, nothing more.

---

## Amendment A1 — mirror short (declared 2026-08-30 before its outcome query)

Prompted by the user's question whether the fade works in the opposite direction. **Event:**
weekend move ≥ **+50 bp** (same P_fri/P_entry bars). Position: **short** at P_entry, cover at
P_exit. Ledger: price = −(P_exit/P_entry − 1); funding (short) = **+Σ funding_rate** over the
same window (shorts receive positive funding); cost 9 bp; same de-clustering, same universe,
same holiday exclusions. Prior stated in advance: the unconditional cut already showed a
+50 bp Monday drift on these perps, which is a direct headwind for shorts — the mirror must
overcome it, so symmetric performance is NOT expected.

---

## Results (appended after execution — see below)

**Executed 2026-08-30 11:44 UTC** via `scripts/analysis-weekend-fade-funding.sql`. One deviation
from the frozen design, forced by a data-quality discovery mid-run: `binance_perp_funding_rate`
stores each funding print 1–3 times (identical rates within a duplicate set — verified across
7 symbols: 4,913 prints ×1, 6,135 ×2, 459 ×3, zero conflicting rates). The funding sum was
deduplicated on (funding_time, funding_rate) before summing; the first (pre-dedupe) run
double-counted funding and is discarded. A verification pull of COINUSDT's 2026-03-08/09 window
confirmed the deduped sum matches the raw prints (−128.33 + 5.72 bp → +122.6 bp to the long).
Funding cadence on these perps is 8h (00:00/08:00/16:00 UTC); several June–July windows contain
genuine 0.0000 prints (rows verified present, not gaps).

### Primary: equities/ETFs, trigger ≤ −50 bp, de-clustered to weekends

| n weekends | n events | mean net | median net | SD | t | worst weekend | price part | funding part |
|---|---|---|---|---|---|---|---|---|
| 17 | 85 | **+147.5 bp** | +140.5 | 334.5 | **1.82** | −427.9 (2026-02-20) | +130.2 | **+26.2** |

**Funding verdict (the I87 question): funding is on average a bonus to the fade, not a tax.**
After a weekend dump the perp trades at a discount and funding prints negative — shorts pay the
long fade position. Per-event funding distribution: min −45.0, p25 −0.5, median +6.4, p75 +70.3,
max +200.0 bp. It is not free money every time — the tail against (PAYP 2026-04-17: −45 bp on a
−400 bp price leg) exists exactly as the I87 caveat predicted — but the sign of the average is
favorable, and on crash weekends (2026-03-06, 04-10, 04-17) the funding leg alone paid
+50–200 bp/event.

### Control: all 26 equity weekends, unconditional

mean +50.2 bp, median +12.3, t=1.24 (price +52.8, funding +6.4). The −50 bp trigger roughly
triples the mean and shifts the median from +12 to +140 — the conditioning is doing real work;
the effect is not "these perps drift up on Mondays."

### Exploratory: metals/energy, Globex-reopen exit

mean +18.7 bp, median −49.7, t=0.36, n=11 weekends. **Nothing there.** Consistent with the
mechanism: metals' underlying reopens Sunday evening, the dislocation window is hours not days,
and the Sunday-reopen anchor arrives before much of a gap can build. The fade is an
equity-perp phenomenon on this evidence.

### Honest read

- Per the pre-registered decision rule: **board #1's economics survive the I87 critique** — the
  net stays materially positive with funding in the ledger, and funding adds ≈ +26 bp/weekend
  on average rather than subtracting.
- t=1.82 on n=17 weekends is still below conventional significance; this remains a prior, not a
  verdict. The mean leans on the March–April crash-rebound weekends; median +140.5 says the
  center of the distribution is positive too, which the original (median +26.9) could not say
  as strongly — but note this run's universe (27 symbols) is wider than the original 10, so the
  medians are not comparable.
- Failure modes visible in the event table: single-name information gaps (AXTI 2026-07-10:
  −1,276.8 bp net — the weekend dip was real news, not crypto noise; OPENAI 2026-07-17:
  −1,016.6 bp), and trend weekends where Monday keeps falling (2026-02-20, 2026-05-15 —
  every triggered symbol negative). Any live sizing must survive a −430 bp de-clustered weekend
  and a −1,280 bp single name.
- Not independent confirmation of the original +90.7 — the sample overlaps and the
  implementation is new (this one is pinned in this doc + the script; the original is lost).
- The 9 bp flat cost ignores weekend book thinness at Sunday-20:00 entry; execution-quality
  measurement is the next step before any live sizing discussion.

### Extension E1 — full Binance US-equity universe (declared 2026-08-30 14:45 UTC, before outcomes)

Binance `exchangeInfo` (pulled live 2026-08-30) classifies 182 non-crypto perps; ~140 carry
`underlyingType=EQUITY` (US-listed stocks, ADRs, and ETFs) vs the 27 this study measured — the
original universe came from the prop watchlist, not Binance's full list. E1 re-runs the
identical event definition on the full EQUITY-type set, with mechanical exclusions declared
now: leveraged/inverse/volatility ETPs (SOXL, SOXS, TQQQ, SQQQ, TZA, TBT, TMF, UVXY — daily
rebalance path products), crypto-underlying ETF (BITO — its underlying trades 24/7, no stale
anchor), private companies (SPCX, SPCXUSD1, OPENAI, ANTHROPIC — rule A2). HK/KR/CN equity,
PREMARKET, COMMODITY, and INDEX types are outside EQUITY by construction. Local 1h data for
the new names ends 2026-07-31, so their weekends end at Friday 2026-07-24; missing bars drop
events via inner joins as usual. Cuts reported: original 27 (headline, unchanged), new names
only, combined. Unfamiliar tickers inside EQUITY are accepted mechanically (Binance's own
classification), flagged if their behavior looks non-US-hours.

**E1 result (run 2026-08-30 14:55 UTC): the edge does NOT extend to the broad universe.**

| Cut | n weekends | n events | mean net | median | t | worst |
|---|---|---|---|---|---|---|
| Original 25 (full sample) | 17 | 80 | +175.5 | +140.7 | 2.10 | −427.9 |
| New ~110 names (Apr–Jul) | 11 | 133 | **+23.2** | +14.8 | **0.22** | −865.7 |
| Combined | 18 | 213 | +109.5 | +133.2 | 1.69 | −427.9 |
| **Same-period control: orig, Apr–Jul only** | 10 | 59 | **+240.8** | +318.4 | **2.04** | — |

The same-period control settles the confound: on the identical April–July weekends the
original names earned +240.8/weekend (t=2.04) while the new names earned +23.2 (t=0.22). The
fade is a property of the crypto-adjacent, high-attention names crypto traders actually push
around on weekends — not of tokenized equity perps in general. The new-name tail also
produced the worst single events (LITE −866, APP −815, BOT −639, GOOGL −515): thin, obscure
names whose weekend drops carry information or never revert. **Decision: the live universe
stays at the measured 24; broad-universe expansion is rejected by measurement.** Caveats on
record: the new names have ≤11 weekends and never lived through a crash regime
(March–April); the monthly re-measure extends this cut, and a pre-registered update can
revisit if the picture changes.

### Amendment A2 — private-company perps have no Monday anchor (filed 2026-08-30 14:05 UTC)

During the live Sunday news-check routine it was noticed that **SPCX (SpaceX) and OPENAI
(OpenAI) are private companies: no listed stock exists, so no Monday US open ever re-anchors
those perps** — the fade's mechanism is structurally absent for them. The exclusion rule is
mechanical and would have been valid ex-ante ("underlying must be an exchange-listed security"),
but honesty requires the caveat: it was noticed only AFTER seeing that both were in-sample
losers (SPCX −83, OPENAI −96 bp mean net), so the exclusion is outcome-contaminated and both
cuts are reported permanently:

| Cut | n weekends | n events | mean net | median net | SD | t | worst |
|---|---|---|---|---|---|---|---|
| All 27 (original primary) | 17 | 85 | +147.5 | +140.5 | 334.5 | 1.82 | −427.9 |
| Excl. SPCX+OPENAI (A2) | 17 | 80 | +175.5 | +140.7 | 344.8 | 2.10 | −427.9 |

The original all-27 numbers remain the study's headline. **Going forward (live spec), the
mechanical rule applies: exchange-listed underlyings only** — a new private-company perp
listing joins the exclusion on the same grounds without a new amendment.

### Amendment A3 — intraday stop variants (declared 2026-08-30 14:20 UTC, before outcomes)

Question (user): should a losing Monday position be cut before the timed exit? Test: for each
of the 85 primary events, the max adverse excursion (MAE) = min(hourly low)/P_entry − 1 over
(entry, exit]. Stop variants at −200/−300/−500 bp from entry: if MAE breaches, the event's
price return becomes the stop level (an OPTIMISTIC fill — hourly bars hide gap-through, and
Monday-open gaps are exactly where the big losses live, so a real stop fills worse). Price-only
comparison, de-clustered as usual. Prior stated in advance: the measured winners dip first
(COIN crash-weekend example: −1.4% floating before +6.4% net), so stops are expected to
convert winners into losers and hurt the mean.

**A3 result (run 2026-08-30 14:25 UTC): stops destroy the strategy; the timed exit stands.**
MAE distribution across the 85 events: the MEDIAN event trades −200 bp below entry at some
point during the hold; p25 −307, p10 −451, worst −1,329. 42/85 events touch −200, 25/85 touch
−300, 7/85 touch −500. De-clustered weekend ledgers (price-only, −9 bp cost):

| Variant | mean | median | t | worst weekend |
|---|---|---|---|---|
| No stop (the rule) | +121.2 | +129.3 | 1.53 | −429.6 |
| Stop −200 bp | **−22.9** | −72.3 | −0.48 | −209.0 |
| Stop −300 bp | **−3.0** | +13.5 | −0.05 | −309.0 |
| Stop −500 bp | +125.8 | +144.1 | 1.58 | −457.6 |

A −2% or −3% stop flips the ledger to ≈zero or negative because the typical WINNER dips
through those levels before the Monday-open snap (the path shape, not bad luck). The −5% stop
is statistically indistinguishable from no stop (7 hits, +4.6 bp difference — noise), and its
fills are optimistic: hourly lows hide gap-through, and the disasters it would "catch" are
opening gaps that fill far below the stop level. Conclusion per the declared prior: no stop;
losses are taken at the timed exit; position size is the risk control.

### Amendment A1 result — mirror short: DOES NOT WORK (run 2026-08-30 13:20 UTC)

Weekend move ≥ +50 bp, short at Sunday 20:00, cover Monday after the open: **20 weekends,
149 events, mean net −70.4 bp, median −24.2, t=−1.17, worst weekend −563.5** (price −69.9,
funding +8.5). As the pre-declared prior predicted: pumped perps do print positive funding
(shorts collect +8.5 bp on average), but the ~+50 bp unconditional Monday drift plus the
asymmetry of the re-anchoring runs over the short side. The fade is long-only. Do not mirror it.
