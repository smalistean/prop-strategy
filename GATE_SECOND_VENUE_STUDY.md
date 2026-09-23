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

## Addendum (2026-09-23 08:27 UTC) — re-measured on today's listings: seven venues, four decision bars, fill at $50k / $100k / $200k

The 2026-09-09 aggregate was measured on June–August bars, before Gate and OKX relisted the deep names, before
MEXC listed PAYP, and before Hyperliquid's HIP-3 dexes carried most of the universe. Re-measured 2026-09-23
08:14 UTC on public endpoints: inventory of every stock/ETF perpetual on Bybit (250), OKX (186), Gate (409),
MEXC (402), Bitget (320), Hyperliquid (129), Kraken (23); then the single 1h decision bar (open 19:00 UTC the
evening before the next US session) for the 24 fade names on each venue, four weekends: 2026-08-30, 09-07
(Labor Day, Monday bar), 09-13, 09-20. Binance bars from `binance_perp_kline`. Raw data:
`research/venue-decision-bars-2026-09-23.json` (145 symbol series, per-venue endpoints and caveats inside).
Bitstamp has no perpetuals and was not queried.

**Coverage.** Every one of the 24 is now on at least four other venues; MEXC lists all 24 including PAYP. Of the
270 tickers that exist elsewhere and not on Binance, the two large ones are SPX and NDX index perps ($266M and
$233M/day, mostly Hyperliquid), which duplicate SPY/QQQ exposure; 146 of the 270 trade under $100k/day. No
candidate is admissible without its own history (A8 route).

**Decision-bar volume, median of the four bars, the thin names (USD):**

| name | Binance | all other venues | ratio | largest others |
|---|---:|---:|---:|---|
| PAYP | 1,214 | 4,082 | 3.4× | MEXC 4,082 (only venue) |
| EWJ | 2,376 | 12,895 | 5.4× | Gate 7,513, MEXC 4,185 |
| JPM | 2,826 | 13,118 | 4.6× | Gate 8,666, MEXC 4,174 |
| LLY | 5,762 | 14,606 | 2.5× | Gate 6,241, MEXC 4,160, HL 2,273 |
| NOK | 10,664 | 18,772 | 1.8× | Gate 13,070, MEXC 4,077 |
| TSM | 14,630 | 32,507 | 2.2× | OKX 12,727, Gate 7,833, MEXC 5,089 |
| QCOM | 28,818 | 41,481 | 1.4× | OKX 15,857, Gate 14,522 |
| AXTI | 35,224 | 65,752 | 1.9× | Gate 27,079, OKX 18,202, Bitget 10,909 |

The other venues now carry more decision-bar volume than Binance on every thin name — but the absolute
numbers stay small: EWJ's 5.4× is $12.9k, a $1.3k cap.

**Fill under Plan S (basket 75%, cap 20%/name, ≤ 10% of each venue's own bar), Binance-only → all seven venues:**

| book | 09-07 (1 name, PAYP) | 09-13 (20 names) | 09-20 (6 names) | three traded weekends | all-24 case, four-bar mean |
|---|---|---|---|---|---|
| $50k | 1% → 5% | 89% → 97% | 61% → 85% | **66% → 81%** | 85% → 97% |
| $100k | 1% → 2% | 82% → 93% | 55% → 69% | **61% → 72%** | 78% → 91% |
| $200k | 0% → 1% | 72% → 84% | 47% → 60% | **53% → 64%** | 71% → 85% |

At $100k with Gate only: 61% → 64% on the traded pattern; Gate + MEXC: 61% → 66%. On the all-24 case the
recovered dollars come 52% from Gate, 23% MEXC, 11% Bitget, 8% Hyperliquid, 3% OKX, 2% Bybit, 0% Kraken —
but on the weekends actually traded, AXTI's and TSM's headroom is filled by OKX and Bitget, so two venues
capture only about half of what seven do.

**Reading.**
1. At $100k, aggregation lifts deployed capital by about 18% relative (61% → 72% of allocation on the traded
   pattern; 78% → 91% on all-24). That is the same order as the 2026-09-09 estimate (14% → 17% annualised);
   the new listings did not change the conclusion, they confirmed it on fresh bars.
2. The single-thin-name weekend is not fixable by any venue: PAYP alone fills 1% → 2% at $100k. That is the
   20% per-name cap meeting a name that trades $1–5k per bar across all venues combined.
3. Getting the full benefit needs five or six venues, not one or two. The 2026-09-09 operational objection
   (4–6 accounts, simultaneous manual exits at 11:00 NY, per-venue tracking studies, per-venue announcement
   reading) is unchanged. Tracking is still measured only for Gate (corr 0.98) and Bybit; MEXC, OKX, Bitget,
   Hyperliquid and Kraken are assumed to track. Gate force-settled 11 of these names' predecessors in August.
4. **The standing re-open condition — "when the per-name cap is routinely bound by Binance's bar on names
   other than PAYP" — is met at $100k** (AXTI, TSM, NOK, JPM, EWJ, QCOM, LLY bind on every bar at six-name
   sizing) **and is not met at $5k** (only EWJ and PAYP bind, and only in small baskets). The decision
   therefore stays *not built* for the current book and is *open* for a $100k book, where the first step
   would be a Gate second account (the one venue with a tracking study) and a pre-registered tracking
   measurement for MEXC and OKX before either is added.
