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

## Amendment A7 — 2026-09-09 20:05 UTC: admitting new listings without a half-year of history (declared before any newcomer outcome is computed)

**Why now.** Binance classified 156 EQUITY perps on 2026-09-09 against ~140 at E1; 25 were listed after
E1's data cut (KO, RDDT on 2026-08-06; GDX, NET, VST, SHOP, LYTE on 08-17; SKUU, SKDD, RAM, DJT, MRNA on
08-25; TEM, MRK, IONQ, MARA, PDD on 08-28; NVDL, TSLL, DDOG, TEAM, MDB, ZS, GTLB on 09-02; GPRO on 09-03).
None is on the prop watchlist as posted 2026-08-25 (`PROP_CHALLENGE_RULES.md`), so by the live spec's
definition none belongs to the universe today. `PerpKlineRefreshApplication` now discovers every EQUITY
perp from `exchangeInfo` on each daily run and collects it from its own listing date, so the bars exist
from here on; BTC/ETH 1h bars are kept current on the same schedule (they had stopped on 2026-08-11).

**The problem stated honestly.** No name in the universe was ever admitted on its own outcomes: the 24
came from the watchlist, and the edge is a pooled property (94 events over 27 names is ~3.5 events per
name; no single name is significant). Demanding a half-year of per-name history from a newcomer would be
a stricter standard than any incumbent met, and per-name outcome selection at n≈3 is noise-fitting.
E1 (2026-08-30) is the constraint: the broad universe has no edge (+23 bp, t=0.22 against +241 for the
measured names on the same weekends), so "structurally similar" is not enough either. What E1's own
reading implies is a *characteristic*: the fade lives in crypto-adjacent, high-attention names that
crypto traders push around while the underlying is closed. That characteristic is observable from hourly
bars, and it accrues 48 observations per weekend per name rather than one.

**Features (declared; per symbol per weekend; all from `binance_perp_kline` 1h, fixed UTC windows):**
weekend bars = open_time Fri 20:00 → Sun 19:00 inclusive (48 bars); weekday bars = open_time Mon 00:00 →
Fri 19:00 of the same week (116 bars). Per-symbol value = median over its weekends; ≥ 3 weekends required.
- **F1 `wk_btc_corr`** — Pearson correlation of the perp's 47 hourly weekend log returns with BTCUSDT's.
- **F2 `wk_vol_ratio`** — mean hourly quote volume over weekend bars ÷ mean over the week's weekday bars.
- **F3 `wk_move_ratio`** — SD of hourly weekend log returns ÷ SD of the week's weekday hourly log returns.

**Tests (declared, run in this order; sample = weekends from 2026-04-03 to 2026-08-28, the pre-listed
holiday weekends excluded as in the frozen SQL, plus 2026-09-04 (Labor Day) by the same pre-listed rule):**
- **T1 separation:** feature distributions for the 25 measured names vs the E1 names with ≥ 3 weekends;
  report medians and the rank-sum AUC (P[random measured name > random E1 name]). Pass: AUC ≥ 0.75.
- **T2 within-E1 prediction (the test that matters):** E1 names split into terciles by each feature;
  the frozen event definition run per tercile; weekend-de-clustered mean net and t per tercile. Pass: top
  tercile mean net > 0 and exceeds the bottom tercile by ≥ 50 bp/event. Three features → Holm-corrected
  for the t of the top-minus-bottom difference; a feature must pass T1 and T2.
- **T3 newcomers:** F1–F3 for the 25 post-E1 listings over the weekends they have. **No outcome is
  computed for any newcomer in this amendment**, and the SQL that would do so is not run on them.
- **Kill rule declared now:** if no feature passes T1 and T2, the characteristic route is dead; newcomers
  then join only after ≥ 20 weekends of their own bars via a fresh pre-registered measurement.

**Admission rule for a newcomer (operational only if a feature passes):**
1. *Mechanical eligibility:* `underlyingType=EQUITY`; exchange-listed underlying (PREMARKET/private out,
   rule A2); not a leveraged/inverse/volatility ETP (daily-reset products; among the 25 that is SKUU,
   SKDD, RAM, NVDL, TSLL); not a crypto-underlying ETP (BITO-class); underlying trades US regular hours.
2. *Characteristic:* after ≥ 3 weekends of bars, the passing feature's per-name value is ≥ the 25th
   percentile of the measured 25's values AND ≥ the 75th percentile of the E1 set's values, both frozen
   from the T1 run. Below either → not eligible; re-checked monthly, never mid-month.
