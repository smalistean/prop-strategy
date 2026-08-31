# Weekend fade with funding — result for review

**Date:** 2026-08-30 11:55 UTC · **Author:** Claude
**Study docs:** design in `WEEKEND_FADE_FUNDING_PREREGISTRATION.md` (frozen before execution), code in `scripts/analysis-weekend-fade-funding.sql`, board row updated in `IDEA_BOARD.md` #1.

## The result in one sentence

Buying every Binance tokenized US stock/ETF perp that fell ≥50 bp over the weekend (entry Sunday 20:00 UTC, exit ~1h after Monday's US open) returned **+147.5 bp per triggered weekend on average after funding and 9 bp costs** (median +140.5, 17 weekends, Dec 2025–Aug 2026, t=1.82), and **funding added +26 bp on average rather than costing anything** — so idea board #1 survives the I87 funding critique and stays at status "measuring".

## What question this answered

Board #1 had measured the weekend fade at +90.7 bp/event **price only**. The I87 finding (weekend funding on these perps is 1.5–3x weekday magnitude) raised the possibility that funding payments silently erase that return, because the position is held through Sunday-night funding prints. This study re-ran the event study with funding P&L in the ledger. Definitions were frozen in the pre-registration before any outcome query ran.

## Headline numbers

| Cut | n weekends | n events | mean net | median net | SD | t | worst weekend | price part | funding part |
|---|---|---|---|---|---|---|---|---|---|
| **Fade (trigger ≤ −50 bp)** | 17 | 85 | **+147.5 bp** | **+140.5** | 334.5 | **1.82** | −427.9 | +130.2 | **+26.2** |
| Control (all weekends, no trigger) | 26 | 425 | +50.2 | +12.3 | 207.2 | 1.24 | — | +52.8 | +6.4 |
| Metals/energy (exploratory) | 11 | 24 | +18.7 | −49.7 | 174.4 | 0.36 | −173.0 | +26.7 | +1.0 |

All numbers are de-clustered: one observation per weekend = the average across that weekend's triggered symbols. 12 of 17 triggered weekends were net positive (71%).

## Every triggered weekend (the full de-clustered series)

| Weekend (Fri) | symbols triggered | avg weekend move | price bp | funding bp | **net bp** |
|---|---|---|---|---|---|
| 2026-01-30 | 1 | −65 | −177 | +30 | **−156** |
| 2026-02-20 | 2 | −58 | −421 | +2 | **−428** |
| 2026-02-27 | 3 | −79 | +116 | +33 | **+141** |
| 2026-03-06 | 6 | −117 | +275 | +88 | **+354** |
| 2026-03-20 | 2 | −120 | +333 | +143 | **+467** |
| 2026-03-27 | 6 | −98 | +75 | +6 | **+73** |
| 2026-04-10 | 12 | −113 | +314 | +95 | **+400** |
| 2026-04-17 | 17 | −123 | −129 | +68 | **−70** |
| 2026-05-08 | 1 | −61 | +973 | −8 | **+956** |
| 2026-05-15 | 5 | −143 | −236 | 0 | **−245** |
| 2026-06-05 | 11 | −168 | +428 | −3 | **+417** |
| 2026-06-12 | 2 | −183 | +237 | −1 | **+228** |
| 2026-06-26 | 3 | −203 | +320 | −5 | **+306** |
| 2026-07-10 | 4 | −110 | −279 | +1 | **−287** |
| 2026-07-17 | 7 | −140 | +91 | −3 | **+79** |
| 2026-07-24 | 2 | −227 | +153 | +2 | **+146** |
| 2026-08-21 | 1 | −52 | +138 | −3 | **+126** |

## Three specific findings

**1. Funding helps the fade.** After a weekend dump the perp trades below fair value, so funding prints negative, and negative funding means shorts pay longs — the fade position collects it. Per-event funding ranged from −45 bp (against) to +200 bp (for); the average is +26 bp per weekend in favor. The I87 concern is resolved in the strategy's favor.

**2. The trigger matters.** Unconditional weekends earn +50 mean / +12 median; the ≤ −50 bp condition triples the mean and moves the median to +140. The return is not a general Monday drift in these perps — it is specific to weekends where the perp dislocated.

**3. Metals/energy do not work.** Their underlying (CME Globex) reopens Sunday evening, so the dislocation window is short; t=0.36, median negative. The fade is an equity-perp effect only.

## How this loses money (observed, not hypothetical)

- **Trend weekends:** 2026-02-20 (−428) and 2026-05-15 (−245) — every triggered symbol kept falling on Monday. The worst de-clustered weekend cost 4.3% of that weekend's stake.
- **Single-name news gaps:** AXTI 2026-07-10 net −1,277 bp, OPENAI 2026-07-17 net −1,017 bp — the weekend drop was company-specific information, not crypto noise, and Monday confirmed it. Consequence: trade the basket of all triggered symbols, never one name.
- **Funding tail against:** PAYP 2026-04-17 paid −45 bp funding on top of a −400 bp price loss. Rare in this sample but exactly the unpredictability I87 warned about.

## What this result is not

- **Not statistical proof.** t=1.82 on 17 weekends; roughly a 4–5% probability of a mean this large from noise. The pre-registered rule says: ledger updated, no promotion.
- **Not independent confirmation of the original +90.7 bp.** The original implementation was not preserved; this is a re-implementation with pinned definitions and a wider universe (27 symbols vs 10), on a largely overlapping sample. The two mean values should not be compared as replication.
- **Not executable at 9 bp yet.** The 9 bp cost is the board's weekday all-in taker figure; Sunday-20:00 books are thinner and no weekend execution-quality measurement exists yet. That measurement is the gate before any live-money discussion.

## Data-quality note (affects other studies too)

`binance_perp_funding_rate` stores each funding print 1–3 times (identical rates). The first run double-counted funding and was discarded; the final numbers dedupe on (funding_time, funding_rate), verified by hand against raw prints for COINUSDT 2026-03-08/09. Funding cadence on these perps is 8h (00:00/08:00/16:00 UTC), and genuine 0.0000-rate prints exist. Any past or future SUM over this table must dedupe first; the I87 demonstration table in `ideas-triage-round1.md` carries a correction note. An importer fix (unique constraint + upsert) is running as a separate task as of 2026-08-30.

## Decision to review

Recommended: **keep accumulating weekends (~2 triggers/month) and measure weekend execution quality next** — pull Sunday-20:00 spread/depth from `binance_book_ticker_second` where covered, or capture the next few weekends live. Tiny-live sizing only after the execution number exists, and sized to survive a −430 bp weekend and a −1,280 bp single name.