5. The lever nobody has measured is the 10%-of-bar cap itself. It is a guess, not a slippage study; at $100k
   a pre-registered measurement of realised fill cost at 15% and 20% of bar on the deep names is worth more
   than any single venue, because it applies to all 24 names at once.

Four bars is the minimum for a median and thin-name bars vary tenfold weekend to weekend (JPM $585–8,211,
LLY $1,000–23,082 on Binance alone); treat every ratio above as a rough estimate, not a measurement.

## Addendum (2026-09-23 10:47 UTC) — does a venue's perp carry Binance's weekend history? Seven venues, every weekend since listing

**Question (user, 2026-09-23 09:5x UTC):** a symbol that is new on another CEX — can it be treated as having Binance's history,
because arbitrage keeps the prices together? If so, does the transfer run only Binance → others? What share of the aggregated
volume should a venue have to be eligible? And: is it better to spread the money over several venues?

**What was measured.** Hourly candles for the 24 names from each venue's public API, from listing to 2026-09-22 (one agent per
venue; an independent agent re-fetched 20–28 cells per venue, all exact; Gate's 23 pre-listing placeholder rows were re-fetched
and replaced). Binance from `binance_perp_kline`. For every (venue, name, weekend) where both sides have the three fade bars
(anchor = last US close bar, entry = the 19:00 UTC bar the evening before the next US session, exit = the 10:00 NY bar; live
shifted form, holiday weekends included): **entry gap** = venue deviation from anchor at the entry close minus Binance's (bp);
**trigger agreement** on Binance-triggered events (≤ −50 bp); **venue-only triggers** (venue ≤ −50 bp, Binance not);
**return** = entry→exit price return, correlation and gap; **funding** over the hold where the venue publishes it.
Rows and both summaries: `research/venue-tracking-2026-09-23.json`; 602,899 hourly rows stay in the session scratchpad.

| venue | listed (24 names) | rows · names · weekends | \|entry gap\| median · p90 (bp) | trigger agreement | venue-only | return corr · \|gap\| median | zero-volume entry bars |
|---|---|---|---|---|---|---|---|
| **all weekends 2026-01-30 → 09-18** | | | | | | | |
| MEXC | Jan (15), Mar–Jul (9) | 597 · 24 · 34 | 3.9 · 15.0 | 110/116 = 95% | 11 | 1.000 · 4.7 | 1 |
| OKX | Feb–Jun | 543 · 22 · 30 | 4.7 · 42.6 | 97/99 = 98% | 18 | 0.996 · 7.8 | 4 |
| Bybit | Apr 21 → Jul 31 | 440 · 23 · 22 | 5.2 · 20.7 | 54/58 = 93% | 4 | 1.000 · 9.5 | 14 |
| Bitget | Jan (12), Feb–Jun (11) | 576 · 23 · 34 | 6.2 · 34.0 | 96/104 = 92% | 13 | 0.995 · 10.6 | 1 |
| Gate | Jan–Jun (13), **Sep 3 (10 deep names)** | 292 · 23 · 33 | 7.7 · 33.4 | 55/60 = 92% | 8 | 0.999 · 7.4 | 0 |
| Hyperliquid | ≤ Feb 27 (retention cut), Mar–Aug | 464 · 19 · 30 | 8.2 · 68.6 | 86/87 = 99% | 24 | 0.986 · 15.0 | 5 |
| Kraken | Jan–Feb (7), Aug–Sep (4) | 220 · 11 · 31 | **30.0 · 152** | 26/35 = 74% | 14 | 0.749 · 78 | **137** |
| **weekends since 2026-06-01 (16)** | | | | | | | |
| OKX | | 349 · 22 · 16 | **3.2 · 10.4** | 49/51 = 96% | 3 | 1.000 · 3.6 | 4 |
| Bitget | | 363 · 23 · 16 | 4.1 · 14.7 | 48/53 = 91% | 4 | 1.000 · 5.1 | 0 |
| MEXC | | 377 · 24 · 16 | 4.2 · 15.5 | 60/63 = 95% | 6 | 1.000 · 5.3 | 0 |
| Bybit | | 357 · 23 · 16 | 4.5 · 18.5 | 49/53 = 92% | 4 | 1.000 · 6.8 | 12 |
| Hyperliquid | | 288 · 19 · 16 | 5.7 · 15.9 | 39/40 = 98% | 6 | 1.000 · 5.2 | 4 |
| Gate | | 223 · 23 · 16 | 6.3 · 18.7 | 39/43 = 91% | 3 | 0.999 · 4.5 | 0 |
| Kraken | | 136 · 11 · 16 | 18.9 · 88 | 13/17 = 76% | 9 | 0.974 · 14 | 74 |