3. *Shadow period, 4 weekends:* the frozen SQL is run on the name weekly and written to a shadow ledger
   that is **not traded and not used for selection** — promotion at the end of the 4 weekends is
   automatic if (a) bars are complete, (b) the decision-bar quote volume clears the spec's liquidity floor
   on ≥ 2 of the 4 weekends, (c) the Sunday name-check routine finds no pending corporate action.
   Shadow outcomes are recorded and ignored, by declaration, because four weekends of outcomes are noise.
4. *Cohort accounting:* promoted names are `cohort B` in the ledger; the 24 are `cohort A`. B is sized
   like any name but capped at one third of a weekend's basket notional until B has ≥ 20 events. B is
   suspended in full if its de-clustered mean net is < 0 at ≥ 20 events, or t < −1.0 at ≥ 10 events, at
   any monthly re-measure; A is never re-sized on B's results. B is the mechanism's out-of-sample test.

**Contamination disclosed.** E1's outcomes are known while the features are chosen. The features come
from the mechanism sentence written into E1 on 2026-08-30, before any per-name feature value existed;
the thresholds come from the measured 25's own distribution, not from tuning on E1 outcomes; T2 is a
test, and the newcomers are the only clean out-of-sample set. If T2 passes narrowly, that is what it is.

**Run 2026-09-09 20:12 UTC** (`scripts/analysis-fade-a7-newcomers.py full`; self-check first reproduced A6 exactly:
orig-27 n=20, 94 events, +143.9, t=1.86; measured-25 +167.6, t=2.11). Bars: 112,087 rows backfilled across
159 symbols, every EQUITY perp and BTC/ETH current to 2026-09-09 19:00 UTC. Sample: 19 Fridays 2026-04-10 →
2026-08-28; 25 measured names and 95 E1 names all have ≥ 3 weekends.

**T1 — separation (pass = AUC ≥ 0.75):**

| Feature | measured-25 median | E1 median | AUC | verdict |
|---|---:|---:|---:|---|
| F1 weekend BTC correlation | 0.205 | 0.130 | **0.779** | pass |
| F2 weekend/weekday volume ratio | 0.118 | 0.152 | 0.399 | fail (measured names are *less* weekend-heavy: their weekday volume is large) |
| F3 weekend/weekday move ratio | 0.238 | 0.265 | 0.388 | fail |

Sanity check on F1: the incumbents rank MSTR 0.742, COIN 0.657, CRCL 0.586, HOOD 0.454 at the top and
AXTI −0.043, LLY 0.079, JPM 0.117 at the bottom — the crypto-native names are where the feature says they
should be.

**E1 extended through 2026-08-28 (the monthly re-measure E1 asked for):** all 95 E1 names, 16 weekends,
192 events, mean **+38.0**, median +69.5, **t=0.50**, worst −865.7; measured-25 on the same weekends:
14 weekends, 67 events, **+207.3**, t=2.1, worst −286.8. E1's decision stands.

**T2 — within-E1 terciles by feature (pass = top mean > 0 and top − bottom ≥ 50 bp/event):**

| Feature | bottom (n wk / events / mean / t) | middle | top | top − bottom | Welch t (p) | Holm p |
|---|---|---|---|---:|---|---:|
| **F1** | 11 / 53 / **+101.4** / 1.51 | 13 / 72 / +12.6 / 0.14 | 15 / 67 / **+165.5** / 2.81 (worst −115) | **+64.1** | 0.72 (0.48) | 1.00 |
| F2 | 11 / 54 / +122.5 / 0.89 | 14 / 69 / +29.4 / 0.43 | 12 / 69 / +108.7 / 4.12 | −13.8 | −0.10 (0.92) | 0.92 |
| F3 | 12 / 55 / +63.4 / 0.48 | 13 / 61 / +9.3 / 0.13 | 12 / 76 / +98.2 / 4.08 | +34.8 | 0.26 (0.80) | 1.00 |

F1 tercile cuts 0.089 / 0.159. Top tercile (32 names, for the record, NOT admitted): CRDO, RKLB, INTW,
BX, MRVL, MUU, DELL, IBM, CRM, BNC, SMCI, WDC, QNTX, BE, IREN, COHR, LRCX, CBRS, MSFT, DRAM, BABA, NFLX,
CRWV, BOT, RIVN, AMD, ARM, GOOGL, ALAB, ASTS, USAR, BMNR. The four E1 disasters (LITE −866, APP −815,
BOT −639, GOOGL −515) sit: APP bottom (0.019); LITE middle (0.156); BOT (0.244) and GOOGL (0.269) top — two of the four
are above the tercile cut and above the 0.191 bar. The feature does not screen out single-name news.

