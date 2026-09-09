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

## Amendment A4 — 2026-09-06 18:40 UTC: the holiday-weekend variant, queried post-hoc, stays untradeable

**Disclosed as post-hoc.** Prompted by a live question on Labor Day weekend 2026-09-06 ("a 3-day
weekend is just a longer weekend — why not trade it?"), an outcome query was run on the holiday
weekends the primary study had pre-listed as exclusions. No position was open and this weekend's skip
had already been decided on the frozen rule, so the query cannot have influenced a live trade; it is
a pre-registration for future holiday weekends, not a justification for a past one.

**Design (stated before the query):** the three Monday-closure weekends inside the data window — MLK
2026-01-19, Presidents 2026-02-16, Memorial 2026-05-25 — same 24-name universe, same −50 bp trigger
at the Sunday 20:00 UTC bar, same 9 bp cost, funding included. Two exits compared: **Monday 11:00 NY**
(the live spec as written, which on these dates lands in a closed market) and **Tuesday 11:00 NY**
(the actual reopen). Declared in advance: n=3 cannot establish an edge; the query can only show
whether holiday weekends look grossly unlike normal ones.

**Result — the sample is n=1, not n=3:**

| Weekend | triggers | exit Mon 11:00 NY | exit Tue 11:00 NY |
|---|---:|---:|---:|
| MLK 2026-01-19 | **no data** — none of the 24 perps was listed (first, TSLA, 2026-01-28) | — | — |
| Memorial 2026-05-25 | **0** | — | — |
| Presidents 2026-02-16 | 1 (INTC, −53 bp) | **−27.4 bp** | **−177.1 bp** |

One of the three holiday weekends predates every listing; of the two with data, one produced a single
triggered name — negative on both exits. (Corrected 2026-09-06 18:49 UTC: the first draft of this table
recorded MLK as "0 triggers"; it was no data.) **The variant is not weakly measured; it is unmeasured**, and it
will stay that way: triggers fire on roughly a third of weekends and holidays come three times a
year, so the expected accumulation is well under one observation per year.

**Incidental, same query, one normal weekend for scale:** 2026-08-21 (4 triggers) returned **−96.3 bp**
exiting Monday and **+243.2 bp** exiting Tuesday. A single extra day swung that weekend by ~340 bp.
n=1 for the comparison and not a finding, but it sizes the question: in these names an extra day of
holding is a large bet, not a rounding error. The strategy's stated risk control is the timed exit
("the exit time is the strategy", A3) — a holiday weekend forces that exit to be either early (before
the catalyst, into a closed market) or extended by 24 unmeasured hours.

**Standing decision: holiday weekends remain a whole-weekend skip** (`WEEKEND_FADE_LIVE_SPEC.md`).
The cost of the skip is small and now quantified — of the two historical holiday weekends with data, one had a single trigger,
so the rule forgoes on the order of one trade per year. The alternative is an unmeasurable
coin flip on a challenge account with a 2,500 USDT hard limit. Revisit only if the variant somehow
accumulates n ≥ 8 triggered weekends, which is not expected this decade.

### A4 addendum — 2026-09-06 18:49 UTC: the shifted variant (trigger Monday 20:00 UTC, exit Tuesday 11:00 NY)

The question was sharpened: not "hold longer", but **shift the whole structure by a day** — anchor
Friday close, evaluate the −50 bp trigger at Monday 20:00 UTC (the evening before the actual reopen),
exit Tuesday 11:00 NY (first full hour after it). Same 19–20 h hold as the measured trade. Stated
before the query: this is the correct mapping of the strategy onto a 3-day weekend, and n cannot
exceed the number of holiday weekends with listings.

| Weekend | names with data | triggered at Mon 20:00 UTC | closest | unconditional Mon→Tue, all names |
|---|---:|---|---|---:|
| MLK 2026-01-19 | 0 | — | — | — |
| Presidents 2026-02-16 | 8 | **none** | INTC -46.9 bp — 3.1 bp short of the line (it *was* −53.3 on Sunday and recovered) | **−108.8 bp** |
| Memorial 2026-05-25 | 20 | **none** | every name up +96 to +589 bp vs Friday | **−42.5 bp** |

**n = 0.** There is not one triggered name in the history of this variant; there is nothing to
average. The two control cuts are both negative — holding everything through a holiday reopen lost
109 and 43 bp — the same sign as the normal-weekend control (−36.5 bp on 2026-08-30), so there is no
"buy everything on holiday Monday" edge hiding underneath either. The INTC bar was re-read
hour-by-hour to confirm the −50 line was not crossed by a bar-convention artefact: the Monday 20:00
UTC bar (open 19:00) closed at -46.9 bp, and the Sunday 20:00 UTC bar at −53.3 bp, under the same
close-of-bar-ending-at-T convention the primary study used.

**Standing decision — superseded 2026-09-06 19:29 UTC by the user:** holiday weekends are **not** a
separate case; the fade is timed to the next US session (entry 20:00 UTC the evening before it, exit
11:00 NY on it) on every weekend alike, and the shifted form is traded under the live spec's ordinary
rules and sizing. The measurement above stays as the honest prior (n = 0 triggered); each traded holiday
weekend is appended to this ledger as an observation of the shifted form. The author's recorded view
was to skip until measured; the user's view is that the mechanism is identical and the sample will
only ever exist by trading it. Both are on the record; the user's decides.
**Independent recomputation (2026-09-06 18:53 UTC):** two implementations written without sight of this
table or the repo, from the Binance public API only, reconciled by a third pass that re-fetched any
disputed bar. Result: agree = True, discrepancies = 0. Both reproduce
the table above exactly — names with data 0 / 8 / 20, triggered sets empty, INTC −46.91 bp, unconditional
−108.83 and −42.46 bp. One boundary case surfaced and is immaterial: Binance stamps funding at
HH:00:00.001, so the Presidents 16:00 UTC exit print falls 1 ms outside the (entry, exit] window under
the literal rule; including it moves two untriggered names by ≤ 4 bp and no trigger. Scripts:
`scratchpad/weekend_fade_holidays_impl{1,2}.py`.

## Amendment A5 — 2026-09-08 09:00 UTC: does a run-up into the anchor predict a worse fade? (tested, no)

**Origin.** Live question on 2026-09-08 with PAYP open: it had run +5.1% Wednesday and +7.4% Thursday
into the Friday anchor — should names with a big pre-close move be skipped, on the idea that the
weekend drop is a spike unwinding rather than a dislocation?

**Declared before the query (in the session log, 2026-09-08 ~05:20 UTC):** H = events whose
underlying rose sharply into the anchor earn less. Split A: Thursday close → Friday close > +2%.
Split B: Wednesday close → Friday close > +3%. Verdict rule: a sign flip, or a gap > 150 bp with both
halves n ≥ 15, would justify a spec amendment; anything smaller is noise at this sample size.
Same bars, same 85-event primary ledger (2 events lack a Wed/Thu bar — first weekend after listing —
leaving 83), same 9 bp cost, funding included.

| Split | ran-up n | ran-up mean / median | rest n | rest mean / median | gap |
|---|---:|---:|---:|---:|---:|
| A: Thu→Fri > +2% | 14 | +123.9 / +57.5 bp | 69 | +144.8 / +110.8 bp | −21 bp |
| B: Wed→Fri > +3% | 19 | +137.4 / +114.6 bp | 64 | +142.5 / +109.6 bp | −5 bp |

De-clustered (weekend means, split B): ran-up weekends **+199.8 bp** (9) vs rest +70.6 bp (15) — the
weekends with run-up names did *better*, not worse. Correlation run-up vs net −0.18. Quartiles of the
two-day run-up: Q1 (fell −14…−5% into Friday) +349, Q2 +148, Q3 (−1…+2.5%) −68, Q4 (ran +2.8…+16%)
+136 — non-monotonic, noise-shaped. PAYP itself appears three times as a ran-up trigger (06-12, 06-26,
07-10): +141, +548, +341 bp.

**Result: H rejected under the pre-declared rule.** No sign flip; gaps of 5–21 bp against a 150 bp
bar. **No change to the live rule** — a run-up into the close is not a skip. Noted for a future,
separately pre-registered question only: Q1 (names that *fell* into Friday and kept falling) is the
strongest quartile at +349 bp; that is post-hoc and is not acted on.

**Independent recomputation (2026-09-08 09:15 UTC) — and what it exposed.** Two implementations from the
Binance public API, reconciled by a third pass: agree, one immaterial 0.5 bp funding-boundary
difference (a print stamped 16:00:00.001 sits 2 ms after the exit bar's close; excluded under the
literal rule). But they found **92 events over 20 weekends**, not 83 over 17: the database this test
ran on was missing three August weekends for 19 names (see A6). On the complete data the result is
the same direction and stronger: Split A ran-up **+204 bp** (n=17) vs rest +118; Split B ran-up
**+205 bp** (n=21) vs rest +113; medians 115/125 vs 105/96. Names that ran up into the anchor faded
*better*. H stays rejected; the numbers in the table above are from the incomplete set and are
superseded by these.

## Amendment A6 — 2026-09-08 09:15 UTC: the ledger was missing three August weekends; headline restated

**Found by:** the independent recomputation in A5. **Mechanism:** `binance_perp_kline` holds 1h bars
for the 27-name universe only up to **2026-07-31 23:00 UTC for 19 of the 27 symbols** (AAOI, AXTI,
CRCL, EWJ, EWY, INTC, JPM, LLY, MU, NOK, NVDA, OPENAI, PAYP, QCOM, SNDK, SPCX, TSM and two more) — a
one-off backfill written 2026-08-12 — while the daily refresh keeps only the ten old-universe names
(AAPL, AMZN, COIN, HOOD, META, MSTR, PLTR, QQQ, SPY, TSLA) current. The study's weekend range ran to
2026-08-21, so for the weekends of 07-31, 08-07, 08-14 and 08-21 the SQL's kline JOINs silently
dropped those 19 names. **Nine events were missing** (CRCL 07-31 −208; JPM 08-07 +98; AXTI 08-14
+1,439 and QCOM −37; AAOI −223, AXTI −543, OPENAI −221, PAYP +255, SPCX +44 on 08-21). Two events the
API run lacked (META, NVDA 2026-03-27, listed the day before — no Wednesday bar for the run-up test)
are valid ledger events and are kept.

**Complete ledger (PG ∪ API, 94 events), de-clustered:**

| Cut | events | weekends | mean net | median | SD | t | worst | as published (A-series) |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| all 27 names | 94 | **20** | **+143.9 bp** | +119.2 | 346.6 | **1.86** | −427.7 | +147.5, n=17, t=1.82 |
| excl. private (OPENAI, SPCX) | 87 | 20 | **+167.6 bp** | +140.6 | 355.5 | **2.11** | −427.7 | +175.5, n=17, t=2.10 |

The 83 events common to both sources agree to ≤ 0.5 bp, so the published computation was right on
the bars it had; it simply did not have August. The conclusion is unchanged — mean slightly lower,
three more weekends, t slightly higher. The live spec's headline is restated to these numbers.
**Fix, done 2026-09-08 09:20 UTC.** August 2026 backfilled for all 27 names from the monthly archives
(`KlineArchiveImportApplication -DklinePairs`, 12,648 rows); September to date and the ten core names'
missing 08-26 → 08-31 filled over REST by a new `PerpKlineRefreshApplication` (walks forward from each
symbol's latest bar; 6,230 rows), which `scripts/xvf-refresh.sh` now runs daily after the venue candles.
All 27 symbols verified current to the hour. **The study SQL re-run from PostgreSQL on the completed
table reproduces the restated headline exactly: n=20 weekends, 94 events, +143.9 bp, median +119.2,
SD 346.6, t=1.86, worst −427.9** — the same numbers the API union gave, so the database is again the
source of record.

## Live ledger (appended per traded weekend; the spec's "re-measure monthly" input)

| # | Friday | form | events | spec net (equal-weight) | basket P/L at 15k/3k sizing | recorded |
|---|---|---|---:|---:|---:|---|
| 21 | 2026-09-04 | shifted (Labor Day: Mon 20:00 UTC → Tue 11:00 NY) | 1 (PAYP, −206 bp at the bar) | **+603 bp** | +$180.9 | 2026-09-08 17:22 UTC |

Cumulative at spec sizing, weekends 1–20 (2026-01-30 → 08-21, complete data per A6): **+$2,305 =
+4.61% of the 50k account** (excl. private names +$2,545 = +5.09%); 13 of 20 traded weekends positive,
6 of 26 in-scope weekends sat out; average deployed $9,600, +1.20% per traded weekend on deployed
capital; worst weekend −$367 (2026-05-15), best +$625 (2026-06-05). With weekend 21: **+$2,486 = +4.97%**.