Thin names since June, \|entry gap\| median (n) and agreement: EWJ 2.1–8.4 bp (16) 4/4 on all six; QCOM 4.2–7.5 (16) 4/4;
AXTI 4.5–13.7 (14–15) 5/6 or 6/6; AAOI 5.7–9.9 3/3; NOK 9.3–24.3 3/3; LLY 3.7–11.4 (no events); JPM 4.6 on MEXC (2/3), 16–23 bp
on Bybit, Bitget and Gate (0–1 of 2–3 events); PAYP exists only on MEXC: 12.4 bp (16), 8/9.

### Reading

1. **Since June, six venues carry Binance's weekend path.** Median entry gap 3–6 bp, p90 10–19 bp, 91–98% of Binance's
   triggers fire on the venue's own bar, and the entry→exit return correlates 0.999–1.000 with a 4–7 bp median gap. The 2026-09-09
   criteria (a) tracking and (b) same trigger, declared for Gate, pass on this window for Bybit, OKX, MEXC, Bitget, Hyperliquid
   and Gate. Kraken does not: its traded candles carry the last print through hours with no trades (137 zero-volume entry bars of
   220), so its "price" at 20:00 UTC is often stale by hours.
2. **"Same history" is earned, not inherited.** Where a venue is young the gap is not 5 bp but 100–185 bp: OKX in April (TSM
   −29 vs −215 bp on 04-10, SNDK −39 vs −198, MU −144 vs −295), MEXC's SNDK in April–May (+40 vs +192 on 05-01), Bitget and
   Hyperliquid on their early weekends (whole-period return-gap means +22 and +43 bp against −1 since June). The same venues are
   the best trackers today. Arbitrage does the work only once market makers quote the contract; on this evidence that takes weeks
   to a few months after listing, and it is not visible in the listing itself. So a new contract on another venue is admitted by
   measuring it — the declared rule below — not by assumption.
3. **Direction.** The pre-registered numbers are Binance bars. A second venue inherits them to the degree measured here, for the
   names it tracks. The reverse — a name Binance does not list, with history only on MEXC or Gate — is not a transfer question:
   that name was never in the pre-registered universe, and it enters through the newcomer route (A7/A8 cohort, its own recorded
   weekends) whichever venue's bars are used. No tracking number shortens that.
4. **Volume share is not the criterion.** (venue, name) pairs with ≥ 8 weekends, bucketed by the venue's median share of the
   combined (Binance + venue) decision bar, since June: share 0–2% → median gap 8.1 bp (p75 20); 2–5% → 4.7; 5–10% → 3.9;
   10–20% → 4.1; ≥ 20% → 4.6. Above about 2% of the combined bar the gap is flat; below it, it depends on whether anyone quotes.
   **Eligibility rule, declared now for any venue × name not yet admitted:** over its last 8 weekends, median \|entry gap\| ≤ 10 bp,
   ≥ 80% of Binance-triggered events also trigger on the venue, venue-only triggers ≤ 20% of Binance's, and the decision bar's median
   volume large enough that the intended order sits inside the cap. On today's data that admits the six venues on the deep names
   and on EWJ, QCOM, AXTI, AAOI, NOK; JPM only on MEXC; PAYP only on MEXC. Fees (criterion (d)) remain measured for Gate only.
5. **Funding has stopped being a transfer problem.** Binance's own funding over the hold on triggered events: +30/+21/+66/+79 bp
   per event in Jan/Feb/Mar/Apr, then −1, −3, +2, −4, +1 bp from May to September (−0.2 bp mean since June, 63 events). Other
   venues since June: −0.2 to −1.7 bp. MEXC's funding mirrored Binance's over the whole period (+26.5 vs +27.2 bp per event);
   Bybit, Gate, Hyperliquid, Kraken never paid it. The +143.9 bp headline includes those spring funding prints; the price
   component is what any venue, Binance included, pays now. Recorded here as a fact about the edge's composition; the edge-decay
   ledger is record-only and is not re-opened by this.
6. **Spreading the money.** On price, the data says a position on any of the six venues exits within ~5 bp of the Binance exit
   since June, so a split costs nothing at 11:00 NY. What the data cannot price is why one would split: Gate force-settled the
   predecessors of 11 of these names three weeks ago; the ten deep names on Gate are twenty days old; Bybit's are 8–22 weeks old;
   Hyperliquid serves only ~5,000 hours of history and had one-sided books on thin names; fees are unmeasured on four venues; and
   every added venue is one more manual exit in the same minute. The 08:27 addendum's split (Binance 61% of the k allocation,
   the rest across five venues) is the split that the *fill* justifies. A split for its own sake — half the book away from Binance
   — puts deep-name orders into books that carry 5–35% of the combined decision bar and would need to be re-priced with the cap
   measurement (LIQUIDITY_CAP_MEASUREMENT.md) on each venue's own ladder, which was not done here.