**Verdict, stated plainly.** F1 passes T1 and T2 *as declared*: AUC 0.78 and a top-minus-bottom gap of
+64 bp against a 50 bp bar. The gap is **not distinguishable from zero** (Welch t 0.72, Holm p 1.0), the
terciles are not monotone (the middle is the worst), and the top tercile's t=2.81 is the largest of nine
sub-samples examined (3 features × 3 terciles) and must not be read as an independent confirmation. Had
T2 required Holm p < 0.10 — which the declaration did not — F1 would have failed. The declared rule stands
because it was declared; the weakness is carried by the structure built for it: shadow first, cohort B
capped at one third of the basket, suspended on its own numbers, cohort A untouched. The prior for cohort
B is "probably a smaller edge than A, possibly none".

**Frozen threshold (from T1, the declaration's formula):** eligible iff **F1 ≥ 0.191** (max of measured-25
p25 = 0.170 and E1 p75 = 0.191), computed over ≥ 3 weekends. Ten of the 25 incumbents sit *below* it
(AXTI, LLY, JPM, SNDK, PAYP, QCOM, EWJ, MU, AAPL, INTC): the newcomer bar is stricter than the incumbents'
own admission, by design, and it removes nobody (declared: A is never re-sized on B's results).

**E1 names above the threshold (24 of 95) are not admitted by this amendment**: their feature and their
outcomes were computed on the same weekends, so selecting them now is outcome-contaminated. Declared
extension (not requested, kept for consistency): an E1 name may enter the newcomer route only on its F1
over weekends **from 2026-09-11 onward**, ≥ 3 weekends, same threshold, same shadow — earliest check at the
October re-measure.

**T3 — newcomers (features only; weekends 2026-08-07 → 2026-09-04; 09-04 is included here because the
feature window is Fri 20:00 → Sun 19:00 UTC and does not depend on the US session):**

| Symbol | Issuer (Nasdaq quote page, 2026-09-09) | mechanical | weekends | F1 | status |
|---|---|---|---:|---:|---|
| RDDT | Reddit Inc | ok | 4 | **0.233** | **eligible → shadow from weekend 2026-09-11** |
| VST | Vistra Corp | ok | 3 | 0.182 | below 0.191; re-check October |
| LYTE | Roundhill Photonics & Optics ETF | ok | 3 | 0.114 | no |
| GDX | VanEck Gold Miners ETF | ok | 3 | 0.026 | no |
| NET | Cloudflare | ok | 3 | −0.026 | no |
| KO | Coca-Cola | ok | 4 | −0.030 | no |
| SHOP | Shopify | ok | 3 | −0.087 | no |
| MRNA, TEM, MRK, IONQ, MARA, PDD, DJT | Moderna, Tempus AI, Merck, IonQ, MARA Holdings, PDD, Trump Media | ok | 1 | 0.151 / 0.164 / 0.057 / 0.062 / 0.073 / 0.088 / 0.081 | one weekend, not judgeable; October |
| DDOG, TEAM, MDB, ZS, GTLB, GPRO | Datadog, Atlassian, MongoDB, Zscaler, GitLab, GoPro | ok | 0 | — | October |
| SKUU, SKDD, RAM, NVDL, TSLL | 2x daily ETPs (GraniteShares/Roundhill/Direxion) | **excluded** | — | — | daily-reset products |

RDDT per weekend: 08-14 −0.203, 08-21 0.358, 08-28 0.234, 09-04 0.233 (08-07 lacks the 100 weekday bars).
MARA — a bitcoin miner, the name the mechanism story would nominate first — reads 0.073 on its one weekend;
one weekend is noise, October decides.

**Disclosure:** the shadow mode's smoke test at 2026-09-09 20:12 UTC printed RDDT's 2026-08-28 row (weekend +140.5 bp, not triggered, so not an event; decision-bar quote volume $1,581). It was seen before the shadow period began; it informs nothing above, but the declaration said no newcomer outcome would be computed and one was.

**Operational from here.** RDDT: shadow ledger for weekends 2026-09-11, 09-18, 09-25, 10-02 (the frozen
SQL run weekly via `scripts/analysis-fade-a7-newcomers.py shadow RDDTUSDT <friday>`, written to the shadow
ledger below, not traded, not selected on). Promotion check 2026-10-05: bars complete, decision-bar quote
volume ≥ the spec's floor on ≥ 2 of 4, no pending corporate action. **Trading cohort B requires a live-spec
filing by the user; nothing in this amendment changes what is traded.** All other newcomers: re-check at
the October re-measure on ≥ 3 weekends.


## Live ledger (appended per traded weekend; the spec's "re-measure monthly" input)

| # | Friday | form | events | spec net (equal-weight) | basket P/L at 15k/3k sizing | recorded |
|---|---|---|---:|---:|---:|---|
| 21 | 2026-09-04 | shifted (Labor Day: Mon 20:00 UTC → Tue 11:00 NY) | 1 (PAYP, −206 bp at the bar) | **+603 bp** | +$180.9 | 2026-09-08 17:22 UTC |

Cumulative at spec sizing, weekends 1–20 (2026-01-30 → 08-21, complete data per A6): **+$2,305 =
+4.61% of the 50k account** (excl. private names +$2,545 = +5.09%); 13 of 20 traded weekends positive,
6 of 26 in-scope weekends sat out; average deployed $9,600, +1.20% per traded weekend on deployed
capital; worst weekend −$367 (2026-05-15), best +$625 (2026-06-05). With weekend 21: **+$2,486 = +4.97%**.

### A7 critic pass — 2026-09-09 20:29 UTC: the admission rule is withdrawn the day it was declared

Three independent adversarial reviews (statistics, mechanism, operations; each re-ran the script and
reproduced every number) were run on the amendment as written above. Their objections, the checks they
asked for, and the decision:

**1. The declared T2 bar had no error control.** With weekend SDs of 223 (bottom, n=11) and 228 bp (top,
n=15), the standard error of the top-minus-bottom gap is 89 bp, so a gap ≥ 50 bp had about a 29% chance
under no effect for one feature and about 64% for at least one of three. "Top mean > 0" is nearly
automatic when the pooled E1 mean is +38. The Holm correction was reported but never consulted by the
pass rule. The operative threshold (0.191, 24 E1 names) was never tested; the tercile cut was 0.159 (32
names). "Passed as declared" was true and uninformative.

**2. The two tests that speak to the mechanism, run at the critics' request (20:29 UTC, `diagnostics` mode):**

| Split at F1 = 0.191 | above | below | gap | Welch t |
|---|---|---|---:|---:|
| E1 names, 2026-04-10 → 08-28 | 24 names, 14 wk, 53 ev, **+106.6**, t 1.34 | 71 names, 13 wk, 139 ev, +30.8, t 0.35 | +75.7 | 0.64 (one-sided p 0.27) |
| **Cohort A, A6 sample** | 15 names, 15 wk, 57 ev, **+75.5**, t 0.94 | 10 names, 15 wk, 30 ev, **+208.0**, t 2.33 | **−132.4** | −1.10 |

Inside the measured universe the *low*-correlation names (EWJ, AAPL, INTC, MU, LLY, JPM, QCOM, PAYP, SNDK,
AXTI) earned the most. Whatever the fade is a property of, F1 does not rank it, in either population.

**3. The feature is not what the mechanism sentence claimed.** F1 computed over weekday hours separates
measured from E1 almost as well (AUC 0.696, medians 0.317 vs 0.236) as over weekend hours (0.779); plain
weekend quote volume per hour separates better (AUC 0.819, medians $98,977 vs $12,326 per hour). The
co-movement is the underlying's BTC exposure and the name's liquidity in every session, not weekend
pushing. F2's AUC of 0.40 said the same thing: the measured names carry *less* of their volume on weekends.
Within E1, volume terciles do not order outcomes either (+70.9 / +119.7 / +92.7 bp), and a per-weekend
rank-normalised F1 gives +117.4 / +29.1 / +133.1 — non-monotone again. On the Binance weekend index: it is
a vendor blend (dxFeed, Kaiko, Pyth Pro, ~1% Binance perp) that is stale while the underlying is closed,
so weekend perp moves are Binance participants' and mark-vs-index is what funding prices; that part of the
sentence stands.

**4. Three to four weekends of F1 cannot classify a name.** The cross-sectional median F1 across all names
swings from 0.03 (07-17) to 0.47 (06-05) by weekend, and the share above 0.191 from 13% to 94%; the median
within-name weekly SD is 0.195, larger than the between-name spread. Split-half (first 4 weekends vs the
rest, names with ≥ 8) agrees on eligibility at 0.191 for 54 of 100 names. RDDT's four weekends rank 0.04,
0.48, 0.68, 0.48 among the incumbents on the same weekends: it cleared the bar because three of its
weekends were high-correlation weekends for everyone.

**5. Operational defects.** (a) The promotion gate cited "the spec's liquidity floor"; the live spec has
none. RDDT's Sunday decision bars were $2,963 / $2,711 / $5,869 / $1,581 / $720 (08-09 → 09-06) against an
incumbent median of ~$65k and p25 of ~$19k (Gate study); any floor that admits RDDT is no floor. Four
incumbents themselves printed under $2,500 on recent Sundays (JPM $585, LLY $1,000, NOK $1,083, PAYP
$2,480 on 08-30) — cohort A has no liquidity rule, which is a separate finding for the user. (b) RDDT is
not on the prop platform's 36-symbol list, so "cohort B" could only ever trade on the own-capital book,
where the 15,000/3,000/one-third figures are undefined. (c) The script's outcome function was correct only
for Fridays up to 2026-10-30 (fixed 20:29 UTC: anchor, entry and exit bars now derive from
America/New_York and the NYSE closure list, with the live spec's shifted form on holiday weekends;
self-check asserts a winter, a summer and a Labor Day weekend and reproduces A6). (d) The kill rule could
not fire inside a year at 0.13–0.20 events per name-weekend, and the one-third cap never binds for one
name. (e) The ETP exclusion reached the right answer for the wrong reason: no daily reset occurs inside a
Friday-close-to-Monday-11:00 hold; the reasons that apply are the halved trigger scale and duplicate
exposure (TSLL on TSLA, NVDL on NVDA). ADRs and foreign-market ETFs with a US regular session are eligible
in principle, as TSM, PAYP, NOK, EWJ, EWY already are. (f) MARA's one-weekend F1 (0.073) rests on a decision
bar with one trade. (g) The E1 membership and thresholds were re-derived from a live `exchangeInfo` call;
they are now constants in the script, and the 20:12 UTC per-name values are in
`data/fade-a7-features-2026-09-09.json`. **Correction 2026-09-12 07:51 UTC:** that claim of "tracked" was wrong when written — `.gitignore` excludes `data/` wholesale and nothing in it had ever been committed. The file is force-added to git as part of the A8 pre-registration commit.

**(h) Found while fixing (c), disclosed 2026-09-09 20:31 UTC: the pre-listed holiday exclusion missed Juneteenth.** The written
rule excludes "weekends adjacent to full US market closures"; the enumerated list (MLK, Presidents, Good Friday,
Memorial, Independence) omitted **Friday 2026-06-19**, a full NYSE closure, so A6, E1 and A7 all kept that weekend
with a closed-market Friday bar as anchor. Effect on the headline: **none** — no name in the 27 was ≤ −50 bp from
that stale bar, so the ledger's 20 weekends / 94 events / +143.9 / t 1.86 are identical with the weekend removed.
Effect on the E1 extension: 16 → 15 weekends, 192 → 183 events, +38.0 → **+47.7 bp, t 0.50 → 0.6**; decision
unchanged. For the record only: under the live spec's shifted form (anchor = Thursday 06-18 close) the weekend
would have produced five events in the 27 — EWJ +181, AAPL +141, AMZN −367, NVDA −17, SPCX −755 bp — which the
live rule would have traded; the ledger starts 2026-09-04 and is unaffected. The script now derives the
closure-adjacent list from the NYSE closure list (`hol_by_rule`) for every future cut, and keeps the enumerated
list only to reproduce the published numbers.

**Decision (2026-09-09 20:29 UTC).** The A7 admission rule (items 1–4 of "Admission rule for a newcomer") is
**withdrawn**: the kill clause declared at 20:05 UTC applies — no feature is supported, so the
characteristic route is dead. RDDT's shadow start on 2026-09-11 is withdrawn; the shadow ledger below is
closed with no rows. What stands: the daily collection of every EQUITY perp; the mechanical eligibility
list (restated per 5e); the E1 extension through 08-28 (+38.0, t 0.50; decision unchanged); the finding
that cohort A has no liquidity rule. The withdrawal is post-hoc in the conservative direction and is
disclosed as such.

**What replaces it — the pooled newcomer cohort cut (declared 2026-09-09 20:29 UTC, before any newcomer outcome
beyond the disclosed 08-28 RDDT row exists).** Per-name history is not, and never was, the criterion: the
24 were admitted as a block and measured as a block. New listings are treated the same way. Cohort N1 =
every EQUITY perp listed after E1's data cut and before 2026-09-04 that passes mechanical eligibility (KO,
RDDT, GDX, NET, VST, SHOP, LYTE, DJT, MRNA, TEM, MRK, IONQ, MARA, PDD, DDOG, TEAM, MDB, ZS, GTLB, GPRO —
20 names; SKUU, SKDD, RAM, NVDL, TSLL excluded). It is measured **once**, on the frozen definition, when
every name in it has ≥ 20 non-holiday weekends of bars (the 2026-09-02 group reaches that on the weekend
of 2027-02-12; run at the February 2027 re-measure), against the measured-25 on the same weekends as the
control, exactly as E1 was. **Admission of N1 as a block iff its de-clustered mean net ≥ +100 bp with
t ≥ 1.5 and its worst weekend ≥ −500 bp; otherwise it stays out and the next cohort (listings from
2026-09-04 on) is measured the same way when it qualifies.** No name enters on its own outcomes, no name
enters early, and the cohort's sizing on admission is the spec's per-name rule with no separate cap.
Until February 2027 nothing changes in what is traded. Names listed on the prop platform meanwhile do not
join before the cut either.

## Amendment A8 — declared 2026-09-12 07:37 UTC, revised 2026-09-12 07:50 UTC after adversarial review: the newcomer cohort becomes a group-sequential test (supersedes A7's single February cut)

**Why.** The user's observation: an edge is worth most when it is new, and the cost of waiting for a
conventional sample is that the edge is crowded by the time the sample exists. A7 answered the newcomer
question with one measurement in February 2027 and nothing before it. The fix is not a lower bar. A test looked
at on a declared schedule, with boundaries that spend the *same* total error budget across the looks, holds the
same false-positive rate as the single test while letting a strong cohort be recognised months earlier and a
dead one be dropped months earlier. The standing rules are `IDEA_TESTING_PROTOCOL.md`; this is their first
application.

**Revision history, stated first because it is load-bearing.** Declared 07:37 UTC with the window opening
2026-09-11. Three adversarial reviews then found, among other things, that the 2026-09-11 anchor bar had closed
at 20:00 UTC on 09-11, **before** the declaration, so "fully prospective" was not literally true; and that the
null had been calibrated on the frozen non-shifted definition with holiday weekends excluded, which is not the
definition this amendment declares. Both are corrected here at 2026-09-12 07:50 UTC: the window moves forward one week to
**2026-09-18**, whose anchor bar had not yet formed at either timestamp, and the null is rebuilt on the live
shifted form with holiday weekends included. **No observation existed in either window at either timestamp**,
so the revision is itself fully prospective; it costs one calendar weekend and makes the claim exact. The
boundaries did not move — the rebuilt null gives a lower error rate, and by protocol §5 a recomputation may only
tighten, never loosen.

**Three corrections to A7, found while building this.**
1. A7 said the cohort reaches 20 weekends "on the weekend of 2027-02-12". Counting non-holiday weekends from
   2026-09-11, the twentieth is **2027-02-19**; 02-12 is closure-adjacent (Presidents Day).
2. More seriously, A7 conflated **calendar weekends of bars** with **observations**. The statistic is computed on
   de-clustered weekends, and a weekend in which no cohort name triggers carries no information. Measured on
   3,000 random 20-name subsets of the 95-name E1 population on this amendment's own definition: a 20-name
   cohort yields a usable weekend on **0.588** of calendar weekends. A7's February test would have run on about
   **12 observations, not 20**, and its t ≥ 1.5 bar was being applied to a smaller sample than the text implied.
3. A7 excluded holiday weekends, inheriting the study convention. The live rule **trades** them in shifted form
   (standing decision, 2026-09-06). A prospective test of what would be traded must include them, so A8 counts
   every weekend on the live shifted form, making the test's definition identical to the traded rule.

**The test.**

| Item | Declaration |
|---|---|
| Population | Cohort N1: 20 names listed 2026-08-06 → 09-03 (KO, RDDT, GDX, NET, VST, SHOP, LYTE, DJT, MRNA, TEM, MRK, IONQ, MARA, PDD, DDOG, TEAM, MDB, ZS, GTLB, GPRO). Frozen. SKUU, SKDD, RAM, NVDL, TSLL excluded as leveraged ETPs (halved trigger scale, duplicate exposure). Later listings are the next cohort. |
| Window | Weekends from **2026-09-18** inclusive. |
| Definition | The frozen event definition in **live shifted form**: anchor = last US session close (13:00 NY on an early-close day), entry = 19:00 UTC bar the evening before the next session, exit = 10:00 NY bar of that session, trigger ≤ −50 bp, cost 9 bp, funding in (entry, exit]. Identical to `WeekendFadeMonitorApplication`. |
| Information metric | **m = usable weekends** (weekends with ≥ 1 triggered name). The weekend observation is the equal-weight mean net across that weekend's triggered names. |
| Looks | m = **6, 9, 12**. No look at any other m. A missed look is evaluated on its own prefix `series[:k]` at its own boundary, never on the full series at a stale boundary. |
| Boundaries | **t ≥ 2.55 at m=6, t ≥ 2.08 at m=9, t ≥ 1.80 at m=12** (O'Brien-Fleming shape, C = 1.80). |
| Economic bars | Admission additionally requires **both** the equal-weight mean ≥ +100 bp **and** the event-weighted mean ≥ +100 bp. The second is the return per unit of capital deployed, which is what the money earns: a weekend with eight triggers deploys eight times the notional of a weekend with one, and on E1 the two statistics differ by more than 50 bp for a large minority of cohorts (+38.0 equal-weight against +141.5 event-weighted on E1 itself). |
| Futility | Stop and declare the cohort dead if **mean + 1.282 × SE < +100 bp** at a scheduled look. Checked only at a scheduled look, never between. |
| Standing condition | A weekend worse than **−500 bp** suspends the cohort whenever it occurs, before or after admission, and forces a re-measure. Deliberately **not** part of the crossing rule: a "worst so far" bar is easier to satisfy with fewer observations and would reward stopping early. |
| Control | At each look the script also reports cohort A's de-clustered mean over the same weekends and the N1-minus-A difference. **Context only, not part of the crossing rule**, so the error rate is unchanged. A7 had promised a same-period control and the sequential rule dropped it; this reinstates it as a reported statistic. A crossing with A far above N1 is to be read as a regime in which the whole tokenized-perp complex faded well, not as evidence about N1 specifically. |
| Attrition | A name delisted or with bars ending mid-test contributes the weekends it has; the ledger records the missing-name count per weekend. The population is never replaced or topped up. If fewer than 15 of the 20 names are still listed at a look, that is disclosed with the result. |
| Backstop | If m = 12 is not reached by the weekend of **2027-04-30**, the final analysis runs then at whatever m ≥ 8 exists, at boundary C/√(m/12) — which is stricter in error terms, not an extra look. If m < 8 even then, the cohort is untestable and rolls into the next one. |
| Not crossed at m = 12 | Not admitted, test closed. N1 may only be re-declared as part of a fresh cohort with a fresh window after 2027-06-30. No extensions. |
| On admission | **The size is not yet declared, and a crossing does not authorise a trade until it is.** N1 names are not on the prop platform's 36-symbol list, so an admitted cohort could only trade the own-capital Binance book, whose Plan S sizing was drafted for $10k and never filed, against roughly $5.1k of actual equity. This deliberately leaves protocol §1 item 9 unsatisfied; the deadline to fill it is the first look, expected around 2026-11-20. |

**Calibration** (`python3 scripts/analysis-sequential-test.py boundaries`). The null is the centred empirical
distribution of weekend means as a 20-name cohort experiences them, built by resampling random 20-name subsets
of the E1 population **on this amendment's own definition** (live shifted form, holidays included): SD **276 bp**,
left-skewed, 1st percentile −973, 99th +779. C was chosen so the family-wise one-sided error rate stays at or
below 0.075 under the **worst** of three nulls:

| Null | family-wise one-sided alpha |
|---|---:|
| Empirical, 20-name cohorts | 0.0579 |
| Normal, same SD | 0.0727 |
| t(4), same SD | 0.0700 |
| Full rule, empirical (economic bars + futility stopping) | 0.0507 |
| *Reference: A7's single test, t ≥ 1.5 at m = 12* | *0.077 – 0.082* |

The first three rows are 50,000-path Monte Carlo estimates with a standard error near 0.0012, so "at or below
0.075" means within Monte Carlo error of it, and they are **efficacy-only upper bounds**: they apply neither
economic bar nor futility stopping, both of which only remove crossings. The full rule comes in at 0.0507. The sequential test is therefore
slightly **more conservative** than the single test it replaces. Calibrating on the empirical null alone would
have understated the rate by about a fifth, which is why the protocol requires the worst of the three.

**What the boundaries demand, stated honestly.** At the resampled cohort SD of 276 bp the boundaries correspond
to means of 287 / 191 / 143 bp. But the statistic uses the **sample** SD of m observations, and at m = 6
that is badly downward-biased and, conditional on a crossing, selected to be small: the unconditional sample SD of six
draws has a median of 224 bp, and conditional on a null m = 6 crossing it has a median of **142 bp** with a
crossing mean of **184 bp**, not 287. So the +100 bp bar is **not** inert at the first look — it removes about
**10%** of null crossings and is the operative
protection against a quiet start. The earlier claim that "the economic bar never binds" was wrong and is
withdrawn. A related fact the reader should have: **a large minority of cohorts are decided at m = 6**, mostly
by futility, so in practice this is substantially a six-observation test.

**Power** (`python3 scripts/analysis-sequential-test.py power`; empirical null shifted to a true weekend mean,
economic bar applied, futility active):

| True mean | Admitted, total | at m=6 | at m=9 | at m=12 | Killed for futility | A7's single test |
|---:|---:|---:|---:|---:|---:|---:|
| 0 bp | 0.050 | 0.015 | 0.015 | 0.020 | 0.660 | 0.062 |
| +100 | 0.398 | 0.113 | 0.146 | 0.138 | 0.219 | 0.414 |
| +150 | 0.650 | 0.236 | 0.246 | 0.168 | 0.081 | 0.666 |
| +200 | 0.823 | 0.401 | 0.287 | 0.135 | 0.022 | 0.830 |
| +300 | 0.962 | 0.698 | 0.188 | 0.076 | 0.001 | 0.972 |

Read honestly: the peeking is close to free (0.650 against 0.666 at a true +150) and what it buys is timing —
at a true +200 the cohort is admitted at the first look, expected around **2026-11-20**, 40% of the time. It does
**not** fix the underlying weakness: at an edge the size of the fade's own headline (+144 to +168 bp) this test
still misses a real cohort about a third of the time, because 12 observations of a 276 bp distribution is not
much. A failure to cross is **not** evidence of no edge, and this is written down in advance so a null result is
not over-read later. A truly worthless cohort is killed at a scheduled look 66% of the time; a cohort at a
true +100 is killed 22% of the time and one at a true +150 8% of the time, which is the declared cost of
stopping early.

**Expected timing** at 0.588 usable weekends per calendar weekend: m=6 around **2026-11-20**, m=9 around
**2026-12-25**, m=12 around **2027-01-29**. Expectations, not commitments: the looks are triggered by m, never
by date.

**Error budget across cohorts.** This amendment controls error across the three looks **within** N1 and controls
nothing across the stream of cohorts the design creates. Four cohorts at this budget carry roughly a one-in-four
chance of at least one false admission somewhere. Per protocol §5: only one test runs at full budget at a time,
an admission is provisional through a confirmation period, and no second cohort is admitted while an earlier
admitted cohort is still inside it.

**Disclosures.** (a) All 20 names have bars before the window; the seven listed 2026-08-06/17 have about five
earlier calendar weekends and the rest two, and one N1 outcome (RDDT, weekend 2026-08-28) was computed and
disclosed in A7's smoke test. The window uses none of it. (b) The null is calibrated from E1, a different
population, because N1 has no outcomes; if N1's weekend distribution proves materially wider or narrower the
boundaries remain valid in t-units but the power and timing tables do not. (c) The null, the 276 bp SD and the
0.588 rate are measured over 2026-04-03 → 08-28, which contains three shifted weekends out of 22; roughly four
of the nineteen weekends to the expected m=12 look are the shifted form, and the 2026-11-27 early-close anchor
form has no measured precedent at all, so timing and power carry an unquantified error on that share. (d) The
protocol and this amendment were written after A7's rule failed, so the motivation is post-hoc; the test is
prospective. (e) Nothing here changes what is traded today, and a crossing changes nothing either until a size
is filed.

## Shadow ledger — cohort B candidates (A7; closed 2026-09-09 20:29 UTC, no rows — reopened only by a new amendment)

Frozen definition, same as the live ledger. A row per candidate per weekend, from the weekend after
eligibility. Opened 2026-09-09 20:12 UTC.

| Weekend (Fri) | Symbol | wknd bp | triggered | price bp | funding bp | net bp | decision-bar quote vol | bars complete |
|---|---|---:|---|---:|---:|---:|---:|---|
